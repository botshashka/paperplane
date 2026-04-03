package dev.paperplane.cli.config

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import java.io.File

@Serializable
data class PaperPlaneConfig(
    val server: ServerConfig = ServerConfig(),
    val dev: DevConfig = DevConfig()
) {
    companion object {
        fun load(projectDir: File): PaperPlaneConfig {
            val configFile = File(projectDir, "paperplane.yml")
            if (!configFile.exists()) return PaperPlaneConfig()
            return Yaml.default.decodeFromString(configFile.readText())
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
    val companion: Boolean = true,
    @SerialName("verbose-server")
    val verboseServer: Boolean = false,
    @SerialName("debounce-ms")
    val debounceMs: Long = 2000,
    val mode: DevMode = DevMode.HOT_RELOAD,
    val jbr: String = "auto"
)
