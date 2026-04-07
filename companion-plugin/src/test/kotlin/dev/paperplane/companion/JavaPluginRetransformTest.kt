package dev.paperplane.companion

import java.lang.instrument.ClassFileTransformer
import java.security.ProtectionDomain
import net.bytebuddy.agent.ByteBuddyAgent
import org.junit.jupiter.api.Test

class JavaPluginRetransformTest {

  @Test
  fun `retransform JavaPlugin via real Instrumentation`() {
    val inst = ByteBuddyAgent.install()
    val inner = JavaPluginTransformer()
    val logging =
        object : ClassFileTransformer {
          override fun transform(
              loader: ClassLoader?,
              className: String?,
              classBeingRedefined: Class<*>?,
              protectionDomain: ProtectionDomain?,
              classfileBuffer: ByteArray?,
          ): ByteArray? {
            if (className != "org/bukkit/plugin/java/JavaPlugin") return null
            System.err.println(
                "TRANSFORMER: called className=$className size=${classfileBuffer?.size} " +
                    "loader=${loader?.javaClass?.name} " +
                    "classBeingRedefined.loader=${classBeingRedefined?.classLoader?.javaClass?.name}"
            )
            return try {
              val result =
                  inner.transform(
                      loader,
                      className,
                      classBeingRedefined,
                      protectionDomain,
                      classfileBuffer,
                  )
              System.err.println("TRANSFORMER: returning ${result?.size} bytes")
              result
            } catch (t: Throwable) {
              System.err.println("TRANSFORMER: threw ${t.javaClass.name}: ${t.message}")
              t.printStackTrace()
              throw t
            }
          }
        }
    inst.addTransformer(logging, true)
    try {
      inst.retransformClasses(org.bukkit.plugin.java.JavaPlugin::class.java)
      println("RETRANSFORM-OK")
    } catch (t: Throwable) {
      var cur: Throwable? = t
      var depth = 0
      while (cur != null && depth < 10) {
        System.err.println("  [$depth] ${cur.javaClass.name}: ${cur.message}")
        cur.stackTrace.take(10).forEach { System.err.println("      at $it") }
        cur = cur.cause
        depth++
      }
      throw t
    } finally {
      inst.removeTransformer(logging)
    }
  }
}
