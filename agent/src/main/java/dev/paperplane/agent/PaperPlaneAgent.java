package dev.paperplane.agent;

import java.lang.instrument.Instrumentation;

/**
 * Minimal Java agent for PaperPlane HMR.
 *
 * Loaded via -javaagent on the Paper server JVM. Stores the Instrumentation
 * instance so the companion plugin can use it for in-place class redefinition.
 */
public class PaperPlaneAgent {
    private static volatile Instrumentation instrumentation;

    public static void premain(String args, Instrumentation inst) {
        instrumentation = inst;
    }

    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }
}
