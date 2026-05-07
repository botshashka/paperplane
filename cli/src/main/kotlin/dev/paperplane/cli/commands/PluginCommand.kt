package dev.paperplane.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.paperplane.cli.config.PaperPlaneConfig
import dev.paperplane.cli.plugins.ManagedPlugins
import dev.paperplane.cli.plugins.ModrinthClient
import dev.paperplane.cli.plugins.ModrinthNetworkError
import dev.paperplane.cli.plugins.ModrinthNotFound
import dev.paperplane.cli.plugins.PluginCache
import dev.paperplane.cli.plugins.PluginDependency
import dev.paperplane.cli.plugins.PluginLockfile
import dev.paperplane.cli.plugins.PluginResolver
import dev.paperplane.cli.ui.TerminalUI
import java.io.File

/** Parent command for `ppl plugin`. Does nothing on its own — routes to subcommands. */
class PluginCommand(
    ui: TerminalUI,
    contextFactory: (TerminalUI) -> PluginCommandContext = ::PluginCommandContext,
) : CliktCommand(name = "plugin") {
  init {
    subcommands(
        AddPluginCommand(ui, contextFactory),
        RemovePluginCommand(ui, contextFactory),
        UpdatePluginCommand(ui, contextFactory),
        InstallPluginCommand(ui, contextFactory),
        ListPluginCommand(ui, contextFactory),
    )
  }

  override fun run() = Unit
}

/**
 * Shared helpers for the plugin subcommands — loading config, constructing services, etc. Open +
 * virtual so tests can subclass and override [resolver] to inject a fake that doesn't hit Modrinth
 * over the network.
 */
open class PluginCommandContext(
    val ui: TerminalUI,
    val projectDir: File = File(System.getProperty("user.dir")),
) {
  open val modrinth: ModrinthClient by lazy { ModrinthClient() }

  open val cache: PluginCache by lazy { PluginCache(File(projectDir, ".paperplane/cache/plugins")) }

  open val resolver: PluginResolver by lazy { PluginResolver(modrinth, cache) }

  fun loadConfig(): PaperPlaneConfig = PaperPlaneConfig.load(projectDir, ui)

  fun mcVersion(config: PaperPlaneConfig): String {
    val version = config.server.version
    if (version.isNullOrBlank()) {
      ui.error("server.version is not set in paperplane.yml")
      ui.status("Set it to your target Minecraft version before adding plugins.")
      throw ProgramResult(1)
    }
    return version
  }

  fun saveConfig(config: PaperPlaneConfig) {
    PaperPlaneConfig.save(projectDir, config)
  }

  fun saveLock(lock: PluginLockfile) {
    PluginLockfile.save(projectDir, lock)
  }

  fun loadLock(): PluginLockfile = PluginLockfile.load(projectDir)
}

/**
 * Parses a user-provided spec into a [PluginDependency]. Accepted shapes:
 *
 * - `placeholderapi` — bare slug or ID, defaults to Modrinth source
 * - `placeholderapi@2.11.6` — pinned version
 * - `modrinth:vault` / `modrinth:vault@1.7.3` — explicit source prefix
 * - `local:./libs/foo.jar` — local filesystem path
 * - `https://modrinth.com/plugin/worldedit` — Modrinth URL (plugin/project/mod path segments)
 * - `https://modrinth.com/plugin/worldedit/version/7.3.0` — URL with pinned version
 * - `https://modrinth.com/project/1u6JkXh5` — URL with Modrinth project ID
 *
 * URL parsing is a paste-from-browser-bar convenience. The host is checked to guard against feeding
 * a non-Modrinth URL into the Modrinth source — future sources (Hangar, GitHub) will add their own
 * URL recognition in [parseUrlSpec].
 */
internal fun parseSpec(spec: String): PluginDependency {
  if (spec.startsWith("http://") || spec.startsWith("https://")) {
    return parseUrlSpec(spec)
  }
  val (sourcePart, rest) =
      if (spec.startsWith("local:")) "local" to spec.removePrefix("local:")
      else if (spec.startsWith("modrinth:")) "modrinth" to spec.removePrefix("modrinth:")
      else "modrinth" to spec
  return when (sourcePart) {
    "local" -> PluginDependency.local(rest)
    "modrinth" -> {
      val at = rest.indexOf('@')
      if (at >= 0) PluginDependency.modrinth(rest.substring(0, at), rest.substring(at + 1))
      else PluginDependency.modrinth(rest)
    }
    else -> throw IllegalArgumentException("Unknown plugin source: $sourcePart")
  }
}

