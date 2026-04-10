package dev.paperplane.cli.commands

import com.github.ajalt.clikt.core.parse
import dev.paperplane.cli.plugins.LockedPlugin
import dev.paperplane.cli.plugins.ManagedPlugins
import dev.paperplane.cli.plugins.ModrinthClient
import dev.paperplane.cli.plugins.PluginCache
import dev.paperplane.cli.plugins.PluginDependency
import dev.paperplane.cli.plugins.PluginLockfile
import dev.paperplane.cli.plugins.PluginResolver
import dev.paperplane.cli.testing.RenderTestBase
import dev.paperplane.cli.ui.TerminalUI
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * End-to-end coverage of `ppl plugin {add,remove,update,install,list}`. Each test sets up a project
 * directory under [canonicalTempDir], drops a `paperplane.yml` if needed, runs the command via
 * `cmd.parse(args)`, and asserts on the resulting config/lockfile + recorded terminal output.
 *
 * The resolver dependency is overridden to a stub that returns synthetic Modrinth versions and
 * "downloads" zero-byte files into the cache, so no network is touched and tests are fast and
 * deterministic.
 */
class PluginCommandTest : RenderTestBase() {

  // ── Stub resolver wiring ──────────────────────────────────────────────

  private class StubCache(dir: File) : PluginCache(dir) {
    override fun get(locked: LockedPlugin): File? {
      if (locked.source == PluginDependency.Source.LOCAL.key) return super.get(locked)
      val file = File(pathFor(locked).parentFile.also { it.mkdirs() }, locked.filename)
      if (!file.exists()) file.writeBytes(byteArrayOf(0))
      return file
    }

    override fun download(locked: LockedPlugin, force: Boolean): File = get(locked)!!
  }

  /**
   * Minimal scriptable Modrinth client. [versions] maps slug → version. [idAliases] maps an "ID"
   * string back to its canonical slug, used by [getProject] so tests can verify `ppl plugin add`
   * rewrites IDs to slugs before saving config.
   */
  private class StubModrinth(
      private val versions: Map<String, String>,
      private val idAliases: Map<String, String> = emptyMap(),
  ) : ModrinthClient() {
    private fun resolved(slug: String, ver: String) =
        ResolvedVersion(
            slug,
            ver,
            "https://example/$slug-$ver.jar",
            "sha-$slug-$ver",
            "$slug-$ver.jar",
        )

    override fun resolveLatest(slug: String, mcVersion: String) =
        versions[slug]?.let { resolved(slug, it) }

    override fun resolveExact(slug: String, version: String, mcVersion: String) =
        if (versions[slug] == version) resolved(slug, version) else null

    override fun listCompatibleVersions(slug: String, mcVersion: String) =
        versions[slug]?.let { listOf(resolved(slug, it)) } ?: emptyList()

    override fun getProject(idOrSlug: String): ProjectInfo {
      // Lenient: only throw NotFound if the input is explicitly absent from BOTH maps. This
      // lets tests that don't care about ID resolution (the vast majority) pass slugs through
      // without having to script a separate ProjectInfo entry. The dedicated ID-rewrite test
      // uses the idAliases map to verify canonicalization.
      val canonical = idAliases[idOrSlug] ?: idOrSlug
      return ProjectInfo(
          slug = canonical,
          title = canonical,
          id = idAliases.entries.firstOrNull { it.value == canonical }?.key ?: canonical,
      )
    }
  }

  private fun stubContextFactory(stub: ModrinthClient): (TerminalUI) -> PluginCommandContext =
      { ui ->
        object : PluginCommandContext(ui, canonicalTempDir) {
          override val modrinth: ModrinthClient = stub
          override val cache: PluginCache =
              StubCache(File(canonicalTempDir, ".paperplane/cache/plugins"))
          override val resolver: PluginResolver = PluginResolver(stub, cache)
        }
      }

  /**
   * Writes a `paperplane.yml` with `server.version` set, optionally with extra YAML lines appended
   * *under* the `server:` block. [extraServerLines] is plain text appended directly after the
   * version line — caller is responsible for two-space indentation.
   */
  private fun writeBaseConfig(extraServerLines: String = "") {
    val text = buildString {
      appendLine("server:")
      appendLine("  version: \"1.21.4\"")
      if (extraServerLines.isNotEmpty()) {
        for (line in extraServerLines.lines()) appendLine(line)
      }
    }
    File(canonicalTempDir, "paperplane.yml").writeText(text)
  }

