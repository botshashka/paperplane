package dev.paperplane.companion

import com.google.gson.annotations.SerializedName
import java.io.File
import org.bukkit.WorldCreator
import org.bukkit.WorldType
import org.bukkit.plugin.java.JavaPlugin

/**
 * The companion's secondary-world primitives for the void-only world-refresh pattern: the server's
 * default world is a tiny void world that never changes; the actual dev world is a secondary world
 * the CLI syncs into the world container and asks this class to (re)load.
 *
 * [refresh] is one half of the fresh-server swap sequence — the CLI syncs world files, requests the
 * refresh, and only then host-loads the plugin (world refresh strictly precedes host-load, so
 * `onEnable` can never capture a `World` the refresh is about to invalidate). [warmup] exercises
 * the world-load path once so the first real refresh in a JVM doesn't pay the ~0.4 s JIT-cold
 * penalty (gate-5 finding 5); when to invoke it is the caller's policy.
 *
 * Main-thread confined, like everything the message handler drives.
 */
class WorldRefresher(
    private val plugin: JavaPlugin,
    /**
     * Where world directories live — the server root for PaperPlane-managed servers. Injected
     * rather than read from `server.worldContainer` so tests can point it at a temp dir (and
     * because MockBukkit doesn't implement `getWorldContainer`).
     */
    private val worldContainer: File,
) {
  companion object {
    /** The throwaway world [warmup] loads and unloads. */
    const val WARMUP_WORLD_NAME = "paperplane_warmup"

    // Superflat preset with no layers in a void biome: loading it exercises the full chunk
    // pipeline (the JIT warmup that matters) while generating essentially nothing.
    private const val VOID_GENERATOR_SETTINGS = """{"layers": [], "biome": "minecraft:the_void"}"""
  }

  sealed class Outcome {
    abstract val worldName: String
    abstract val durationMs: Long

    data class Ok(override val worldName: String, override val durationMs: Long) : Outcome()

    data class Failed(
        override val worldName: String,
        val message: String,
        override val durationMs: Long,
    ) : Outcome()
  }

  /**
   * Loads (or reloads) the secondary world [worldName] from its already-synced directory. A
   * previous incarnation is unloaded first with `save = false` — the synced files are the
   * authoritative state, and letting the stale incarnation save would overwrite them with exactly
   * the world state the refresh exists to replace.
   */
  fun refresh(worldName: String): Outcome {
    val start = System.currentTimeMillis()
    fun failed(message: String) =
        Outcome.Failed(worldName, message, System.currentTimeMillis() - start)

    val defaultWorld = plugin.server.worlds.firstOrNull()
    refreshGuardFailure(worldName, defaultWorld?.name)?.let {
      return failed(it)
    }

    plugin.server.getWorld(worldName)?.let { previous ->
      // Unload refuses while players stand in the world; parking them at the default spawn is the
      // mechanical minimum. Where players should actually end up is presentation-time policy that
      // lives with the transfer machinery, not here.
      val parkAt = defaultWorld?.spawnLocation
      if (parkAt != null) {
        for (player in previous.players) player.teleport(parkAt)
      }
      if (!plugin.server.unloadWorld(previous, false)) {
        return failed("could not unload the previous incarnation of '$worldName'")
      }
    }

    WorldCreator(worldName).createWorld()
        ?: return failed("the server refused to load world '$worldName'")
    plugin.logger.info("Refreshed world '$worldName'")
    return Outcome.Ok(worldName, System.currentTimeMillis() - start)
  }

  /** The reason [worldName] can't be refreshed at all, or null when the request is well-formed. */
  private fun refreshGuardFailure(worldName: String, defaultWorldName: String?): String? =
      when {
        worldName.isBlank() -> "worldRefresh needs a worldName"
        worldName == defaultWorldName ->
            "'$worldName' is the server's default world, which Bukkit cannot unload — " +
                "the dev world must load under a different name than the server's level-name"
        !File(File(worldContainer, worldName), "level.dat").isFile ->
            "no world files for '$worldName' in ${worldContainer.absolutePath} — " +
                "world sync must complete before the refresh"
        else -> null
      }

  /**
   * Loads and unloads a throwaway void world, then deletes its directory. Pays the once-per-JVM
   * JIT/classload cost of the chunk pipeline (~0.4 s, gate-5 finding 5) so the session's first real
   * refresh doesn't.
   */
  fun warmup(): Outcome {
    val start = System.currentTimeMillis()
    val dir = File(worldContainer, WARMUP_WORLD_NAME)
    deleteRecursively(dir) // a leftover from a crashed run must not be loaded as a stale world

    val world =
        WorldCreator(WARMUP_WORLD_NAME)
            .type(WorldType.FLAT)
            .generatorSettings(VOID_GENERATOR_SETTINGS)
            .generateStructures(false)
            .createWorld()
            ?: return Outcome.Failed(
                WARMUP_WORLD_NAME,
                "the server refused to load the warmup world",
                System.currentTimeMillis() - start,
            )
    val unloaded = plugin.server.unloadWorld(world, false)
    deleteRecursively(dir)
    if (!unloaded) {
      return Outcome.Failed(
          WARMUP_WORLD_NAME,
          "could not unload the warmup world",
          System.currentTimeMillis() - start,
      )
    }
    return Outcome.Ok(WARMUP_WORLD_NAME, System.currentTimeMillis() - start)
  }

  private fun deleteRecursively(dir: File) {
    if (!dir.exists()) return
    dir.listFiles()?.forEach { child ->
      if (child.isDirectory) deleteRecursively(child) else child.delete()
    }
    dir.delete()
  }
}

/** Mirror of the CLI's `WorldRefreshRequest` — same JSON shape, no cross-module dependency. */
data class HostWorldRefreshRequest(val requestId: String = "", val worldName: String = "")

/** Mirror of the CLI's `WorldWarmupRequest`. */
data class HostWorldWarmupRequest(val requestId: String = "")

/** Mirror of the CLI's `WorldOpStatus`; serialized as the lowercase wire values. */
enum class HostWorldOpStatus {
  @SerializedName("ok") OK,
  @SerializedName("failed") FAILED,
}

/** Which world primitive a `worldReport` answers. Mirror of the CLI's `WorldOp`. */
enum class HostWorldOp {
  @SerializedName("refresh") REFRESH,
  @SerializedName("warmup") WARMUP,
}

/**
 * Mirror of the CLI's `WorldReport`, sent as a `worldReport` message. Answers both world
 * primitives, discriminated by [op]; [requestId] echoes the request so the CLI's waiter can discard
 * stale answers. [durationMs] is the companion-side cost of the world operation (the CLI times its
 * own wall clock separately, like the load path).
 */
data class HostWorldReport(
    val requestId: String,
    val status: HostWorldOpStatus,
    val op: HostWorldOp,
    val worldName: String,
    val durationMs: Long,
    val message: String? = null,
)
