package dev.paperplane.cli.plugins

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PluginLockfileTest {

  @TempDir lateinit var tempDir: File

  private fun locked(slug: String, version: String = "1.0.0") =
      LockedPlugin(
          slug = slug,
          source = "modrinth",
          version = version,
          sha512 = "abc",
          url = "https://example/$slug.jar",
          filename = "$slug.jar",
      )

  @Test
  fun `missing lockfile loads as empty`() {
    val lock = PluginLockfile.load(tempDir)
    assertEquals(0, lock.plugins.size)
  }

  @Test
  fun `upsert adds new entry and sorts alphabetically`() {
    val lock =
        PluginLockfile()
            .upsert(locked("vault"))
            .upsert(locked("placeholderapi"))
            .upsert(locked("armor-stand"))
    assertEquals(listOf("armor-stand", "placeholderapi", "vault"), lock.plugins.map { it.slug })
  }

  @Test
  fun `upsert replaces existing entry without duplicating`() {
    val lock = PluginLockfile().upsert(locked("vault", "1.0.0")).upsert(locked("vault", "2.0.0"))
    assertEquals(1, lock.plugins.size)
    assertEquals("2.0.0", lock.plugins[0].version)
  }

  @Test
  fun `remove drops entry`() {
    val lock = PluginLockfile().upsert(locked("vault")).upsert(locked("papi"))
    val after = lock.remove("vault")
    assertEquals(1, after.plugins.size)
    assertNull(after.find("vault"))
    assertNotNull(after.find("papi"))
  }

  @Test
  fun `save and load round trip`() {
    val original =
        PluginLockfile()
            .upsert(locked("vault", "1.7.3").copy(pinned = true))
            .upsert(locked("placeholderapi", "2.11.6"))
    PluginLockfile.save(tempDir, original)
    val reloaded = PluginLockfile.load(tempDir)
    assertEquals(original, reloaded)
    // Header comment preserved — load strips it before parsing.
    val raw = File(tempDir, "paperplane-lock.yml").readText()
    assertTrue(raw.startsWith("# Auto-generated"), "expected header comment")
  }

  @Test
  fun `find upsert and remove are case insensitive`() {
    // Hand-edited lockfile case: stored slug is mixed-case "WorldEdit". All lookup paths must
    // still match "worldedit", "WORLDEDIT", etc.
    val lock = PluginLockfile().upsert(locked("WorldEdit"))
    assertNotNull(lock.find("worldedit"))
    assertNotNull(lock.find("WORLDEDIT"))
    assertNotNull(lock.find("WorldEdit"))

    // upsert with different casing replaces in place.
    val replaced = lock.upsert(locked("worldedit", "2.0.0"))
    assertEquals(1, replaced.plugins.size)
    assertEquals("2.0.0", replaced.plugins[0].version)

    // remove with different casing drops the entry.
    val removed = lock.remove("WORLDEDIT")
    assertEquals(0, removed.plugins.size)
  }

  @Test
  fun `delete is idempotent`() {
    PluginLockfile.delete(tempDir)
    PluginLockfile.delete(tempDir) // no throw
  }
}
