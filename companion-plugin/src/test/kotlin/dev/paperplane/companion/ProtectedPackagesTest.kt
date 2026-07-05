package dev.paperplane.companion

import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Structural guard: the companion JAR must NOT ship classes in server-protected packages.
 *
 * Paper's `PluginClassLoader` refuses to load classes whose names start with `org.bukkit.*`,
 * `net.minecraft.*`, or `io.papermc.*` from a plugin JAR — only the server itself is allowed to own
 * those namespaces. Shipping a class there compiles fine but throws `NoClassDefFoundError` at
 * runtime the first time the host tries to use it.
 *
 * This test scans the compiled `main` classes directory rather than the assembled shadowJar so it
 * runs as part of the normal `test` task without depending on `shadowJar`. The two file sets are
 * identical for our purposes — shadow only copies what `classes` produced.
 */
class ProtectedPackagesTest {

  @Test
  fun `compiled main classes are not in server-protected packages`() {
    val classesRoot = File("build/classes")
    if (!classesRoot.isDirectory) {
      // Test should never be skipped silently — fail loudly so a misconfigured run surfaces.
      error("Expected $classesRoot to exist; did the test task depend on classes?")
    }

    val offenders =
        classesRoot
            .walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .map { it.relativeTo(classesRoot).invariantSeparatorsPath }
            // First two segments are the language+sourceset (e.g. "kotlin/main/..."); strip them.
            .mapNotNull {
              val parts = it.split("/")
              if (parts.size > 2) parts.drop(2).joinToString("/") else null
            }
            .filter { rel ->
              rel.startsWith("org/bukkit/") ||
                  rel.startsWith("net/minecraft/") ||
                  rel.startsWith("io/papermc/")
            }
            .toList()

    assertTrue(
        offenders.isEmpty(),
        "Companion ships classes in server-protected packages — Paper's PluginClassLoader will refuse to load them at runtime: $offenders",
    )
  }
}
