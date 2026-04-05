package dev.paperplane.cli.devserver

internal enum class ReloadStrategy(val key: String) {
  HOTSWAP("hotswap"),
  DIRECTORY("directory"),
  JAR("jar"),
}
