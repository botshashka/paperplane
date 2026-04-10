package dev.paperplane.cli.plugins

import java.io.File
import java.io.IOException

/**
 * Orchestrates the full dependency flow: given the list of [PluginDependency] entries from
 * `paperplane.yml` and the current [PluginLockfile], decides which need to be freshly resolved
 * against the source (Modrinth API or local path), mutates the lockfile, and downloads any missing
 * JARs. Returns the ready-to-copy JAR files for the server plugins directory.
 *
 * The three invariants this class enforces:
 *
 * 1. **Existing pinned entries never drift.** If `paperplane.yml` has `vault@1.7.3` and the
 *    lockfile already has `vault 1.7.3`, we don't re-query Modrinth on `ppl dev` — we just validate
 *    that the cache is warm.
 * 2. **New entries are resolved once and frozen.** The first time `placeholderapi` appears in
 *    config without a version, we resolve "latest compatible" from Modrinth, write it to the
 *    lockfile, and from that point treat it as effectively pinned (until `ppl plugin update`). This
 *    matches npm-install behavior: lockfile is authoritative.
 * 3. **Orphans are dropped.** If a plugin was removed from config, it's dropped from the lockfile.
 *    The cached JAR is left alone — `ppl clean --all` cleans the cache.
 */
class PluginResolver(
    private val modrinth: ModrinthClient,
    private val cache: PluginCache,
) {

  /**
   * Result of a sync operation. [jars] is the list of cache-resolved files that should be copied
   * into the server plugins directory. [lockfile] is the new lockfile state (may differ from the
   * input if entries were added/removed/updated). [hitNetwork] tells the caller whether the
   * operation required API calls — used to decide whether to show a spinner.
   */
  data class SyncResult(
      val jars: List<File>,
      val lockfile: PluginLockfile,
      val hitNetwork: Boolean,
      val summary: List<String>, // "PlaceholderAPI 2.11.6", "Vault 1.7.3" — for instant feedback
  )

  /**
   * Reconcile [config] entries with [previous] lockfile, resolving anything new against Modrinth
   * and dropping anything that's been removed. Downloads missing cache entries. Does NOT re-
   * resolve existing entries — use [update] for that.
   *
   * [mcVersion] is the Minecraft version to filter Modrinth resolution by. Must be set in
   * `paperplane.yml` before plugins can be resolved (because "latest compatible with what?").
   */
  fun sync(
      config: List<PluginDependency>,
      previous: PluginLockfile,
      mcVersion: String?,
  ): SyncResult {
    val configSlugs = config.map { it.slug }.toSet()
    // Drop orphans from lockfile.
    var working = previous.copy(plugins = previous.plugins.filter { it.slug in configSlugs })

    var hitNetwork = false
    for (dep in config) {
      val existing = working.find(dep.slug)
      val needsResolve =
          when {
            existing == null -> true
            // Pinned entries track whether the user's YAML matches the lockfile. If the user
            // bumped `vault@1.7.3 -> vault@1.8.0` by hand, we need to re-resolve.
            dep.version != null && dep.version != existing.version -> true
            // Source changed mid-flight (e.g. someone swapped modrinth: -> local:). Re-resolve.
            dep.source.key != existing.source -> true
            else -> false
          }
      if (needsResolve) {
        val resolved = resolve(dep, mcVersion)
        if (dep.source == PluginDependency.Source.MODRINTH) hitNetwork = true
        working = working.upsert(resolved)
      }
    }

    // Download any missing JARs from the (now-complete) lockfile.
    val jars = mutableListOf<File>()
    val summary = mutableListOf<String>()
    for (locked in working.plugins) {
      val cached = cache.get(locked)
      val file =
          if (cached != null) {
            cached
          } else {
            hitNetwork = hitNetwork || locked.source != PluginDependency.Source.LOCAL.key
            cache.download(locked)
          }
      jars += file
      summary += "${locked.slug} ${locked.version}"
    }

    return SyncResult(jars, working, hitNetwork, summary)
  }

  /**
   * Re-resolves the specified [targets] (or all of [config], if [targets] is null) against
   * Modrinth, picking the latest compatible version. Pinned entries are only touched if their slug
   * is in [targets] AND [force] is true. `local:` entries just re-hash in place.
   *
   * Returns a new lockfile with the updates applied. Does NOT download — callers should call [sync]
   * (or [installFromLockfile]) after updating to populate the cache.
   */
  fun update(
      config: List<PluginDependency>,
      previous: PluginLockfile,
      mcVersion: String?,
      targets: Set<String>? = null,
      force: Boolean = false,
  ): PluginLockfile {
    var working = previous
    for (dep in config) {
      if (targets != null && dep.slug !in targets) continue
      val existing = working.find(dep.slug)
      // Respect the pin unless --force. A pinned entry in paperplane.yml (e.g. `vault@1.7.3`)
      // is a statement from the user; `ppl plugin update vault` without --force is a no-op on
      // that slug.
      if (existing != null && existing.pinned && !force) continue
      val resolved = resolve(dep, mcVersion)
      working = working.upsert(resolved)
    }
    return working
  }

  /**
   * Populates the cache from [lockfile] without any resolution or config interaction. Used by `ppl
   * plugin install` — the "npm ci" equivalent: deterministic, offline-capable when the cache is
   * already warm. [force] forces a re-download of every entry (and [forceSlugs], if set, restricts
   * that to those slugs).
   */
  fun installFromLockfile(
      lockfile: PluginLockfile,
      force: Boolean = false,
      forceSlugs: Set<String>? = null,
  ): List<File> {
    val result = mutableListOf<File>()
    for (locked in lockfile.plugins) {
      val shouldForce = force || (forceSlugs != null && locked.slug in forceSlugs)
      val file = cache.download(locked, force = shouldForce)
      result += file
    }
    return result
  }

  private fun resolve(dep: PluginDependency, mcVersion: String?): LockedPlugin {
    return when (dep.source) {
      PluginDependency.Source.MODRINTH -> resolveModrinth(dep, mcVersion)
      PluginDependency.Source.LOCAL -> resolveLocal(dep)
    }
  }

  private fun resolveModrinth(dep: PluginDependency, mcVersion: String?): LockedPlugin {
    checkNotNull(mcVersion?.takeIf { it.isNotBlank() }) {
      "Cannot resolve Modrinth plugins without a Minecraft version. " +
          "Set `server.version` in paperplane.yml before adding plugins."
    }
    val slug = dep.modrinth!!
    val resolved =
        if (dep.version != null) resolveExactOrFail(slug, dep.version, mcVersion)
        else resolveLatestOrFail(slug, mcVersion)
    return LockedPlugin(
        slug = slug.lowercase(),
        source = PluginDependency.Source.MODRINTH.key,
        version = resolved.versionNumber,
        sha512 = resolved.sha512,
        url = resolved.downloadUrl,
        filename = resolved.filename,
        pinned = dep.version != null,
    )
  }

  private fun resolveExactOrFail(
      slug: String,
      version: String,
      mcVersion: String,
  ): ModrinthClient.ResolvedVersion {
    return modrinth.resolveExact(slug, version, mcVersion)
        ?: run {
          val available =
              modrinth.listCompatibleVersions(slug, mcVersion).take(MAX_SUGGESTIONS).map {
                it.versionNumber
              }
          error(
              "Modrinth has no version '$version' of '$slug' for MC $mcVersion. " +
                  if (available.isEmpty()) "No compatible versions exist."
                  else "Available: ${available.joinToString(", ")}"
          )
        }
  }

  private fun resolveLatestOrFail(
      slug: String,
      mcVersion: String,
  ): ModrinthClient.ResolvedVersion {
    return modrinth.resolveLatest(slug, mcVersion)
        ?: error("Modrinth has no version of '$slug' compatible with MC $mcVersion on Paper.")
  }

  companion object {
    private const val MAX_SUGGESTIONS = 10
  }

  private fun resolveLocal(dep: PluginDependency): LockedPlugin {
    val path = dep.local!!
    val file = File(path)
    if (!file.exists()) {
      throw IOException("Local plugin file does not exist: $path")
    }
    val sha = cache.sha512(file)
    return LockedPlugin(
        slug = PluginDependency.localSlug(path).lowercase(),
        source = PluginDependency.Source.LOCAL.key,
        version = file.lastModified().toString(), // no semver for local; use mtime as a version-ish
        sha512 = sha,
        url = file.absolutePath,
        filename = file.name,
        pinned = true,
    )
  }
}
