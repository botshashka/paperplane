package dev.paperplane.cli.config

import com.charleskorn.kaml.InvalidPropertyValueException
import com.charleskorn.kaml.UnknownPropertyException
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlMap
import com.github.ajalt.clikt.core.ProgramResult
import dev.paperplane.cli.ui.TerminalUI
import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class PaperPlaneConfig(
    val server: ServerConfig = ServerConfig(),
    val dev: DevConfig = DevConfig(),
) {
  companion object {
    fun load(projectDir: File, ui: TerminalUI): PaperPlaneConfig {
      val configFile = File(projectDir, "paperplane.yml")
      if (!configFile.exists()) return PaperPlaneConfig()
      return try {
        Yaml.default.decodeFromString(configFile.readText())
      } catch (e: InvalidPropertyValueException) {
        val unknown = e.cause as? UnknownPropertyException
        if (unknown != null) {
          reportUnknownKey(ui, unknown)
        } else {
          ui.error("Invalid paperplane.yml: ${e.message}")
        }
        throw ProgramResult(1)
      } catch (e: UnknownPropertyException) {
        reportUnknownKey(ui, e)
        throw ProgramResult(1)
      }
    }

    /**
     * Serializes [config] back to `paperplane.yml` in [projectDir]. Used for reverse-sync of
     * machine-managed fields like `server.ops` after a dev session. Note: comments and custom
     * formatting in the existing file are not preserved — kaml does a round-trip serialization.
     */
    fun save(projectDir: File, config: PaperPlaneConfig) {
      val yaml =
          Yaml(configuration = YamlConfiguration(encodeDefaults = false, breakScalarsAt = 120))
      val text = yaml.encodeToString(config)
      File(projectDir, "paperplane.yml").writeText(text)
    }

    private fun reportUnknownKey(ui: TerminalUI, e: UnknownPropertyException) {
      ui.blank()
      ui.error("Unknown key '${e.propertyName}' in paperplane.yml")
      ui.status("Valid keys: ${e.validPropertyNames.sorted().joinToString(", ")}")
    }
  }
}

@Serializable
data class ServerConfig(
    val version: String? = null,
    @SerialName("jvm-args") val jvmArgs: List<String> = listOf("-Xmx2G"),
    /**
     * User overrides for `server.properties`. Merged on top of PaperPlane's defaults. Managed keys
     * (`server-port`, `accepts-transfers`, `online-mode`) cannot be overridden — they're required
     * for the dev server to function.
     */
    val properties: Map<String, String> = emptyMap(),
    /**
     * Player names to pre-op on the dev server. Written to `ops.json` on each `ppl dev`.
     * PaperPlane also auto-ops joining players, and their names are synced back into this list
     * on shutdown so they persist across `ppl clean` runs.
     */
    val ops: List<String> = emptyList(),
    /**
     * Names that must never be auto-opped. Takes precedence over [ops] (a name appearing in
     * both is never opped) and blocks the auto-op-on-join behavior as well as the reverse-sync
     * that otherwise grows [ops] organically. Use this to deop a player permanently: add them
     * here and they stay deopped across sessions.
     */
    @SerialName("op-banlist") val opBanlist: List<String> = emptyList(),
    /**
     * Passthrough overrides for `config/paper-global.yml`. Deep-merged on top of PaperPlane's
     * defaults at configure time (user wins on leaf conflicts). Leave empty to use defaults.
     * Paper's full schema is very large, so we don't enumerate keys here — users only set the
     * ones they care about.
     */
    @SerialName("paper-global") val paperGlobal: YamlMap? = null,
    /**
     * Passthrough overrides for `config/paper-world-defaults.yml`. Deep-merged the same way as
     * [paperGlobal].
     */
    @SerialName("paper-world-defaults") val paperWorldDefaults: YamlMap? = null,
)

@Serializable
enum class DevMode {
  @SerialName("hot-reload") HOT_RELOAD,
  @SerialName("blue-green") BLUE_GREEN,
  @SerialName("restart") RESTART,
}

@Serializable
data class DevConfig(
    @SerialName("debounce-ms") val debounceMs: Long = 2000,
    val mode: DevMode = DevMode.HOT_RELOAD,
    val jbr: String = "auto",
)