  // ── parseSpec ─────────────────────────────────────────────────────────

  @Test
  fun `parseSpec defaults to modrinth source`() {
    val dep = parseSpec("placeholderapi")
    assertEquals(PluginDependency.Source.MODRINTH, dep.source)
    assertEquals("placeholderapi", dep.slug)
    assertNull(dep.version)
  }

  @Test
  fun `parseSpec extracts version after at sign`() {
    val dep = parseSpec("vault@1.7.3")
    assertEquals("vault", dep.slug)
    assertEquals("1.7.3", dep.version)
  }

  @Test
  fun `parseSpec respects modrinth prefix`() {
    val dep = parseSpec("modrinth:vault@1.7.3")
    assertEquals("vault", dep.slug)
    assertEquals("1.7.3", dep.version)
    assertEquals(PluginDependency.Source.MODRINTH, dep.source)
  }

  @Test
  fun `parseSpec handles local prefix`() {
    val dep = parseSpec("local:./libs/foo.jar")
    assertEquals(PluginDependency.Source.LOCAL, dep.source)
    assertEquals("foo", dep.slug)
  }

  @Test
  fun `parseSpec accepts a Modrinth plugin URL`() {
    val dep = parseSpec("https://modrinth.com/plugin/worldedit")
    assertEquals(PluginDependency.Source.MODRINTH, dep.source)
    assertEquals("worldedit", dep.modrinth)
    assertNull(dep.version)
  }

  @Test
  fun `parseSpec accepts a Modrinth URL with project ID`() {
    val dep = parseSpec("https://modrinth.com/project/1u6JkXh5")
    assertEquals(PluginDependency.Source.MODRINTH, dep.source)
    assertEquals("1u6JkXh5", dep.modrinth)
  }

  @Test
  fun `parseSpec accepts a Modrinth URL with pinned version`() {
    val dep = parseSpec("https://modrinth.com/plugin/worldedit/version/7.3.0")
    assertEquals("worldedit", dep.modrinth)
    assertEquals("7.3.0", dep.version)
  }

  @Test
  fun `parseSpec accepts a Modrinth URL with www subdomain`() {
    val dep = parseSpec("https://www.modrinth.com/plugin/placeholderapi")
    assertEquals("placeholderapi", dep.modrinth)
  }

  @Test
  fun `parseSpec accepts mod and datapack categories`() {
    assertEquals("x", parseSpec("https://modrinth.com/mod/x").modrinth)
    assertEquals("y", parseSpec("https://modrinth.com/datapack/y").modrinth)
  }

  @Test
  fun `parseSpec rejects non-Modrinth URLs with a helpful error`() {
    val e =
        assertThrows(IllegalArgumentException::class.java) {
          parseSpec("https://hangar.papermc.io/plugin/EssentialsX")
        }
    assertTrue(e.message!!.contains("Only Modrinth URLs are supported"))
  }

  @Test
  fun `parseSpec rejects Modrinth URLs that aren't project pages`() {
    assertThrows(IllegalArgumentException::class.java) {
      parseSpec("https://modrinth.com/dashboard")
    }
  }

  @Test
  fun `parseSpec rejects malformed URLs`() {
    assertThrows(IllegalArgumentException::class.java) { parseSpec("https://modrinth.com/") }
  }

  // ── add ───────────────────────────────────────────────────────────────

  @Test
  fun `add resolves new modrinth plugin and writes config plus lockfile`() {
    writeBaseConfig()
    val (ui, t) = newUi()
    val cmd =
        AddPluginCommand(ui, stubContextFactory(StubModrinth(mapOf("placeholderapi" to "2.11.6"))))
    cmd.parse(listOf("placeholderapi"))

    val configText = File(canonicalTempDir, "paperplane.yml").readText()
    assertTrue(configText.contains("placeholderapi"), configText)
    val lock = PluginLockfile.load(canonicalTempDir)
    assertEquals("2.11.6", lock.find("placeholderapi")!!.version)
    assertTrue(t.writes.any { it.contains("Added placeholderapi 2.11.6") })
  }

