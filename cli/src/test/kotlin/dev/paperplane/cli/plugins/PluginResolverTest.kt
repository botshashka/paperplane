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

/**
 * Resolver unit tests use a [FakeModrinthClient] and a [FakePluginCache] to avoid any network or
 * filesystem downloads while still exercising the real [PluginResolver] decision logic.
 */
class PluginResolverTest {

  @TempDir lateinit var tempDir: File

  private class FakeModrinthClient(
      val versionsBySlug: Map<String, List<ModrinthClient.ResolvedVersion>>,
  ) : ModrinthClient() {
    override fun listCompatibleVersions(
        slug: String,
        mcVersion: String,
    ): List<ModrinthClient.ResolvedVersion> = versionsBySlug[slug] ?: emptyList()

    override fun resolveLatest(slug: String, mcVersion: String) =
        listCompatibleVersions(slug, mcVersion).firstOrNull()

    override fun resolveExact(slug: String, version: String, mcVersion: String) =
        listCompatibleVersions(slug, mcVersion).firstOrNull { it.versionNumber == version }
  }

  private fun resolvedVersion(slug: String, version: String) =
      ModrinthClient.ResolvedVersion(
          slug = slug,
          versionNumber = version,
          downloadUrl = "https://example/$slug-$version.jar",
          sha512 = "sha-$slug-$version",
          filename = "$slug-$version.jar",
      )

  private fun cache() = PluginCache(File(tempDir, "cache"))

  @Test
  fun `new modrinth entry is resolved as latest and locked`() {
    val modrinth =
        FakeModrinthClient(
            mapOf("placeholderapi" to listOf(resolvedVersion("placeholderapi", "2.11.6")))
        )
    // Use a fake cache that shortcircuits get/download — we only exercise resolution here.
    val cacheStub = StubCache(File(tempDir, "cache"))
    val resolver = PluginResolver(modrinth, cacheStub)
    val deps = listOf(PluginDependency.modrinth("placeholderapi"))
    val result = resolver.sync(deps, PluginLockfile(), mcVersion = "1.21.4")
    val locked = result.lockfile.find("placeholderapi")!!
    assertEquals("2.11.6", locked.version)
    assertEquals(false, locked.pinned)
  }

  @Test
  fun `pinned modrinth entry is resolved via resolveExact`() {
    val modrinth =
        FakeModrinthClient(
            mapOf(
                "vault" to
                    listOf(
                        resolvedVersion("vault", "1.8.0"),
                        resolvedVersion("vault", "1.7.3"),
                    )
            )
        )
    val resolver = PluginResolver(modrinth, StubCache(File(tempDir, "cache")))
    val deps = listOf(PluginDependency.modrinth("vault", "1.7.3"))
    val result = resolver.sync(deps, PluginLockfile(), mcVersion = "1.21.4")
    val locked = result.lockfile.find("vault")!!
    assertEquals("1.7.3", locked.version)
    assertTrue(locked.pinned)
  }

  @Test
  fun `update respects pinned entries without force`() {
    val modrinth = FakeModrinthClient(mapOf("vault" to listOf(resolvedVersion("vault", "2.0.0"))))
    val resolver = PluginResolver(modrinth, StubCache(File(tempDir, "cache")))
    val pinnedLock =
        PluginLockfile()
            .upsert(
                LockedPlugin(
                    slug = "vault",
                    source = "modrinth",
                    version = "1.7.3",
                    sha512 = "old",
                    url = "u",
                    filename = "vault.jar",
                    pinned = true,
                )
            )
    val deps = listOf(PluginDependency.modrinth("vault", "1.7.3"))
    val updated = resolver.update(deps, pinnedLock, mcVersion = "1.21.4")
    assertEquals("1.7.3", updated.find("vault")!!.version)
  }

