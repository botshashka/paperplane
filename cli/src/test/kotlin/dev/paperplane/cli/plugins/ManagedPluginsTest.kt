package dev.paperplane.cli.plugins

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ManagedPluginsTest {

  @TempDir lateinit var tempDir: File

  private fun serverDir() = File(tempDir, "server").apply { mkdirs() }

  // ── load / save ──────────────────────────────────────────────────────

  @Test
  fun `load returns empty set when manifest missing`() {
    assertEquals(emptySet<String>(), ManagedPlugins.load(serverDir()))
  }

  @Test
  fun `save and load round trip`() {
    val dir = serverDir()
    val filenames = setOf("PlaceholderAPI-2.11.6.jar", "Vault-1.7.3.jar")
    ManagedPlugins.save(dir, filenames)
    assertEquals(filenames, ManagedPlugins.load(dir))
  }

  @Test
  fun `save creates parent dirs if needed`() {
    val dir = File(tempDir, "fresh/server")
    ManagedPlugins.save(dir, setOf("a.jar"))
    assertEquals(setOf("a.jar"), ManagedPlugins.load(dir))
  }

  @Test
  fun `save overwrites existing manifest`() {
    val dir = serverDir()
    ManagedPlugins.save(dir, setOf("old.jar"))
    ManagedPlugins.save(dir, setOf("new.jar"))
    assertEquals(setOf("new.jar"), ManagedPlugins.load(dir))
  }

  @Test
  fun `load handles malformed json gracefully`() {
    val dir = serverDir()
    File(dir, ".paperplane").mkdirs()
    File(dir, ".paperplane/managed-plugins.json").writeText("not json")
    assertEquals(emptySet<String>(), ManagedPlugins.load(dir))
  }

  // ── prune ────────────────────────────────────────────────────────────

  @Test
  fun `prune deletes orphans from plugins dir and updates manifest`() {
    val dir = serverDir()
    val pluginsDir = File(dir, "plugins").apply { mkdirs() }

    // Initial state: two managed jars.
    File(pluginsDir, "A.jar").writeText("a")
    File(pluginsDir, "B.jar").writeText("b")
    ManagedPlugins.save(dir, setOf("A.jar", "B.jar"))

    // Remove B from the lockfile — prune should delete B.jar.
    ManagedPlugins.prune(dir, setOf("A.jar"))

    assertTrue(File(pluginsDir, "A.jar").exists())
    assertFalse(File(pluginsDir, "B.jar").exists())
    assertEquals(setOf("A.jar"), ManagedPlugins.load(dir))
  }

  @Test
  fun `prune never touches files not in old manifest`() {
    val dir = serverDir()
    val pluginsDir = File(dir, "plugins").apply { mkdirs() }

    // These are NOT managed — the dev plugin jar, companion, and plugin data.
    File(pluginsDir, "my-plugin.jar").writeText("dev")
    File(pluginsDir, "paperplane-companion.jar").writeText("companion")
    File(pluginsDir, "PlaceholderAPI").mkdirs()
    File(pluginsDir, "PlaceholderAPI/config.yml").writeText("cfg")

    // Old manifest is empty (no managed plugins before). Prune to empty.
    ManagedPlugins.prune(dir, emptySet())

    assertTrue(File(pluginsDir, "my-plugin.jar").exists(), "dev plugin jar must survive")
    assertTrue(File(pluginsDir, "paperplane-companion.jar").exists(), "companion must survive")
    assertTrue(File(pluginsDir, "PlaceholderAPI/config.yml").exists(), "data dir must survive")
  }

  @Test
  fun `prune handles missing plugins dir gracefully`() {
    val dir = serverDir()
    // No plugins/ dir exists at all. Should not throw.
    ManagedPlugins.prune(dir, setOf("x.jar"))
    assertEquals(setOf("x.jar"), ManagedPlugins.load(dir))
  }

  @Test
  fun `prune from populated to empty removes all managed jars`() {
    val dir = serverDir()
    val pluginsDir = File(dir, "plugins").apply { mkdirs() }

    File(pluginsDir, "X.jar").writeText("x")
    File(pluginsDir, "Y.jar").writeText("y")
    File(pluginsDir, "unmanaged.jar").writeText("u")
    ManagedPlugins.save(dir, setOf("X.jar", "Y.jar"))

    ManagedPlugins.prune(dir, emptySet())

    assertFalse(File(pluginsDir, "X.jar").exists())
    assertFalse(File(pluginsDir, "Y.jar").exists())
    assertTrue(File(pluginsDir, "unmanaged.jar").exists(), "not in manifest — never touched")
    assertEquals(emptySet<String>(), ManagedPlugins.load(dir))
  }

  // ── copyJars ─────────────────────────────────────────────────────────

  @Test
  fun `copyJars copies files atomically into target dir`() {
    val src = File(tempDir, "src").apply { mkdirs() }
    val a = File(src, "a.jar").apply { writeBytes("alpha".toByteArray()) }
    val b = File(src, "b.jar").apply { writeBytes("beta".toByteArray()) }
    val target = File(tempDir, "target")

    ManagedPlugins.copyJars(listOf(a, b), target)

    assertEquals("alpha", File(target, "a.jar").readText())
    assertEquals("beta", File(target, "b.jar").readText())
  }

  @Test
  fun `copyJars skips unchanged files`() {
    val src = File(tempDir, "src").apply { mkdirs() }
    val a = File(src, "a.jar").apply { writeBytes("hello".toByteArray()) }
    val target = File(tempDir, "target").apply { mkdirs() }

    ManagedPlugins.copyJars(listOf(a), target)
    val firstMtime = File(target, "a.jar").lastModified()
    a.setLastModified(firstMtime) // match mtime so skip triggers

    ManagedPlugins.copyJars(listOf(a), target)
    assertEquals("hello", File(target, "a.jar").readText())
  }

  @Test
  fun `copyJars overwrites when size differs`() {
    val src = File(tempDir, "src").apply { mkdirs() }
    val a = File(src, "a.jar").apply { writeBytes("v1".toByteArray()) }
    val target = File(tempDir, "target").apply { mkdirs() }

    ManagedPlugins.copyJars(listOf(a), target)
    a.writeBytes("v2-longer".toByteArray())
    ManagedPlugins.copyJars(listOf(a), target)
    assertEquals("v2-longer", File(target, "a.jar").readText())
  }

  @Test
  fun `copyJars handles empty list`() {
    val target = File(tempDir, "target")
    ManagedPlugins.copyJars(emptyList(), target)
    assertTrue(target.exists())
  }
}