  @Test
  fun `add accepts a Modrinth project ID and stores the canonical slug in config`() {
    writeBaseConfig()
    val (ui, t) = newUi()
    val cmd =
        AddPluginCommand(
            ui,
            stubContextFactory(
                StubModrinth(
                    versions = mapOf("worldedit" to "7.3.0"),
                    idAliases = mapOf("1u6JkXh5" to "worldedit"),
                )
            ),
        )
    cmd.parse(listOf("1u6JkXh5"))

    val configText = File(canonicalTempDir, "paperplane.yml").readText()
    // Config stores the slug, not the opaque ID.
    assertTrue(configText.contains("worldedit"), "expected slug in config: $configText")
    assertFalse(configText.contains("1u6JkXh5"), "config should not contain raw ID")
    val locked = PluginLockfile.load(canonicalTempDir).find("worldedit")
    assertNotNull(locked)
    assertEquals("7.3.0", locked!!.version)
  }

  @Test
  fun `add with pinned version writes pinned lockfile entry`() {
    writeBaseConfig()
    val (ui, _) = newUi()
    val cmd = AddPluginCommand(ui, stubContextFactory(StubModrinth(mapOf("vault" to "1.7.3"))))
    cmd.parse(listOf("vault@1.7.3"))
    val locked = PluginLockfile.load(canonicalTempDir).find("vault")!!
    assertEquals("1.7.3", locked.version)
    assertTrue(locked.pinned)
  }

  @Test
  fun `add rejects duplicate slug`() {
    writeBaseConfig(
        extraServerLines =
            """
            |  plugins:
            |    - modrinth: "vault"
            """
                .trimMargin()
    )
    val (ui, t) = newUi()
    val cmd = AddPluginCommand(ui, stubContextFactory(StubModrinth(mapOf("vault" to "2.0.0"))))
    assertThrows(com.github.ajalt.clikt.core.ProgramResult::class.java) {
      cmd.parse(listOf("vault"))
    }
    assertTrue(t.writes.any { it.contains("already in paperplane.yml") })
  }

  @Test
  fun `add fails with helpful error when server version missing`() {
    File(canonicalTempDir, "paperplane.yml").writeText("server: {}\n")
    val (ui, t) = newUi()
    val cmd = AddPluginCommand(ui, stubContextFactory(StubModrinth(emptyMap())))
    assertThrows(com.github.ajalt.clikt.core.ProgramResult::class.java) {
      cmd.parse(listOf("vault"))
    }
    assertTrue(t.writes.any { it.contains("server.version is not set") })
  }

  @Test
  fun `add local plugin does not require server version`() {
    File(canonicalTempDir, "paperplane.yml").writeText("server: {}\n")
    val localJar =
        File(canonicalTempDir, "myplug.jar").apply { writeBytes("payload".toByteArray()) }
    val (ui, t) = newUi()
    val cmd = AddPluginCommand(ui, stubContextFactory(StubModrinth(emptyMap())))
    cmd.parse(listOf("local:${localJar.absolutePath}"))
    assertNotNull(PluginLockfile.load(canonicalTempDir).find("myplug"))
    assertTrue(t.writes.any { it.contains("Added myplug") })
  }

  // ── remove ────────────────────────────────────────────────────────────

  @Test
  fun `remove drops entry from config and lockfile`() {
    writeBaseConfig()
    val (ui, _) = newUi()
    AddPluginCommand(ui, stubContextFactory(StubModrinth(mapOf("vault" to "1.7.3"))))
        .parse(listOf("vault"))
    assertNotNull(PluginLockfile.load(canonicalTempDir).find("vault"))

    val (ui2, t2) = newUi()
    RemovePluginCommand(ui2, stubContextFactory(StubModrinth(emptyMap()))).parse(listOf("vault"))

    val configText = File(canonicalTempDir, "paperplane.yml").readText()
    assertFalse(configText.contains("vault"), "config should not mention vault: $configText")
    // With no remaining entries, the lockfile should be deleted entirely.
    assertFalse(File(canonicalTempDir, "paperplane-lock.yml").exists())
    assertTrue(t2.writes.any { it.contains("Removed vault") })
  }

