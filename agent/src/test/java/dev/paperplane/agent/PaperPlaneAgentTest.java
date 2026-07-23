package dev.paperplane.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.net.URLClassLoader;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

class PaperPlaneAgentTest {

    private final List<ClassFileTransformer> registered = new ArrayList<>();

    private Instrumentation mockInstrumentation() {
        return (Instrumentation) java.lang.reflect.Proxy.newProxyInstance(
                Instrumentation.class.getClassLoader(),
                new Class[]{Instrumentation.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("addTransformer") && args != null && args.length >= 1) {
                        registered.add((ClassFileTransformer) args[0]);
                    }
                    if (method.getName().equals("toString")) return "mock-instrumentation";
                    return null;
                }
        );
    }

    @BeforeEach
    void resetInstrumentation() throws Exception {
        Field field = PaperPlaneAgent.class.getDeclaredField("instrumentation");
        field.setAccessible(true);
        field.set(null, null);
        registered.clear();
    }

    @Test
    void getInstrumentationReturnsNullBeforePremain() {
        assertNull(PaperPlaneAgent.getInstrumentation(),
                "Should return null before premain is called");
    }

    @Test
    void premainStoresInstrumentationAndRegistersTheCrcRecorder() {
        Instrumentation inst = mockInstrumentation();

        PaperPlaneAgent.premain(null, inst);

        assertSame(inst, PaperPlaneAgent.getInstrumentation(),
                "Should return the same instance that was passed to premain");
        assertEquals(1, registered.size(),
                "premain must register the CRC-recording load hook");
    }

    @Test
    void multiplePremainCallsLastOneWins() {
        Instrumentation first = mockInstrumentation();
        Instrumentation second = mockInstrumentation();

        PaperPlaneAgent.premain(null, first);
        PaperPlaneAgent.premain(null, second);

        assertSame(second, PaperPlaneAgent.getInstrumentation(),
                "Last premain call should win");
    }

    // ── CRC registry ────────────────────────────────────────────────────

    @Test
    void loadHookRecordsTheCrcOfInitiallyDefinedBytes() throws Exception {
        PaperPlaneAgent.premain(null, mockInstrumentation());
        ClassFileTransformer recorder = registered.get(0);
        ClassLoader loader = new URLClassLoader(new URL[0], null);
        byte[] bytes = {1, 2, 3, 4};

        byte[] result = recorder.transform(
                (Module) null, loader, "com/example/Foo", null, null, bytes);

        assertNull(result, "the recorder must never transform");
        CRC32 crc = new CRC32();
        crc.update(bytes);
        assertEquals(crc.getValue(), PaperPlaneAgent.getLoadedCrc(loader, "com.example.Foo"));
    }

    @Test
    void redefinitionPassesAreNotRecordedByTheHook() throws Exception {
        // The patcher records successful redefinitions explicitly via updateCrc; the hook must
        // ignore non-initial passes so a foreign retransform's reconstituted bytes can't corrupt
        // the registry.
        PaperPlaneAgent.premain(null, mockInstrumentation());
        ClassFileTransformer recorder = registered.get(0);
        ClassLoader loader = new URLClassLoader(new URL[0], null);

        recorder.transform((Module) null, loader, "com/example/Foo", String.class, null,
                new byte[]{9, 9, 9});

        assertEquals(PaperPlaneAgent.UNKNOWN_CRC,
                PaperPlaneAgent.getLoadedCrc(loader, "com.example.Foo"));
    }

    @Test
    void updateCrcOverridesTheLoadRecord() {
        ClassLoader loader = new URLClassLoader(new URL[0], null);

        PaperPlaneAgent.updateCrc(loader, "com.example.Foo", 42L);
        assertEquals(42L, PaperPlaneAgent.getLoadedCrc(loader, "com.example.Foo"));

        PaperPlaneAgent.updateCrc(loader, "com.example.Foo", 43L);
        assertEquals(43L, PaperPlaneAgent.getLoadedCrc(loader, "com.example.Foo"));
    }

    @Test
    void unknownClassesAndNullLoadersReportUnknownCrc() {
        ClassLoader loader = new URLClassLoader(new URL[0], null);

        assertEquals(PaperPlaneAgent.UNKNOWN_CRC,
                PaperPlaneAgent.getLoadedCrc(loader, "com.example.NeverSeen"));
        assertEquals(PaperPlaneAgent.UNKNOWN_CRC,
                PaperPlaneAgent.getLoadedCrc(null, "com.example.Foo"));
        // A null loader update must be ignored, not throw.
        PaperPlaneAgent.updateCrc(null, "com.example.Foo", 1L);
    }

    @Test
    void patchRecordsWinOverDefineRecordsAndFlagWasPatched() throws Exception {
        PaperPlaneAgent.premain(null, mockInstrumentation());
        ClassFileTransformer recorder = registered.get(0);
        ClassLoader loader = new URLClassLoader(new URL[0], null);
        byte[] defined = {1, 2, 3, 4};

        recorder.transform((Module) null, loader, "com/example/Foo", null, null, defined);
        assertFalse(PaperPlaneAgent.wasPatched(loader, "com.example.Foo"),
                "a plain define is not a patch");

        PaperPlaneAgent.updateCrc(loader, "com.example.Foo", 42L);

        assertEquals(42L, PaperPlaneAgent.getLoadedCrc(loader, "com.example.Foo"),
                "the patch record describes what's running now");
        assertTrue(PaperPlaneAgent.wasPatched(loader, "com.example.Foo"));
        assertFalse(PaperPlaneAgent.wasPatched(loader, "com.example.Other"));
        assertFalse(PaperPlaneAgent.wasPatched(null, "com.example.Foo"));
    }

    // ── Package filter ──────────────────────────────────────────────────

    @Test
    void parsePrefixesConvertsPackagesToInternalFormPrefixes() {
        assertArrayEquals(new String[]{"com/acme/", "dev/paperplane/"},
                PaperPlaneAgent.parsePrefixes("com.acme, dev.paperplane"));
        assertArrayEquals(new String[]{"com/acme/"},
                PaperPlaneAgent.parsePrefixes("com.acme,, ,"));
    }

    @Test
    void parsePrefixesOnNullOrBlankArgsRecordsEverything() {
        assertArrayEquals(new String[0], PaperPlaneAgent.parsePrefixes(null));
        assertArrayEquals(new String[0], PaperPlaneAgent.parsePrefixes("  "));
        assertTrue(PaperPlaneAgent.records(new String[0], "any/thing/At/All"),
                "no prefixes means record everything — the bare -javaagent contract");
    }

    @Test
    void recordsMatchesOnlyClassesUnderAPrefix() {
        String[] prefixes = PaperPlaneAgent.parsePrefixes("com.acme");
        assertTrue(PaperPlaneAgent.records(prefixes, "com/acme/Foo"));
        assertTrue(PaperPlaneAgent.records(prefixes, "com/acme/util/Helper"));
        assertFalse(PaperPlaneAgent.records(prefixes, "com/acmeplugin/Foo"),
                "the trailing slash must prevent sibling-package prefix bleed");
        assertFalse(PaperPlaneAgent.records(prefixes, "net/minecraft/server/Level"));
    }

    @Test
    void loadHookSkipsClassesOutsideTheConfiguredPackages() throws Exception {
        PaperPlaneAgent.premain("com.example", mockInstrumentation());
        ClassFileTransformer recorder = registered.get(0);
        ClassLoader loader = new URLClassLoader(new URL[0], null);
        byte[] bytes = {1, 2, 3, 4};

        recorder.transform((Module) null, loader, "net/minecraft/server/Level", null, null, bytes);
        recorder.transform((Module) null, loader, "com/example/Foo", null, null, bytes);

        assertEquals(PaperPlaneAgent.UNKNOWN_CRC,
                PaperPlaneAgent.getLoadedCrc(loader, "net.minecraft.server.Level"),
                "an out-of-package class must never be hashed or retained");
        CRC32 crc = new CRC32();
        crc.update(bytes);
        assertEquals(crc.getValue(), PaperPlaneAgent.getLoadedCrc(loader, "com.example.Foo"));
    }

    @Test
    void registryIsScopedPerLoader() {
        ClassLoader a = new URLClassLoader(new URL[0], null);
        ClassLoader b = new URLClassLoader(new URL[0], null);

        PaperPlaneAgent.updateCrc(a, "com.example.Foo", 1L);
        PaperPlaneAgent.updateCrc(b, "com.example.Foo", 2L);

        assertEquals(1L, PaperPlaneAgent.getLoadedCrc(a, "com.example.Foo"));
        assertEquals(2L, PaperPlaneAgent.getLoadedCrc(b, "com.example.Foo"));
    }
}
