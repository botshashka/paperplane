package dev.paperplane.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

class PaperPlaneAgentTest {

    @BeforeEach
    void resetInstrumentation() throws Exception {
        // Reset the static field between tests
        Field field = PaperPlaneAgent.class.getDeclaredField("instrumentation");
        field.setAccessible(true);
        field.set(null, null);
    }

    @Test
    void getInstrumentationReturnsNullBeforePremain() {
        assertNull(PaperPlaneAgent.getInstrumentation(),
                "Should return null before premain is called");
    }

    @Test
    void premainStoresInstrumentation() {
        // We pass null since we cannot create a real Instrumentation in unit tests.
        // premain just stores whatever is passed.
        PaperPlaneAgent.premain(null, null);
        // After premain(null, null), the field is set to null — same as initial state.
        // This verifies premain doesn't throw when called with null args.
        assertNull(PaperPlaneAgent.getInstrumentation());
    }

    @Test
    void getInstrumentationReturnsStoredInstanceAfterPremain() throws Exception {
        // Use reflection to set a non-null value since we can't construct Instrumentation
        Field field = PaperPlaneAgent.class.getDeclaredField("instrumentation");
        field.setAccessible(true);

        // Simulate premain having stored an instrumentation instance by using a proxy
        Instrumentation mockInst = (Instrumentation) java.lang.reflect.Proxy.newProxyInstance(
                Instrumentation.class.getClassLoader(),
                new Class[]{Instrumentation.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("toString")) return "mock-instrumentation";
                    return null;
                }
        );

        PaperPlaneAgent.premain(null, mockInst);
        assertSame(mockInst, PaperPlaneAgent.getInstrumentation(),
                "Should return the same instance that was passed to premain");
    }

    @Test
    void multiplePremainCallsLastOneWins() throws Exception {
        Instrumentation first = (Instrumentation) java.lang.reflect.Proxy.newProxyInstance(
                Instrumentation.class.getClassLoader(),
                new Class[]{Instrumentation.class},
                (proxy, method, args) -> null
        );
        Instrumentation second = (Instrumentation) java.lang.reflect.Proxy.newProxyInstance(
                Instrumentation.class.getClassLoader(),
                new Class[]{Instrumentation.class},
                (proxy, method, args) -> null
        );

        PaperPlaneAgent.premain(null, first);
        PaperPlaneAgent.premain(null, second);

        assertSame(second, PaperPlaneAgent.getInstrumentation(),
                "Last premain call should win");
        assertNotSame(first, PaperPlaneAgent.getInstrumentation(),
                "First instrumentation should be replaced");
    }
}