/**
 * Extracts a [PluginDependency] from a project URL. Only Modrinth is recognized in v1 — other hosts
 * throw an error directing the user to the explicit-prefix form. Modrinth URLs have multiple valid
 * path shapes:
 * ```
 * /plugin/<slug-or-id>
 * /plugin/<slug-or-id>/version/<version>
 * /project/<slug-or-id>
 * /mod/<slug-or-id>              (mods also live under /project endpoints in the API)
 * /datapack/<slug-or-id>         (same)
 * ```
 *
 * We strip the leading category segment (`plugin` | `project` | `mod` | `datapack`), then treat the
 * next segment as the slug/ID. A trailing `/version/<x>` is treated as a pinned version.
 */
private val PROJECT_CATEGORIES = setOf("plugin", "project", "mod", "datapack", "resourcepack")

private fun parseUrlSpec(spec: String): PluginDependency {
  val uri =
      try {
        java.net.URI(spec)
      } catch (e: java.net.URISyntaxException) {
        throw IllegalArgumentException("Invalid URL: $spec", e)
      }
  val segments = validateModrinthUrl(uri, spec)
  val slugOrId = segments[1]
  val version =
      if (segments.size >= 4 && segments[2].equals("version", ignoreCase = true)) segments[3]
      else null
  return PluginDependency.modrinth(slugOrId, version)
}

private fun validateModrinthUrl(uri: java.net.URI, spec: String): List<String> {
  val host = uri.host
  require(host != null) { "URL has no host: $spec" }
  require(
      host.equals("modrinth.com", ignoreCase = true) ||
          host.equals("www.modrinth.com", ignoreCase = true)
  ) {
    "Only Modrinth URLs are supported. For other sources, use an explicit prefix " +
        "like `modrinth:<slug>`, `local:<path>`, etc."
  }
  val segments = uri.path.trim('/').split('/').filter { it.isNotEmpty() }
  require(segments.size >= 2) {
    "Modrinth URL doesn't point at a project: $spec — expected /plugin/<slug>"
  }
  require(segments[0].lowercase() in PROJECT_CATEGORIES) {
    "Modrinth URL path '${segments[0]}' is not a project page. Expected one of: " +
        PROJECT_CATEGORIES.joinToString(", ")
  }
  return segments
}

/**
 * Wraps a block that calls Modrinth/resolver APIs, catching known exception types and translating
 * them into user-facing error messages + [ProgramResult]. Consolidates the four catch branches that
 * every plugin subcommand would otherwise duplicate.
 */
internal fun <T> handleResolveErrors(ui: TerminalUI, block: () -> T): T {
  try {
    return block()
  } catch (e: ModrinthNotFound) {
    ui.error(e.message ?: "Modrinth project not found")
  } catch (e: ModrinthNetworkError) {
    ui.error(e.message ?: "Network error reaching Modrinth")
  } catch (e: IllegalStateException) {
    ui.error(e.message ?: "Resolution failed")
  } catch (e: java.io.IOException) {
    ui.error(e.message ?: "I/O error")
  }
  throw ProgramResult(1)
}

