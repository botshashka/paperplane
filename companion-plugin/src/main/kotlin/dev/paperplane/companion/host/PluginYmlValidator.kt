package dev.paperplane.companion.host

import java.util.logging.Logger
import org.bukkit.Server
import org.bukkit.plugin.PluginDescriptionFile
import org.bukkit.plugin.PluginLoadOrder

/**
 * Validates a user plugin's `plugin.yml` against the constraints of being hosted by the companion.
 *
 * The companion runs `load: POSTWORLD` and only loads its inner plugin after `ServerLoadEvent` —
 * which means certain plugin.yml shapes are incompatible with hot-reload mode. Detect them here and
 * surface a clear actionable error rather than failing at load time with a confusing stack trace.
 */
object PluginYmlValidator {

  sealed class Result {
    object Ok : Result()

    data class Reject(val message: String) : Result()
  }

  /**
   * Validates against hard architectural limits. If validation fails, the host must abort the load
   * and surface [Result.Reject.message] to the CLI for display to the user.
   *
   * Soft warnings (missing softdepends) are logged but do not fail validation — same as Paper.
   */
  fun validate(
      description: PluginDescriptionFile,
      server: Server,
      logger: Logger,
  ): Result {
    // Hard limit: STARTUP plugins load before world generation. Companion runs POSTWORLD; we
    // cannot host anything that needs to be alive earlier.
    if (description.load == PluginLoadOrder.STARTUP) {
      return Result.Reject(
          "Plugin '${description.name}' declares `load: STARTUP`. " +
              "PaperPlane hot-reload runs POSTWORLD and cannot host STARTUP plugins. " +
              "Set `dev.mode: restart` in paperplane.yml."
      )
    }

    val apiVersion = description.apiVersion
    if (!apiVersion.isNullOrBlank()) {
      val paperApi = detectPaperApiVersion(server)
      if (paperApi != null && compareApiVersions(apiVersion, paperApi) > 0) {
        return Result.Reject(
            "Plugin '${description.name}' requires api-version $apiVersion but the running Paper " +
                "server is $paperApi. Update Paper or lower api-version."
        )
      }
    }

    for (dep in description.depend) {
      if (server.pluginManager.getPlugin(dep) == null) {
        return Result.Reject(
            "Plugin '${description.name}' depends on '$dep', but it is not loaded. " +
                "Add it to `server.plugins` in paperplane.yml or place it in plugins/."
        )
      }
    }

    for (dep in description.softDepend) {
      if (server.pluginManager.getPlugin(dep) == null) {
        logger.info(
            "Soft-dependency '$dep' is not loaded; '${description.name}' will run without it."
        )
      }
    }

    return Result.Ok
  }

  /**
   * Compares two API versions like "1.21" / "1.21.4" / "1.13". Returns negative / 0 / positive.
   *
   * Robust to extra segments (`1.21.4` > `1.21`). Non-numeric segments compare as -1 (treats
   * malformed versions as older to fail open rather than block users with surprising errors).
   */
  internal fun compareApiVersions(a: String, b: String): Int {
    val pa = a.split('.')
    val pb = b.split('.')
    val n = maxOf(pa.size, pb.size)
    for (i in 0 until n) {
      val ai = pa.getOrNull(i)?.toIntOrNull() ?: -1
      val bi = pb.getOrNull(i)?.toIntOrNull() ?: -1
      if (ai != bi) return ai.compareTo(bi)
    }
    return 0
  }

  private fun detectPaperApiVersion(server: Server): String? {
    val v = server.bukkitVersion ?: return null
    // Paper bukkitVersion examples: "1.21.4-R0.1-SNAPSHOT", "1.20.6-R0.1-SNAPSHOT".
    val match = Regex("""^(\d+\.\d+(?:\.\d+)?)""").find(v) ?: return null
    return match.groupValues[1]
  }
}
