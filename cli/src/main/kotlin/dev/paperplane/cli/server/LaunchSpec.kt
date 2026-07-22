package dev.paperplane.cli.server

/**
 * The complete JVM launch identity shared by every Paper server a dev session starts.
 *
 * Built exactly once per session (see `DevSession.launchSpec`) and passed to every
 * [PaperServerManager.start] call — initial startup, fix recovery, leak restart, awaiting-fix cold
 * start, blue-green standby, pre-warm and fixed-server alike. This makes the "mirror the args"
 * invariant structural: no recovery or restart path can silently downgrade a server to a plain JVM
 * without the redefinition agent or the JBR flags, because there is exactly one place the launch
 * arguments are assembled.
 */
data class LaunchSpec(
    val javaBin: String,
    val isJbr: Boolean,
    val jvmArgs: List<String>,
) {
  companion object {
    /**
     * Opens `java.net` to reflective access. The instant tier's new-class splice calls
     * `URLClassLoader.addURL` reflectively on Paper's jar-backed plugin classloader, which JDK 17+
     * rejects with `InaccessibleObjectException` unless this open is present at launch.
     */
    const val ADD_OPENS_JAVA_NET = "--add-opens=java.base/java.net=ALL-UNNAMED"

    /**
     * JBR-only flag lifting class redefinition from body-only to structural (add/remove members).
     */
    const val ENHANCED_REDEFINITION = "-XX:+AllowEnhancedClassRedefinition"

    fun build(javaBin: String, isJbr: Boolean, baseJvmArgs: List<String>): LaunchSpec =
        LaunchSpec(
            javaBin = javaBin,
            isJbr = isJbr,
            jvmArgs =
                buildList {
                  addAll(baseJvmArgs)
                  add(ADD_OPENS_JAVA_NET)
                  if (isJbr) add(ENHANCED_REDEFINITION)
                },
        )
  }
}
