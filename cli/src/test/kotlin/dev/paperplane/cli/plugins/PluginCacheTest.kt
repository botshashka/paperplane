package dev.paperplane.cli.plugins

import java.io.File
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PluginCacheTest {

  @TempDir lateinit var tempDir: File

  private fun sha512(bytes: ByteArray): String {
    val d = MessageDigest.getInstance("SHA-512").digest(bytes)
    return d.joinToString("") { "%02x".format(it) }
  }

  @Test
  fun `pathFor modrinth keys by source slug version`() {
    val cache = PluginCache(tempDir)
    val locked =
        LockedPlugin(
            slug = "vault",
            source = "modrinth",
            version = "1.7.3",
            sha512 = "x",
            url = "https://example/vault.jar",
            filename = "Vault-1.7.3.jar",
        )
    val path = cache.pathFor(locked)
    assertEquals("modrinth-vault-1.7.3.jar", path.name)
    assertEquals(tempDir.absolutePath, path.parentFile.absolutePath)
  }

  @Test
  fun `local cache returns the source path unchanged`() {
    val cache = PluginCache(tempDir)
    val src = File(tempDir, "my.jar").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    val locked =
        LockedPlugin(
            slug = "my",
            source = "local",
            version = "0",
            sha512 = sha512(src.readBytes()),
            url = src.absolutePath,
            filename = "my.jar",
        )
    val path = cache.pathFor(locked)
    assertEquals(src.absolutePath, path.absolutePath)
    assertNotNull(cache.get(locked))
  }

  @Test
  fun `local download validates sha and fails on mismatch`() {
    val cache = PluginCache(tempDir)
    val src = File(tempDir, "my.jar").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    val locked =
        LockedPlugin(
            slug = "my",
            source = "local",
            version = "0",
            sha512 = "deadbeef", // wrong
            url = src.absolutePath,
            filename = "my.jar",
        )
    assertThrows(java.io.IOException::class.java) { cache.download(locked) }
  }

  @Test
  fun `get returns null on sha mismatch`() {
    val cache = PluginCache(tempDir)
    val target = File(tempDir, "modrinth-x-1.0.jar").apply { writeBytes(byteArrayOf(1)) }
    val locked =
        LockedPlugin(
            slug = "x",
            source = "modrinth",
            version = "1.0",
            sha512 = "nomatch",
            url = "https://example/x.jar",
            filename = "x.jar",
        )
    assertNull(cache.get(locked))
    assertTrue(target.exists()) // get() doesn't mutate
  }

  @Test
  fun `get returns file when sha matches`() {
    val cache = PluginCache(tempDir)
    val bytes = "hello".toByteArray()
    File(tempDir, "modrinth-x-1.0.jar").writeBytes(bytes)
    val locked =
        LockedPlugin(
            slug = "x",
            source = "modrinth",
            version = "1.0",
            sha512 = sha512(bytes),
            url = "https://example/x.jar",
            filename = "x.jar",
        )
    assertNotNull(cache.get(locked))
  }
}
