package dev.paperplane.cli.config

import com.charleskorn.kaml.InvalidPropertyValueException
import com.charleskorn.kaml.UnknownPropertyException
import com.charleskorn.kaml.Yaml
import dev.paperplane.cli.ui.TerminalUI
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import java.io.File
import kotlin.system.exitProcess

@Serializable
data class PaperPlaneConfig(
    val server: ServerConfig = ServerConfig(),
    val dev: DevConfig = DevConfig()
) {
    companion object {
        fun load(projectDir: File): PaperPlaneConfig {
            val configFile = File(projectDir, "paperplane.yml")
            if (!configFile.exists()) return PaperPlaneConfig()
            return try {
                Yaml.default.decodeFromString(configFile.readText())
            } catch (e: InvalidPropertyValueException) {
                val unknown = e.cause as? UnknownPropertyException
                if (unknown != null) {
                    reportUnknownKey(unknown)
                } else {
                    TerminalUI.error("Invalid paperplane.yml: ${e.message}")
                }
                exitProcess(1)
            } catch (e: UnknownPropertyException) {
                reportUnknownKey(e)
                exitProcess(1)
            }
        }

        private fun reportUnknownKey(e: UnknownPropertyException) {
            TerminalUI.blank()
            TerminalUI.error("Unknown key '${e.propertyName}' in paperplane.yml")
            TerminalUI.status("Valid keys: ${e.validPropertyNames.sorted().joinToString(", ")}")
        }
    }
}

@Serializable
data class ServerConfig(
    val version: String? = null,
    @SerialName("jvm-args")
    val jvmArgs: List<String> = listOf("-Xmx2G")
)

@Serializable
enum class DevMode {
    @SerialName("hot-reload") HOT_RELOAD,
    @SerialName("blue-green") BLUE_GREEN,
    @SerialName("restart") RESTART
}

@Serializable
data class DevConfig(
    @SerialName("debounce-ms")
    val debounceMs: Long = 2000,
    val mode: DevMode = DevMode.HOT_RELOAD,
    val jbr: String = "auto"
)