class AddPluginCommand(
    private val ui: TerminalUI,
    private val contextFactory: (TerminalUI) -> PluginCommandContext = ::PluginCommandContext,
) : CliktCommand(name = "add") {
  override fun help(context: com.github.ajalt.clikt.core.Context) =
      "Resolve a plugin, download it, and add it to paperplane.yml + paperplane-lock.yml"

  private val spec by
      argument("spec", help = "e.g. placeholderapi, vault@1.7.3, local:./libs/x.jar")
  private val upgrade by
      option(
              "--upgrade",
              "-u",
              help =
                  "Replace an existing entry with the freshly resolved version. " +
                      "Drops any @version pin from paperplane.yml unless the spec sets a new one.",
          )
          .flag()

  override fun run() {
    val ctx = contextFactory(ui)
    try {
      val rawDep = parseSpec(spec)
      // Canonicalize Modrinth IDs (e.g. "1u6JkXh5") to slugs ("worldedit") so paperplane.yml
      // and the lockfile never contain opaque IDs. Slug input round-trips identically since
      // Modrinth's /project endpoint accepts both forms.
      val config = ctx.loadConfig()
      // Canonicalize Modrinth input (slug, ID, or URL) to the lowercase slug before checking
      // for duplicates — the spinner wraps the network call to /project/{id_or_slug}.
      val dep =
          if (rawDep.source == PluginDependency.Source.MODRINTH) {
            handleResolveErrors(ui) {
              ui.spin("Resolving ${rawDep.modrinth}...") {
                val canonical = ctx.modrinth.getProject(rawDep.modrinth!!).slug.lowercase()
                PluginDependency.modrinth(canonical, rawDep.version)
              }
            }
          } else rawDep
      val existing = config.server.plugins.find { it.slug == dep.slug }
      if (existing != null && !upgrade) {
        ui.block {
          error("'${dep.slug}' is already in paperplane.yml")
          status("Use `ppl plugin update ${dep.slug}` to bump the version,")
          status("or `ppl plugin add ${dep.slug} --upgrade` to replace it with the latest.")
        }
        throw ProgramResult(1)
      }
      val mcVersion =
          if (dep.source == PluginDependency.Source.MODRINTH) ctx.mcVersion(config) else ""
      val resolver = ctx.resolver
      val lock = ctx.loadLock()
      val previouslyLocked = lock.find(dep.slug)

      handleResolveErrors(ui) {
        val verb = if (existing != null) "Upgrading" else "Downloading"
        val result =
            ui.spin("$verb ${dep.slug}...") {
              // For an upgrade, replace the existing dep in place (preserving order); otherwise
              // append. The new dep already carries any @version the user supplied — if not, the
              // YAML pin is dropped, matching `npm install pkg@latest` semantics.
              val newPlugins =
                  if (existing != null) config.server.plugins.map { if (it.slug == dep.slug) dep else it }
                  else config.server.plugins + dep
              val newConfig = config.copy(server = config.server.copy(plugins = newPlugins))
              // Drop the slug from the lockfile when upgrading so sync re-resolves from scratch
              // rather than seeing the old version as up-to-date.
              val baseLock = if (existing != null) lock.remove(dep.slug) else lock
              val syncResult =
                  resolver.sync(newConfig.server.plugins, baseLock, mcVersion.ifEmpty { null })
              ctx.saveConfig(newConfig)
              ctx.saveLock(syncResult.lockfile)
              syncResult
            }

        val locked = result.lockfile.find(dep.slug)!!
        ui.block {
          if (existing != null && previouslyLocked != null) {
            success("Upgraded ${locked.slug}: ${previouslyLocked.version} -> ${locked.version}")
          } else {
            success("Added ${locked.slug} ${locked.version}")
          }
          info("source", locked.source)
          info("file", locked.filename)
        }
      }
    } finally {
      ui.endView()
    }
  }
}

class RemovePluginCommand(
    private val ui: TerminalUI,
    private val contextFactory: (TerminalUI) -> PluginCommandContext = ::PluginCommandContext,
) : CliktCommand(name = "remove") {
  override fun help(context: com.github.ajalt.clikt.core.Context) =
      "Remove a plugin from paperplane.yml + paperplane-lock.yml"

  private val slug by argument("slug")

  override fun run() {
    val ctx = contextFactory(ui)
    try {
      val config = ctx.loadConfig()
      // User input is normalized to lowercase for comparison against the always-lowercase
      // stored slugs. `ppl plugin remove WorldEdit` matches a stored `worldedit`.
      val target = slug.lowercase()
      val filtered = config.server.plugins.filterNot { it.slug == target }
      if (filtered.size == config.server.plugins.size) {
        ui.error("'$slug' is not in paperplane.yml")
        throw ProgramResult(1)
      }
      val newConfig = config.copy(server = config.server.copy(plugins = filtered))
      ctx.saveConfig(newConfig)
      val newLock = ctx.loadLock().remove(target)
      if (newLock.plugins.isEmpty()) PluginLockfile.delete(ctx.projectDir)
      else ctx.saveLock(newLock)

      ui.block { success("Removed $slug") }
    } finally {
      ui.endView()
    }
  }
}

