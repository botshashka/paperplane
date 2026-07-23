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
    /**
     * Whether to attach the redefinition agent. Always true in production — it lives here, on the
     * launch identity, rather than as a parallel [PaperServerManager.start] argument, because that
     * is precisely what this type exists to prevent: an aspect of how a server is launched that a
     * recovery path could pass differently from startup. Only tests that launch throwaway real JVMs
     * without the CLI resources on the classpath turn it off.
     */
    val attachAgent: Boolean = true,
    /**
     * Package prefixes the agent records load-time CRCs for, passed as its `-javaagent` argument.
     * Empty records every class the JVM defines — correct, but it hashes all of Paper's boot on
     * every start to keep a few dozen entries anyone reads. A class outside these prefixes simply
     * has no load record, which the companion turns into a refusal and the CLI into a normal swap.
     */
    val recordedPackages: List<String> = emptyList(),
) {
  companion object {
    /**
     * JBR-only flag lifting class redefinition from body-only to structural (add/remove members).
     * The instant tier never uses that headroom (it is body-only by design, ADR 0005); the flag is
     * armed for the user's own tooling — an IDE debugger attached to a `dev.jbr` server gets
     * structural hotswap over JDWP.
     */
    const val ENHANCED_REDEFINITION = "-XX:+AllowEnhancedClassRedefinition"

    fun build(
        javaBin: String,
        isJbr: Boolean,
        baseJvmArgs: List<String>,
        recordedPackages: List<String> = emptyList(),
    ): LaunchSpec =
        LaunchSpec(
            javaBin = javaBin,
            isJbr = isJbr,
            jvmArgs =
                buildList {
                  addAll(baseJvmArgs)
                  if (isJbr) add(ENHANCED_REDEFINITION)
                },
            recordedPackages = recordedPackages,
        )

    /**
     * The packages the agent should record for a plugin whose main class is [mainClass]: its own
     * package plus the parent package, so helper classes in sibling packages
     * (`com.acme.plugin.Main` alongside `com.acme.util.Helper`) still get load records. Two
     * segments minimum — a one-segment prefix would re-admit half the runtime and give back the
     * saving. Empty for a main class in the default package, which records everything.
     */
    fun recordedPackagesFor(mainClass: String): List<String> {
      val pkg = mainClass.substringBeforeLast('.', "")
      if (pkg.isEmpty()) return emptyList()
      val parent = pkg.substringBeforeLast('.', "")
      return if (parent.contains('.')) listOf(parent) else listOf(pkg)
    }
  }
}
