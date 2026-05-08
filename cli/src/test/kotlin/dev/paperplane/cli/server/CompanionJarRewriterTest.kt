package dev.paperplane.cli.server

import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CompanionJarRewriterTest {

  @TempDir lateinit var tempDir: File

  // ── injectDepends (string transform) ────────────────────────────────

  @Test
  fun `injectDepends appends depend list when none existed`() {
    val original =
        """
        name: PaperPlane
        main: dev.paperplane.companion.CompanionPlugin
        version: 1.0
        load: POSTWORLD
        """
            .trimIndent()
    val result =
        CompanionJarRewriter.injectDepends(
            original,
            listOf("WorldGuard", "Vault"),
            emptyList(),
        )
    assertTrue(result.contains("depend: [WorldGuard, Vault]"))
    assertFalse(result.contains("softdepend"))
  }

  @Test
  fun `injectDepends appends softdepend list when none existed`() {
    val original = "name: PaperPlane\nmain: x\nversion: 1.0\nload: POSTWORLD"
    val result =
        CompanionJarRewriter.injectDepends(original, emptyList(), listOf("PlaceholderAPI"))
    assertTrue(result.contains("softdepend: [PlaceholderAPI]"))
    assertFalse(result.contains("\ndepend:"))
  }

  @Test
  fun `injectDepends omits both when both empty`() {
    val original = "name: PaperPlane\nmain: x\nversion: 1.0"
    val result = CompanionJarRewriter.injectDepends(original, emptyList(), emptyList())
    assertFalse(result.contains("depend"))
  }

  @Test
  fun `injectDepends is idempotent (replaces existing values)`() {
    val original =
        """
        name: PaperPlane
        main: x
        version: 1.0
        depend: [OldDep]
        softdepend: [OldSoft]
        """
            .trimIndent()
    val result =
        CompanionJarRewriter.injectDepends(
            original,
            listOf("NewDep"),
            listOf("NewSoft"),
        )
    assertTrue(result.contains("depend: [NewDep]"))
    assertTrue(result.contains("softdepend: [NewSoft]"))
    assertFalse(result.contains("OldDep"))
    assertFalse(result.contains("OldSoft"))
  }

  @Test
  fun `injectDepends preserves all unrelated lines`() {
    val original =
        """
        name: PaperPlane
        main: dev.paperplane.companion.CompanionPlugin
        version: ${'$'}{version}
        api-version: '1.18'
        description: Some description
        load: POSTWORLD
        """
            .trimIndent()
    val result =
        CompanionJarRewriter.injectDepends(original, listOf("X"), emptyList())
    assertTrue(result.contains("name: PaperPlane"))
    assertTrue(result.contains("main: dev.paperplane.companion.CompanionPlugin"))
    assertTrue(result.contains("api-version: '1.18'"))
    assertTrue(result.contains("description: Some description"))
    assertTrue(result.contains("load: POSTWORLD"))
  }

  // ── rewrite (full JAR mutation) ─────────────────────────────────────

  @Test
  fun `rewrite produces a JAR with mutated plugin yml and other entries intact`() {
    val sourceJar = makeFixtureJar()
    val output = File(tempDir, "out/paperplane-companion.jar")

    CompanionJarRewriter.rewrite(
        { sourceJar.inputStream() },
        output,
        listOf("WorldGuard"),
        listOf("PlaceholderAPI"),
    )

    assertTrue(output.exists())
    JarFile(output).use { jar ->
      // plugin.yml mutated.
      val ymlEntry = jar.getJarEntry("plugin.yml")
      assertNotNull(ymlEntry)
      val ymlText = jar.getInputStream(ymlEntry).bufferedReader().readText()
      assertTrue(ymlText.contains("depend: [WorldGuard]"))
      assertTrue(ymlText.contains("softdepend: [PlaceholderAPI]"))
      assertTrue(ymlText.contains("name: PaperPlane"))
      // Other entries preserved.
      val classEntry = jar.getJarEntry("dev/paperplane/companion/CompanionPlugin.class")
      assertNotNull(classEntry)
      assertEquals("hello".toByteArray().size, jar.getInputStream(classEntry).readBytes().size)
    }
  }

  @Test
  fun `rewrite is idempotent across calls`() {
    val sourceJar = makeFixtureJar()
    val output = File(tempDir, "out.jar")

    // First write: WorldGuard.
    CompanionJarRewriter.rewrite(
        { sourceJar.inputStream() },
        output,
        listOf("WorldGuard"),
        emptyList(),
    )
    val first = JarFile(output).use { it.getInputStream(it.getJarEntry("plugin.yml"))!!.bufferedReader().readText() }

    // Second write to the SAME output, using the OUTPUT as input → simulate re-running ppl dev.
    CompanionJarRewriter.rewrite(
        { output.inputStream() },
        output,
        listOf("WorldGuard"),
        emptyList(),
    )
    val second = JarFile(output).use { it.getInputStream(it.getJarEntry("plugin.yml"))!!.bufferedReader().readText() }

    assertEquals(first, second, "Re-running rewrite with same depends must produce identical plugin.yml")
  }

  @Test
  fun `rewrite with empty depends drops any prior depend lines`() {
    val sourceJar = makeFixtureJar(plugin_yml = "name: PaperPlane\nmain: x\nversion: 1.0\ndepend: [Stale]\n")
    val output = File(tempDir, "out.jar")
    CompanionJarRewriter.rewrite({ sourceJar.inputStream() }, output, emptyList(), emptyList())
    val text = JarFile(output).use { it.getInputStream(it.getJarEntry("plugin.yml"))!!.bufferedReader().readText() }
    assertFalse(text.contains("depend"))
    assertFalse(text.contains("Stale"))
  }

  // ── helpers ─────────────────────────────────────────────────────────

  private fun makeFixtureJar(
      plugin_yml: String =
          """
            name: PaperPlane
            main: dev.paperplane.companion.CompanionPlugin
            version: 1.0
            api-version: '1.18'
            load: POSTWORLD
            """
              .trimIndent(),
  ): File {
    val jar = File(tempDir, "fixture.jar")
    JarOutputStream(jar.outputStream()).use { jos ->
      jos.putNextEntry(JarEntry("plugin.yml"))
      jos.write(plugin_yml.toByteArray())
      jos.closeEntry()
      jos.putNextEntry(JarEntry("dev/paperplane/companion/CompanionPlugin.class"))
      jos.write("hello".toByteArray())
      jos.closeEntry()
    }
    return jar
  }
}