class UpdatePluginCommand(
    private val ui: TerminalUI,
    private val contextFactory: (TerminalUI) -> PluginCommandContext = ::PluginCommandContext,
) : CliktCommand(name = "update") {
  override fun help(context: com.github.ajalt.clikt.core.Context) =
      "Re-resolve all plugins (or just one) against Modrinth and rewrite the lockfile"

  private val slug by argument("slug").optional()
  private val force by option("--force", "-f", help = "Also update pinned entries").flag()

  override fun run() {
    val ctx = contextFactory(ui)
    try {
      val config = ctx.loadConfig()
      if (config.server.plugins.isEmpty()) {
        ui.status("No plugins to update")
        return
      }
      val mcVersion = ctx.mcVersion(config)
      val resolver = ctx.resolver
      val lock = ctx.loadLock()
      val targets = slug?.let { setOf(it.lowercase()) }

      // Heads-up when `update --force` targets a slug that's pinned in paperplane.yml itself
      // (e.g. `vault@1.7.3`). The YAML constraint feeds resolveExact, so --force only bypasses
      // the lockfile pin guard, not the YAML version. Tell the user how to actually bump it
      // instead of silently reporting "Already up to date".
      if (force && targets != null) {
        for (dep in config.server.plugins) {
          if (dep.slug !in targets) continue
          if (dep.version == null) continue
          ui.block {
            status(
                "${dep.slug} is pinned in paperplane.yml at ${dep.version}. " +
                    "`--force` only ignores the lockfile pin, not the YAML version.")
            status("Run `ppl plugin add ${dep.slug} --upgrade` to bump to latest,")
            status("or edit paperplane.yml to drop the @${dep.version} suffix.")
          }
        }
      }

      handleResolveErrors(ui) {
        val result =
            ui.spin("Checking for updates...") {
              val updated =
                  resolver.update(
                      config.server.plugins,
                      lock,
                      mcVersion,
                      targets = targets,
                      force = force,
                  )
              // Also download anything that changed.
              resolver.sync(config.server.plugins, updated, mcVersion)
            }
        ctx.saveLock(result.lockfile)

        ui.block {
          val changes =
              result.lockfile.plugins.mapNotNull { newLocked ->
                val old = lock.find(newLocked.slug)
                when {
                  old == null -> "${newLocked.slug}: added ${newLocked.version}"
                  old.version != newLocked.version ->
                      "${newLocked.slug}: ${old.version} -> ${newLocked.version}"
                  // Same version, different sha — the bytes changed (local re-hash, or a
                  // tampered/repaired Modrinth checksum). Surface it; otherwise the user sees
                  // "Already up to date" even though we silently rewrote the lockfile.
                  old.sha512 != newLocked.sha512 -> "${newLocked.slug}: refreshed checksum"
                  else -> null
                }
              }
          if (changes.isEmpty()) {
            success("Already up to date")
          } else {
            for (change in changes) success(change)
          }
          val summary = result.lockfile.plugins.joinToString(", ") { "${it.slug} ${it.version}" }
          if (summary.isNotEmpty()) info("Plugins", summary)
        }
      }
    } finally {
      ui.endView()
    }
  }
}

