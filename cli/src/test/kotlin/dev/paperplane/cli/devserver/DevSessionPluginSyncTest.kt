package dev.paperplane.cli.devserver

import dev.paperplane.cli.config.PaperPlaneConfig
import dev.paperplane.cli.config.ServerConfig
import dev.paperplane.cli.plugins.LockedPlugin
import dev.paperplane.cli.plugins.ManagedPlugins
import dev.paperplane.cli.plugins.ModrinthClient
import dev.paperplane.cli.plugins.PluginCache
import dev.paperplane.cli.plugins.PluginDependency
import dev.paperplane.cli.plugins.PluginLockfile
import dev.paperplane.cli.plugins.PluginResolver
import dev.paperplane.cli.server.PaperDownloader
import dev.paperplane.cli.server.PaperServerManager
import dev.paperplane.cli.testing.FakePaperServerManager
import dev.paperplane.cli.ui.RecordingTerminal
import dev.paperplane.cli.ui.TerminalUI
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Integration tests for [DevSession.syncDependencyPlugins] — the wiring point that sits between the
 * resolver, the lockfile on disk, and [PaperServerManager.copyDependencyPlugins]. Uses a stub
 * [PluginCache] that returns synthetic files (no network, no real download), and a
 * [FakePaperServerManager] that records every method call so we can assert on the call order and
 * pruning behavior.
 */
class DevSessionPluginSyncTest {

  @TempDir lateinit var tempDir: File

  private fun newSession(
      config: PaperPlaneConfig,
      resolver: PluginResolver,
  ): Triple<DevSession, FakePaperServerManager, RecordingTerminal> {
    val terminal = RecordingTerminal()
    val ui = TerminalUI(terminal)
    val ppDir = File(tempDir, ".paperplane").apply { mkdirs() }
    val downloader = PaperDownloader(File(ppDir, "cache"))
    val gradle = dev.paperplane.cli.testing.FakeGradleBridge(tempDir, ui)
    val session =
        DevSession(
            config = config,
            ppDir = ppDir,
            gradle = gradle,
            downloader = downloader,
            projectDir = tempDir,
            ui = ui,
            pluginResolverFactory = { resolver },
        )
    val serverDir = File(ppDir, "server").apply { mkdirs() }
    val manager = FakePaperServerManager(serverDir, downloader, ui)
    return Triple(session, manager, terminal)
  }

  /** Stub cache: every modrinth entry resolves to a tiny synthetic file in the cache dir. */
  private class StubCache(dir: File) : PluginCache(dir) {
    val downloadedSlugs = mutableListOf<String>()

    override fun get(locked: LockedPlugin): File? {
      if (locked.source == PluginDependency.Source.LOCAL.key) return super.get(locked)
      val file = File(pathFor(locked).parentFile.also { it.mkdirs() }, locked.filename)
      if (!file.exists()) file.writeBytes(byteArrayOf(0))
      return file
    }

    override fun download(locked: LockedPlugin, force: Boolean): File {
      downloadedSlugs += locked.slug
      return get(locked)!!
    }
  }

  private fun fakeModrinth(versions: Map<String, ModrinthClient.ResolvedVersion>) =
      object : ModrinthClient() {
        override fun resolveLatest(slug: String, mcVersion: String) = versions[slug]

        override fun resolveExact(slug: String, version: String, mcVersion: String) =
            versions[slug]?.takeIf { it.versionNumber == version }

        override fun listCompatibleVersions(slug: String, mcVersion: String) =
            listOfNotNull(versions[slug])
      }

  private fun resolved(slug: String, version: String) =
      ModrinthClient.ResolvedVersion(
          slug = slug,
          versionNumber = version,
          downloadUrl = "https://example/$slug-$version.jar",
          sha512 = "deadbeef-$slug-$version",
          filename = "$slug-$version.jar",
      )

  private fun callSync(session: DevSession, manager: PaperServerManager) {
    // Use reflection-free indirection: the method is internal so this test (same module) can
    // call it directly.
    session.syncDependencyPlugins(manager)
  }

  @Test
  fun `empty plugin list deletes lockfile and prunes server plugins dir`() {
    val (session, manager, _) =
        newSession(
            config =
                PaperPlaneConfig(server = ServerConfig(version = "1.21.4", plugins = emptyList())),
            resolver = PluginResolver(ModrinthClient(), StubCache(File(tempDir, "cache"))),
        )
    // Pre-existing lockfile that should be removed.
    PluginLockfile.save(
        tempDir,
        PluginLockfile()
            .upsert(
                LockedPlugin(
                    slug = "ghost",
                    source = "modrinth",
                    version = "1.0.0",
                    sha512 = "x",
                    url = "u",
                    filename = "ghost.jar",
                )
            ),
    )

    callSync(session, manager)

    assertFalse(File(tempDir, "paperplane-lock.yml").exists(), "lockfile should be deleted")
    // Manifest prune ran — written with empty set since no plugins remain.
    assertEquals(emptySet<String>(), ManagedPlugins.load(manager.serverDir))
  }

