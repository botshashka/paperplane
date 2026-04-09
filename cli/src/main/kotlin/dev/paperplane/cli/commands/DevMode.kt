package dev.paperplane.cli.commands

/**
 * The three dev-server modes supported by PaperPlane. Serialized to and from [value] in
 * `paperplane.yml` under `dev.mode`.
 *
 * Lifted out of [CreateCommand] so [InitCommand] and any future command can reference the same
 * constants instead of passing raw strings to [ProjectTemplates.paperplaneYml].
 */
internal enum class DevMode(val display: String, val description: String, val value: String) {
  HOT_RELOAD("Hot reload", "fastest iteration, reloads plugin in-place", "hot-reload"),
  BLUE_GREEN("Blue-green", "zero-downtime via Velocity proxy", "blue-green"),
  RESTART("Restart", "stop, rebuild, restart", "restart"),
}
