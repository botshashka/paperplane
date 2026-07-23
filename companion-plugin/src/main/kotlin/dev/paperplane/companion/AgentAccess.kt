package dev.paperplane.companion

import java.lang.instrument.Instrumentation

/**
 * Resolves the PaperPlane javaagent's [Instrumentation] handle, or `null` when the agent isn't
 * loaded.
 *
 * The agent powers two optional capabilities — the instant tier (in-place class redefinition plus
 * its load-hook CRC registry) and NMS-class detection — both of which degrade gracefully when it's
 * absent. A missing agent is never fatal: the welcome reports capability `none`, the lane
 * escalates, and full swaps still work in every mode.
 */
object AgentAccess {
  /** Mirror of the agent's `UNKNOWN_CRC` sentinel — the class was never seen by the load hook. */
  const val UNKNOWN_CRC = -1L

  fun instrumentation(): Instrumentation? =
      try {
        agentClass().getMethod("getInstrumentation").invoke(null) as? Instrumentation
      } catch (_: ReflectiveOperationException) {
        null
      }

  /**
   * The CRC32 of the bytes [loader] actually defined [binaryName] from, per the agent's load-hook
   * registry — the instant tier's verification ground truth. [UNKNOWN_CRC] when unrecorded or the
   * agent is absent.
   */
  fun loadedCrc(loader: ClassLoader, binaryName: String): Long =
      try {
        agentClass()
            .getMethod("getLoadedCrc", ClassLoader::class.java, String::class.java)
            .invoke(null, loader, binaryName) as Long
      } catch (_: ReflectiveOperationException) {
        UNKNOWN_CRC
      }

  /**
   * Whether [binaryName]'s running bytes came from an in-place patch. A patched class must verify
   * against the agent's patch record alone — its backing jar or directory no longer describes
   * what's running. `false` when unrecorded or the agent is absent.
   */
  fun wasPatched(loader: ClassLoader, binaryName: String): Boolean =
      try {
        agentClass()
            .getMethod("wasPatched", ClassLoader::class.java, String::class.java)
            .invoke(null, loader, binaryName) as Boolean
      } catch (_: ReflectiveOperationException) {
        false
      }

  /** Records a successful in-place redefinition in the agent's registry. */
  fun updateCrc(loader: ClassLoader, binaryName: String, crc: Long) {
    try {
      agentClass()
          .getMethod("updateCrc", ClassLoader::class.java, String::class.java, Long::class.java)
          .invoke(null, loader, binaryName, crc)
    } catch (_: ReflectiveOperationException) {
      // Agent absent — the patcher refuses before ever reaching an update.
    }
  }

  private fun agentClass(): Class<*> =
      ClassLoader.getSystemClassLoader().loadClass("dev.paperplane.agent.PaperPlaneAgent")
}