  @Test
  fun `add and remove are case insensitive across slug casings`() {
    writeBaseConfig()
    val (ui, _) = newUi()
    // Add with one casing.
    AddPluginCommand(
            ui,
            stubContextFactory(
                StubModrinth(
                    versions = mapOf("worldedit" to "7.3.0"),
                    // User typed "WorldEdit"; Modrinth /project returns canonical lowercase.
                    idAliases = mapOf("WorldEdit" to "worldedit"),
                )
            ),
        )
        .parse(listOf("WorldEdit"))

    val configText = File(canonicalTempDir, "paperplane.yml").readText()
    assertTrue(configText.contains("worldedit"), "stored slug must be lowercase: $configText")
    assertFalse(configText.contains("WorldEdit"), "original casing must not leak into config")

    // Remove with a THIRD casing — all-caps.
    val (ui2, t2) = newUi()
    RemovePluginCommand(ui2, stubContextFactory(StubModrinth(emptyMap())))
        .parse(listOf("WORLDEDIT"))
    assertTrue(
        t2.writes.any { it.contains("Removed WORLDEDIT") || it.contains("Removed worldedit") }
    )
    assertFalse(File(canonicalTempDir, "paperplane-lock.yml").exists())
  }

  @Test
  fun `add rejects duplicate case-insensitively`() {
    writeBaseConfig()
    val (ui, _) = newUi()
    AddPluginCommand(ui, stubContextFactory(StubModrinth(mapOf("vault" to "1.7.3"))))
        .parse(listOf("vault"))

    val (ui2, t2) = newUi()
    assertThrows(com.github.ajalt.clikt.core.ProgramResult::class.java) {
      AddPluginCommand(ui2, stubContextFactory(StubModrinth(mapOf("vault" to "1.7.3"))))
          .parse(listOf("Vault"))
    }
    assertTrue(t2.writes.any { it.contains("already in paperplane.yml") })
  }

  @Test
  fun `remove errors when slug not present`() {
    writeBaseConfig()
    val (ui, t) = newUi()
    assertThrows(com.github.ajalt.clikt.core.ProgramResult::class.java) {
      RemovePluginCommand(ui, stubContextFactory(StubModrinth(emptyMap()))).parse(listOf("nope"))
    }
    assertTrue(t.writes.any { it.contains("'nope' is not in paperplane.yml") })
  }

  // ── update ────────────────────────────────────────────────────────────

  @Test
  fun `update bumps unpinned entry to latest`() {
    writeBaseConfig()
    val (ui, _) = newUi()
    AddPluginCommand(ui, stubContextFactory(StubModrinth(mapOf("vault" to "1.0.0"))))
        .parse(listOf("vault"))

    val (ui2, t) = newUi()
    UpdatePluginCommand(ui2, stubContextFactory(StubModrinth(mapOf("vault" to "2.0.0"))))
        .parse(emptyList())

    assertEquals("2.0.0", PluginLockfile.load(canonicalTempDir).find("vault")!!.version)
    assertTrue(t.writes.any { it.contains("vault: 1.0.0 -> 2.0.0") })
  }

  @Test
  fun `update without force skips pinned entries`() {
    writeBaseConfig()
    val (ui, _) = newUi()
    AddPluginCommand(ui, stubContextFactory(StubModrinth(mapOf("vault" to "1.7.3"))))
        .parse(listOf("vault@1.7.3"))

    val (ui2, t) = newUi()
    UpdatePluginCommand(ui2, stubContextFactory(StubModrinth(mapOf("vault" to "2.0.0"))))
        .parse(emptyList())

    assertEquals("1.7.3", PluginLockfile.load(canonicalTempDir).find("vault")!!.version)
    assertTrue(t.writes.any { it.contains("Already up to date") })
  }

  @Test
  fun `update prints no plugins message when config is empty`() {
    writeBaseConfig()
    val (ui, t) = newUi()
    UpdatePluginCommand(ui, stubContextFactory(StubModrinth(emptyMap()))).parse(emptyList())
    assertTrue(t.writes.any { it.contains("No plugins to update") })
  }

  // ── install ───────────────────────────────────────────────────────────

  @Test
  fun `install populates cache and deploys to server plugins dir`() {
    writeBaseConfig()
    val (ui, _) = newUi()
    AddPluginCommand(ui, stubContextFactory(StubModrinth(mapOf("vault" to "1.7.3"))))
        .parse(listOf("vault"))

    val (ui2, t) = newUi()
    InstallPluginCommand(ui2, stubContextFactory(StubModrinth(emptyMap()))).parse(emptyList())
    assertTrue(t.writes.any { it.contains("Installed") })

    // Jar should be in the server plugins dir, not just the cache.
    val pluginsDir = File(canonicalTempDir, ".paperplane/server/plugins")
    assertTrue(pluginsDir.exists(), "server/plugins/ should be created by install")
    assertTrue(
        pluginsDir.listFiles()?.any { it.name.contains("vault") } == true,
        "vault jar should be in server/plugins/",
    )

    // Manifest should track the installed file.
    val manifest = ManagedPlugins.load(File(canonicalTempDir, ".paperplane/server"))
    assertTrue(manifest.isNotEmpty(), "manifest should record the installed jar")
  }

