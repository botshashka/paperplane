package dev.paperplane.companion

import java.lang.instrument.Instrumentation

/**
 * Resolves the PaperPlane javaagent's [Instrumentation] handle, or `null` when the agent isn't
 * loaded.
 *
 * The agent powers two optional capabilities — the hot-swap tier (in-place class redefinition) and
 * NMS-class detection — both of which degrade gracefully when it's absent. Restart and blue-green
 * modes legitimately run without the agent, so a missing agent is never fatal: full host reloads
 * still work.
 */
object AgentAccess {
  fun instrumentation(): Instrumentation? =
      try {
        val agentClass =
            ClassLoader.getSystemClassLoader().loadClass("dev.paperplane.agent.PaperPlaneAgent")
        agentClass.getMethod("getInstrumentation").invoke(null) as? Instrumentation
      } catch (_: ReflectiveOperationException) {
        null
      }
}
