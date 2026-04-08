package dev.paperplane.cli.config

import com.charleskorn.kaml.InvalidPropertyValueException
import com.charleskorn.kaml.UnknownPropertyException
import com.charleskorn.kaml.Yaml
import com.github.ajalt.clikt.core.ProgramResult
import dev.paperplane.cli.ui.TerminalUI
import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

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