  @Test
  fun `install prunes removed plugins via manifest`() {
    writeBaseConfig()
    val modrinth = StubModrinth(mapOf("vault" to "1.7.3", "luckperms" to "5.4.0"))

    // Add two plugins.
    val (ui, _) = newUi()
    AddPluginCommand(ui, stubContextFactory(modrinth)).parse(listOf("vault"))
    val (ui2, _) = newUi()
    AddPluginCommand(ui2, stubContextFactory(modrinth)).parse(listOf("luckperms"))

    // Install both.
    val (ui3, _) = newUi()
    InstallPluginCommand(ui3, stubContextFactory(modrinth)).parse(emptyList())
    val pluginsDir = File(canonicalTempDir, ".paperplane/server/plugins")
    assertEquals(2, pluginsDir.listFiles()?.count { it.name.endsWith(".jar") } ?: 0)

    // Remove vault.
    val (ui4, _) = newUi()
    RemovePluginCommand(ui4, stubContextFactory(modrinth)).parse(listOf("vault"))

    // Re-install — should prune vault from server/plugins/.
    val (ui5, _) = newUi()
    InstallPluginCommand(ui5, stubContextFactory(StubModrinth(mapOf("luckperms" to "5.4.0"))))
        .parse(emptyList())

    val remaining = pluginsDir.listFiles()?.filter { it.name.endsWith(".jar") }?.map { it.name }
    assertTrue(remaining?.any { it.contains("luckperms") } == true, "luckperms should survive")
    assertFalse(remaining?.any { it.contains("vault") } == true, "vault should be pruned")
  }

  @Test
  fun `install with missing lockfile prints nothing-to-install message`() {
    writeBaseConfig(
        extraServerLines =
            """
            |  plugins:
            |    - modrinth: "ghost"
            """
                .trimMargin()
    )
    // No lockfile written. Empty-lockfile branch returns early with a status message rather
    // than throwing — out-of-sync detection only fires when the lockfile has entries to compare.
    val (ui, t) = newUi()
    InstallPluginCommand(ui, stubContextFactory(StubModrinth(emptyMap()))).parse(emptyList())
    assertTrue(
        t.writes.any { it.contains("No plugins in paperplane-lock.yml") },
        "expected nothing-to-install message",
    )
  }

  @Test
  fun `install detects out of sync lockfile when both sides are non-empty`() {
    writeBaseConfig(
        extraServerLines =
            """
            |  plugins:
            |    - modrinth: "ghost"
            """
                .trimMargin()
    )
    PluginLockfile.save(
        canonicalTempDir,
        PluginLockfile()
            .upsert(
                LockedPlugin(
                    slug = "stranger",
                    source = "modrinth",
                    version = "1.0.0",
                    sha512 = "x",
                    url = "https://example/stranger.jar",
                    filename = "stranger.jar",
                )
            ),
    )
    val (ui, t) = newUi()
    assertThrows(com.github.ajalt.clikt.core.ProgramResult::class.java) {
      InstallPluginCommand(ui, stubContextFactory(StubModrinth(emptyMap()))).parse(emptyList())
    }
    assertTrue(t.writes.any { it.contains("out of sync") })
  }

  // ── list ──────────────────────────────────────────────────────────────

  @Test
  fun `list shows installed plugins with version and source`() {
    writeBaseConfig()
    val (ui, _) = newUi()
    AddPluginCommand(ui, stubContextFactory(StubModrinth(mapOf("vault" to "1.7.3"))))
        .parse(listOf("vault@1.7.3"))

    val (ui2, t) = newUi()
    ListPluginCommand(ui2, stubContextFactory(StubModrinth(emptyMap()))).parse(emptyList())
    assertTrue(t.writes.any { it.contains("vault") && it.contains("1.7.3") })
    assertTrue(t.writes.any { it.contains("pinned") })
  }

  @Test
  fun `list reports no plugins on empty lockfile`() {
    val (ui, t) = newUi()
    ListPluginCommand(ui, stubContextFactory(StubModrinth(emptyMap()))).parse(emptyList())
    assertTrue(t.writes.any { it.contains("No plugins installed") })
  }
}