  @Test
  fun `update with force bumps pinned entry`() {
    val modrinth = FakeModrinthClient(mapOf("vault" to listOf(resolvedVersion("vault", "2.0.0"))))
    val resolver = PluginResolver(modrinth, StubCache(File(tempDir, "cache")))
    val pinnedLock =
        PluginLockfile()
            .upsert(
                LockedPlugin(
                    slug = "vault",
                    source = "modrinth",
                    version = "1.7.3",
                    sha512 = "old",
                    url = "u",
                    filename = "vault.jar",
                    pinned = true,
                )
            )
    val deps = listOf(PluginDependency.modrinth("vault")) // drop the pin for this call
    val updated =
        resolver.update(
            deps,
            pinnedLock,
            mcVersion = "1.21.4",
            targets = setOf("vault"),
            force = true,
        )
    assertEquals("2.0.0", updated.find("vault")!!.version)
  }

  @Test
  fun `sync drops orphans removed from config`() {
    val modrinth = FakeModrinthClient(emptyMap())
    val resolver = PluginResolver(modrinth, StubCache(File(tempDir, "cache")))
    val lock =
        PluginLockfile()
            .upsert(
                LockedPlugin(
                    slug = "gone",
                    source = "modrinth",
                    version = "1.0.0",
                    sha512 = "",
                    url = "",
                    filename = "gone.jar",
                )
            )
    val result = resolver.sync(emptyList(), lock, mcVersion = "1.21.4")
    assertNull(result.lockfile.find("gone"))
  }

  @Test
  fun `sync errors when modrinth entry cannot be resolved`() {
    val modrinth = FakeModrinthClient(mapOf("missing" to emptyList()))
    val resolver = PluginResolver(modrinth, StubCache(File(tempDir, "cache")))
    val deps = listOf(PluginDependency.modrinth("missing"))
    assertThrows(IllegalStateException::class.java) {
      resolver.sync(deps, PluginLockfile(), mcVersion = "1.21.4")
    }
  }

  @Test
  fun `sync errors when mc version missing for modrinth entry`() {
    val modrinth = FakeModrinthClient(emptyMap())
    val resolver = PluginResolver(modrinth, StubCache(File(tempDir, "cache")))
    val deps = listOf(PluginDependency.modrinth("placeholderapi"))
    assertThrows(IllegalStateException::class.java) {
      resolver.sync(deps, PluginLockfile(), mcVersion = null)
    }
  }

  @Test
  fun `local entry is resolved by hashing the file`() {
    val localJar = File(tempDir, "my.jar").apply { writeBytes("payload".toByteArray()) }
    val modrinth = FakeModrinthClient(emptyMap())
    val resolver = PluginResolver(modrinth, cache())
    val deps = listOf(PluginDependency.local(localJar.absolutePath))
    val result = resolver.sync(deps, PluginLockfile(), mcVersion = null)
    val locked = result.lockfile.find("my")!!
    assertEquals("local", locked.source)
    assertEquals(sha512("payload".toByteArray()), locked.sha512)
    assertNotNull(result.jars.first())
  }

  private fun sha512(bytes: ByteArray): String {
    val d = MessageDigest.getInstance("SHA-512").digest(bytes)
    return d.joinToString("") { "%02x".format(it) }
  }

  /**
   * A PluginCache that pretends every modrinth entry is already cached by returning a zero-byte
   * placeholder file from get(). Bypasses HTTP so resolver tests don't need a real server. Local
   * entries still go through the real cache logic (deterministic from disk).
   */
  private class StubCache(private val dir: File) : PluginCache(dir) {
    override fun get(locked: LockedPlugin): File? {
      if (locked.source == PluginDependency.Source.LOCAL.key) return super.get(locked)
      dir.mkdirs()
      val file = File(dir, locked.filename)
      if (!file.exists()) file.writeBytes(ByteArray(0))
      return file
    }

    override fun download(locked: LockedPlugin, force: Boolean): File = get(locked)!!
  }
}
