package dev.paperplane.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Collections;
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

    /** Loader → (binary class name → CRC32 of defined bytes). Weak so dead loaders unpin. */
    private static final Map<ClassLoader, Map<String, Long>> loadedCrcs =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Sentinel for {@link #getLoadedCrc} when the class was never recorded. */
    public static final long UNKNOWN_CRC = -1L;

    public static void premain(String args, Instrumentation inst) {
        instrumentation = inst;
        inst.addTransformer(new CrcRecorder());
    }

    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }

    /**
     * The CRC32 of the bytes {@code loader} defined {@code binaryName} from, or
     * {@link #UNKNOWN_CRC} when the class wasn't seen (loaded before premain, or a null/bootstrap
     * loader).
     */
    public static long getLoadedCrc(ClassLoader loader, String binaryName) {
        if (loader == null) return UNKNOWN_CRC;
        Map<String, Long> byName = loadedCrcs.get(loader);
        if (byName == null) return UNKNOWN_CRC;
        Long crc = byName.get(binaryName);
        return crc == null ? UNKNOWN_CRC : crc;
    }

    /** Records the CRC of a successful in-place redefinition (called by the companion patcher). */
    public static void updateCrc(ClassLoader loader, String binaryName, long crc) {
        if (loader == null) return;
        loadedCrcs.computeIfAbsent(loader, l -> new ConcurrentHashMap<>()).put(binaryName, crc);
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
            if (loader != null && className != null && classBeingRedefined == null) {
                CRC32 crc = new CRC32();
                crc.update(classfileBuffer);
                updateCrc(loader, className.replace('/', '.'), crc.getValue());
            }
            return null;
        }
    }
}
