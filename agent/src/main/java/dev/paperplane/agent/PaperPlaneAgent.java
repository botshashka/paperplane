package dev.paperplane.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;

/**
 * Minimal Java agent for the PaperPlane instant tier.
 *
 * Loaded via -javaagent on every dev-server JVM. Does two things:
 *
 * <ul>
 *   <li>Stores the {@link Instrumentation} instance so the companion plugin can redefine classes
 *       in place.
 *   <li>Maintains a per-classloader registry of each loaded class's CRC32, recorded from the
 *       exact bytes the JVM defined. This is the instant tier's verification ground truth: the
 *       filesystem is already overwritten by verify time, and retransformation-based capture is
 *       byte-unfaithful (HotSpot hands retransformers a <em>reconstituted</em> class file whose
 *       constant-pool encoding differs from the loaded original). The load hook sees the original
 *       bytes; successful patches update the registry via {@link #updateCrc}.
 * </ul>
 *
 * The transformer only records on initial class definition ({@code classBeingRedefined == null});
 * redefinition passes are recorded explicitly by the patcher, so a foreign agent's retransform
 * runs can never corrupt the registry with reconstituted bytes.
 */
public class PaperPlaneAgent {
    private static volatile Instrumentation instrumentation;

    /**
     * Loader → (binary class name → CRC32 of defined bytes), recorded by the load hook. Weak so
     * dead loaders unpin. Kept separate from {@link #patchedCrcs}: a define record's bytes came
     * from the loader's own source (jar or directory) — possibly rewritten by a server-side
     * class-file transformer — while a patch record's bytes are exactly what the patcher wrote.
     * The companion's verification treats the two differently.
     */
    private static final Map<ClassLoader, Map<String, Long>> definedCrcs =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Loader → (binary class name → CRC32 of the last in-place redefinition's bytes). */
    private static final Map<ClassLoader, Map<String, Long>> patchedCrcs =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Sentinel for {@link #getLoadedCrc} when the class was never recorded. */
    public static final long UNKNOWN_CRC = -1L;

    /**
     * Internal-form ({@code me/dev/}) name prefixes the load hook records, from the agent's
     * {@code -javaagent:...=<comma-separated packages>} argument. Empty means record everything,
     * which is what a bare {@code -javaagent} (tests, manual runs) gets.
     *
     * <p><b>The contract when a plugin class falls outside these prefixes:</b> nothing records it,
     * {@link #getLoadedCrc} answers {@link #UNKNOWN_CRC}, and the companion refuses the patch with
     * "no load record" — the CLI then escalates to a normal swap. Unfiltered classes cost a
     * <em>slower</em> rebuild, never a wrong one. That direction is deliberate, because the filter
     * is a guess: it is derived from the plugin main class's package, and a project whose sources
     * span unrelated roots can legitimately produce classes outside it.
     */
    private static volatile String[] recordedPrefixes = new String[0];

    public static void premain(String args, Instrumentation inst) {
        instrumentation = inst;
        recordedPrefixes = parsePrefixes(args);
        inst.addTransformer(new CrcRecorder());
    }

    /**
     * Parses the agent argument into internal-form prefixes. Only the plugin's own classes are ever
     * read back, but the hook sees every class the JVM defines — tens of thousands on Paper's
     * parallel boot path — so hashing them all costs hundreds of milliseconds and megabytes of
     * retained map on every server start, of which over 99% is never read.
     */
    static String[] parsePrefixes(String args) {
        if (args == null || args.isBlank()) return new String[0];
        String[] parts = args.split(",");
        List<String> prefixes = new ArrayList<>(parts.length);
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            String internal = trimmed.replace('.', '/');
            prefixes.add(internal.endsWith("/") ? internal : internal + "/");
        }
        return prefixes.toArray(new String[0]);
    }

    /** Whether the load hook records {@code internalName} (internal form, slash-separated). */
    static boolean records(String[] prefixes, String internalName) {
        if (prefixes.length == 0) return true;
        for (String prefix : prefixes) {
            if (internalName.startsWith(prefix)) return true;
        }
        return false;
    }

    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }

    /**
     * The CRC32 of the bytes {@code loader} is currently running {@code binaryName} with — the
     * last patch if one landed, else the bytes the loader defined — or {@link #UNKNOWN_CRC} when
     * the class wasn't seen (loaded before premain, or a null/bootstrap loader).
     */
    public static long getLoadedCrc(ClassLoader loader, String binaryName) {
        long patched = lookup(patchedCrcs, loader, binaryName);
        return patched != UNKNOWN_CRC ? patched : lookup(definedCrcs, loader, binaryName);
    }

    /**
     * Whether the running bytes of {@code binaryName} came from an in-place patch rather than the
     * loader's own source. Patched classes must verify against the patch record alone — their
     * backing jar or directory no longer describes what's running.
     */
    public static boolean wasPatched(ClassLoader loader, String binaryName) {
        return lookup(patchedCrcs, loader, binaryName) != UNKNOWN_CRC;
    }

    /** Records the CRC of a successful in-place redefinition (called by the companion patcher). */
    public static void updateCrc(ClassLoader loader, String binaryName, long crc) {
        if (loader == null) return;
        patchedCrcs.computeIfAbsent(loader, l -> new ConcurrentHashMap<>()).put(binaryName, crc);
    }

    private static long lookup(
            Map<ClassLoader, Map<String, Long>> registry, ClassLoader loader, String binaryName) {
        if (loader == null) return UNKNOWN_CRC;
        Map<String, Long> byName = registry.get(loader);
        if (byName == null) return UNKNOWN_CRC;
        Long crc = byName.get(binaryName);
        return crc == null ? UNKNOWN_CRC : crc;
    }

    private static final class CrcRecorder implements ClassFileTransformer {
        @Override
        public byte[] transform(
                Module module,
                ClassLoader loader,
                String className,
                Class<?> classBeingRedefined,
                ProtectionDomain protectionDomain,
                byte[] classfileBuffer) {
            // The prefix test comes first so an unrecorded class never hashes its bytes and never
            // touches the registry — which is also why the synchronized map below is no longer on
            // the boot path: only the plugin's own definitions ever take that monitor.
            if (loader != null
                    && className != null
                    && classBeingRedefined == null
                    && records(recordedPrefixes, className)) {
                CRC32 crc = new CRC32();
                crc.update(classfileBuffer);
                definedCrcs
                        .computeIfAbsent(loader, l -> new ConcurrentHashMap<>())
                        .put(className.replace('/', '.'), crc.getValue());
            }
            return null;
        }
    }
}
