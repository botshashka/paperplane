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

  @Test
  fun `downloadRemote wraps network IOException with slug version url context`() {
    // Without this wrap, handleResolveErrors falls back to "I/O error" for any IOException whose
    // message is null (e.g. proxy CONNECT failures from java.net.http.HttpClient). The wrapped
    // message names the slug, version, URL, and includes the underlying cause's class name.
    val cache = PluginCache(tempDir)
    val locked =
        LockedPlugin(
            slug = "ghost",
            source = "modrinth",
            version = "1.0.0",
            sha512 = "x".repeat(128),
            // 192.0.2.0/24 is reserved for documentation (RFC 5737) and never routed —
            // connections will fail fast.
            url = "http://192.0.2.1:1/ghost.jar",
            filename = "ghost.jar",
        )
    val ex = assertThrows(java.io.IOException::class.java) { cache.download(locked) }
    val msg = ex.message ?: ""
    assertTrue(msg.contains("ghost"), "message should name the slug: $msg")
    assertTrue(msg.contains("1.0.0"), "message should include the version: $msg")
    assertTrue(msg.contains("192.0.2.1"), "message should include the URL: $msg")
    assertTrue(
        msg.contains("Check your network connection") || msg.contains("`ppl plugin update ghost`"),
        "message should suggest a recovery action: $msg",
    )
  }
}
