package dev.paperplane.cli.devserver.instant

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BuildCandidateTest {

  @TempDir lateinit var tempDir: File

  @Test
  fun `capture maps class files to FQCNs across multiple dirs and resources to relative paths`() {
    val javaOut = File(tempDir, "classes/java/main").apply { mkdirs() }
    val kotlinOut = File(tempDir, "classes/kotlin/main").apply { mkdirs() }
    File(javaOut, "com/example").mkdirs()
    File(javaOut, "com/example/Main.class").writeBytes(byteArrayOf(1, 2))
    File(kotlinOut, "com/example").mkdirs()
    File(kotlinOut, "com/example/Util.class").writeBytes(byteArrayOf(3))
    File(kotlinOut, "com/example/notes.txt").writeText("not a class")
    val resources = File(tempDir, "resources/main").apply { mkdirs() }
    File(resources, "config.yml").writeText("a: 1")
    File(resources, "lang").mkdirs()
    File(resources, "lang/en.yml").writeText("hello")

    val candidate = BuildCandidate.capture(listOf(javaOut, kotlinOut), resources)

    assertEquals(setOf("com.example.Main", "com.example.Util"), candidate.classes.keys)
    assertTrue(candidate.classes.getValue("com.example.Main").contentEquals(byteArrayOf(1, 2)))
    assertEquals(setOf("config.yml", "lang/en.yml"), candidate.resourceCrcs.keys)
  }

  @Test
  fun `capture tolerates missing dirs and a null resources dir`() {
    val candidate = BuildCandidate.capture(listOf(File(tempDir, "nope")), null)
    assertTrue(candidate.classes.isEmpty())
    assertTrue(candidate.resourceCrcs.isEmpty())
  }

  @Test
  fun `capture order does not change the sourceDirs identity`() {
    // sourceDirs feeds the classifier's OUTPUT_LAYOUT_CHANGED gate. Gradle owes no ordering
    // guarantee for classesDirs across invocations, so a mere reorder must compare equal — only a
    // genuinely moved directory may escalate.
    val javaOut = File(tempDir, "classes/java/main").apply { mkdirs() }
    val kotlinOut = File(tempDir, "classes/kotlin/main").apply { mkdirs() }

    val ab = BuildCandidate.capture(listOf(javaOut, kotlinOut), null)
    val ba = BuildCandidate.capture(listOf(kotlinOut, javaOut), null)

    assertEquals(ab.sourceDirs, ba.sourceDirs)
  }

  @Test
  fun `classCrc is stable per content and zero for unknown classes`() {
    val candidate = BuildCandidate(mapOf("com.example.A" to byteArrayOf(1, 2, 3)), emptyMap())
    assertEquals(BuildCandidate.crc32(byteArrayOf(1, 2, 3)), candidate.classCrc("com.example.A"))
    assertEquals(0L, candidate.classCrc("com.example.Missing"))
  }
}
