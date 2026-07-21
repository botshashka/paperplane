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
    void registryIsScopedPerLoader() {
        ClassLoader a = new URLClassLoader(new URL[0], null);
        ClassLoader b = new URLClassLoader(new URL[0], null);

        PaperPlaneAgent.updateCrc(a, "com.example.Foo", 1L);
        PaperPlaneAgent.updateCrc(b, "com.example.Foo", 2L);

        assertEquals(1L, PaperPlaneAgent.getLoadedCrc(a, "com.example.Foo"));
        assertEquals(2L, PaperPlaneAgent.getLoadedCrc(b, "com.example.Foo"));
    }
}
