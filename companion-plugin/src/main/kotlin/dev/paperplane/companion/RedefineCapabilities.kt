package dev.paperplane.companion

import java.lang.instrument.Instrumentation
import java.lang.management.ManagementFactory

/**
 * Detects what this JVM can redefine, reported to the CLI in the welcome handshake so the instant
 * classifier knows its ceiling per live server.
 *
 * [Capability.enhanced] requires the JBR vendor check AND the actual
 * `-XX:+AllowEnhancedClassRedefinition` launch flag — vendor alone over-reports (JBR without the
 * flag behaves like a stock JVM and would turn every structural patch into a JVM veto round-trip).
 */
object RedefineCapabilities {
  const val ENHANCED_FLAG = "-XX:+AllowEnhancedClassRedefinition"

  data class Capability(val agent: Boolean, val enhanced: Boolean)

  fun detect(
      instrumentation: Instrumentation? = AgentAccess.instrumentation(),
      vendor: String = System.getProperty("java.vendor", ""),
      vmName: String = System.getProperty("java.vm.name", ""),
      inputArguments: List<String> = ManagementFactory.getRuntimeMXBean().inputArguments,
  ): Capability {
    val agent = instrumentation != null
    val jbr =
        vendor.contains("JetBrains", ignoreCase = true) || vmName.contains("JBR", ignoreCase = true)
    val flagged = inputArguments.any { it == ENHANCED_FLAG }
    return Capability(agent = agent, enhanced = agent && jbr && flagged)
  }
}