class InstallPluginCommand(
    private val ui: TerminalUI,
    private val contextFactory: (TerminalUI) -> PluginCommandContext = ::PluginCommandContext,
) : CliktCommand(name = "install") {
  override fun help(context: com.github.ajalt.clikt.core.Context) =
      "Download locked plugins to cache and deploy them into the server plugins directory"

  private val slug by argument("slug").optional()
  private val force by option("--force", "-f", help = "Re-download even if cached").flag()

  override fun run() {
    val ctx = contextFactory(ui)
    try {
      val config = ctx.loadConfig()
      val lock = ctx.loadLock()
      // Sanity check: lockfile in sync with config? Runs BEFORE the empty-lock early return so a
      // hand-edit of paperplane.yml (config has plugins, lock missing entirely) is reported as
      // out-of-sync rather than silently passing as "nothing to install".
      val configSlugs = config.server.plugins.map { it.slug }.toSet()
      val lockSlugs = lock.plugins.map { it.slug }.toSet()
      if (configSlugs != lockSlugs) {
        ui.block {
          error("paperplane-lock.yml is out of sync with paperplane.yml")
          val onlyInConfig = configSlugs - lockSlugs
          val onlyInLock = lockSlugs - configSlugs
          if (onlyInConfig.isNotEmpty()) {
            status("In config, not in lock: ${onlyInConfig.joinToString(", ")}")
            status("Run `ppl plugin add` for these (or delete them from paperplane.yml).")
          }
          if (onlyInLock.isNotEmpty()) {
            status("In lock, not in config: ${onlyInLock.joinToString(", ")}")
            status("Run `ppl plugin remove` for these.")
          }
        }
        throw ProgramResult(1)
      }
      if (lock.plugins.isEmpty()) {
        ui.status("No plugins in paperplane-lock.yml — nothing to install")
        return
      }

      val resolver = ctx.resolver
      val forceSlugs = slug?.let { setOf(it.lowercase()) }
      val shouldForce = force || forceSlugs != null

      handleResolveErrors(ui) {
        val files =
            ui.spin("Installing plugins...") {
              resolver.installFromLockfile(lock, force = force, forceSlugs = forceSlugs)
            }
        // Deploy to every server dir under .paperplane/ (server, server-swap for blue-green).
        // Create the primary server dir if it doesn't exist yet; skip swap if it hasn't been
        // initialized by a blue-green dev session.
        val ppDir = File(ctx.projectDir, ".paperplane")
        val currentFilenames = files.map { it.name }.toSet()
        val allAdded = mutableSetOf<String>()
        val allPruned = mutableSetOf<String>()
        for (dirName in listOf("server", "server-swap")) {
          val serverDir = File(ppDir, dirName)
          if (!serverDir.exists() && dirName == "server") serverDir.mkdirs()
          if (!serverDir.exists()) continue
          val oldManifest = ManagedPlugins.load(serverDir)
          val pluginsDir = File(serverDir, "plugins")
          ManagedPlugins.copyJars(files, pluginsDir)
          allPruned += ManagedPlugins.prune(serverDir, currentFilenames)
          allAdded += currentFilenames - oldManifest
        }
        val total = lock.plugins.size
        val pluginWord = if (total == 1) "plugin" else "plugins"
        val summary = lock.plugins.joinToString(", ") { "${it.slug} ${it.version}" }
        ui.block {
          if (shouldForce) {
            // For a single-slug install, the count is 1, not lock.plugins.size — only that slug
            // was actually re-downloaded. Naming the slug here makes it clear what changed.
            if (forceSlugs != null) {
              val word = if (forceSlugs.size == 1) "plugin" else "plugins"
              success("Reinstalled ${forceSlugs.size} $word (${forceSlugs.joinToString(", ")})")
            } else {
              success("Reinstalled $total $pluginWord")
            }
          } else if (allAdded.isEmpty() && allPruned.isEmpty()) {
            success("All $pluginWord already installed")
          } else {
            if (allAdded.isNotEmpty()) {
              success("Installed ${allAdded.size} $pluginWord")
            }
            if (allPruned.isNotEmpty()) {
              success("Removed ${allPruned.size} $pluginWord")
            }
          }
          info("Plugins", summary)
        }
      }
    } finally {
      ui.endView()
    }
  }
}

class ListPluginCommand(
    private val ui: TerminalUI,
    private val contextFactory: (TerminalUI) -> PluginCommandContext = ::PluginCommandContext,
) : CliktCommand(name = "list") {
  override fun help(context: com.github.ajalt.clikt.core.Context) =
      "Show installed plugins from paperplane-lock.yml"

  override fun run() {
    val ctx = contextFactory(ui)
    try {
      val lock = ctx.loadLock()
      if (lock.plugins.isEmpty()) {
        ui.status("No plugins installed")
        return
      }
      ui.block {
        for (entry in lock.plugins) {
          val pinTag = if (entry.pinned) " (pinned)" else ""
          info(entry.slug, "${entry.version}$pinTag [${entry.source}]")
        }
      }
    } finally {
      ui.endView()
    }
  }
}