  @Test
  fun `new plugin resolves writes lockfile copies and prunes`() {
    val cache = StubCache(File(tempDir, "cache"))
    val resolver =
        PluginResolver(
            fakeModrinth(mapOf("placeholderapi" to resolved("placeholderapi", "2.11.6"))),
            cache,
        )
    val (session, manager, terminal) =
        newSession(
            config =
                PaperPlaneConfig(
                    server =
                        ServerConfig(
                            version = "1.21.4",
                            plugins = listOf(PluginDependency.modrinth("placeholderapi")),
                        )
                ),
            resolver = resolver,
        )

    callSync(session, manager)

    val lock = PluginLockfile.load(tempDir)
    assertEquals(1, lock.plugins.size)
    assertEquals("2.11.6", lock.find("placeholderapi")!!.version)
    // Jar deployed and manifest updated with the installed filename.
    val deployed = File(manager.serverDir, "plugins").listFiles()?.map { it.name } ?: emptyList()
    assertTrue(
        deployed.any { it.contains("placeholderapi") },
        "expected placeholderapi jar in plugins/",
    )
    assertTrue(ManagedPlugins.load(manager.serverDir).isNotEmpty())
    assertTrue(
        terminal.writes.any { it.contains("Resolving plugins") },
        "should show spinner when lockfile is empty",
    )
    assertTrue(
        terminal.writes.any { it.contains("placeholderapi 2.11.6") },
        "should print plugins summary",
    )
  }

  @Test
  fun `cached lockfile produces no spinner`() {
    val cache = StubCache(File(tempDir, "cache"))
    val resolver = PluginResolver(fakeModrinth(emptyMap()), cache)
    // Pre-populate lockfile so syncDependencyPlugins sees nothing to resolve.
    PluginLockfile.save(
        tempDir,
        PluginLockfile()
            .upsert(
                LockedPlugin(
                    slug = "vault",
                    source = "modrinth",
                    version = "1.7.3",
                    sha512 = "x",
                    url = "https://example/vault.jar",
                    filename = "vault.jar",
                )
            ),
    )
    val (session, manager, terminal) =
        newSession(
            config =
                PaperPlaneConfig(
                    server =
                        ServerConfig(
                            version = "1.21.4",
                            plugins = listOf(PluginDependency.modrinth("vault")),
                        )
                ),
            resolver = resolver,
        )

    callSync(session, manager)

    assertFalse(
        terminal.writes.any { it.contains("Resolving plugins") },
        "no spinner when nothing needs resolving",
    )
    assertTrue(terminal.writes.any { it.contains("vault 1.7.3") })
  }

  @Test
  fun `local plugin entry is hashed and copied`() {
    val localJar = File(tempDir, "local-plug.jar").apply { writeBytes("payload".toByteArray()) }
    val cache = StubCache(File(tempDir, "cache"))
    val resolver = PluginResolver(fakeModrinth(emptyMap()), cache)
    val (session, manager, _) =
        newSession(
            config =
                PaperPlaneConfig(
                    server =
                        ServerConfig(
                            version = "1.21.4",
                            plugins = listOf(PluginDependency.local(localJar.absolutePath)),
                        )
                ),
            resolver = resolver,
        )

    callSync(session, manager)

    val lock = PluginLockfile.load(tempDir)
    assertEquals("local", lock.find("local-plug")!!.source)
    val deployed = File(manager.serverDir, "plugins").listFiles()?.map { it.name } ?: emptyList()
    assertTrue(deployed.any { it.contains("local-plug") }, "expected local-plug jar in plugins/")
  }

  @Test
  fun `removed plugin is dropped from lockfile and pruned`() {
    val cache = StubCache(File(tempDir, "cache"))
    val resolver = PluginResolver(fakeModrinth(emptyMap()), cache)
    PluginLockfile.save(
        tempDir,
        PluginLockfile()
            .upsert(
                LockedPlugin(
                    slug = "vault",
                    source = "modrinth",
                    version = "1.7.3",
                    sha512 = "x",
                    url = "https://example/vault.jar",
                    filename = "vault.jar",
                )
            ),
    )
    // Config has NO plugins entries — vault was removed.
    val (session, manager, _) =
        newSession(
            config =
                PaperPlaneConfig(server = ServerConfig(version = "1.21.4", plugins = emptyList())),
            resolver = resolver,
        )

    callSync(session, manager)

    assertFalse(File(tempDir, "paperplane-lock.yml").exists())
    assertEquals(emptySet<String>(), ManagedPlugins.load(manager.serverDir))
  }
}
