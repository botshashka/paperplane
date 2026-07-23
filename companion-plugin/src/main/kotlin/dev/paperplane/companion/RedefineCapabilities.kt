package dev.paperplane.companion

import com.google.gson.annotations.SerializedName
import java.lang.instrument.Instrumentation
import java.lang.management.ManagementFactory

/**
 * Mirror of the CLI's `RedefineCapability`, serialized as the lowercase wire values. One value, not
 * a boolean pair: `{agent, enhanced}` could encode `agent=false, enhanced=true`, a state that
 * cannot exist, and forced the CLI to re-derive the tier from two flags.
 */
enum class HostRedefineCapability {
  /** No instrumentation agent — nothing can be redefined. */
  @SerializedName("none") NONE,

  /** Stock JVM + agent: method-body-only redefinition. */
  @SerializedName("body-only") BODY_ONLY,

  /** JBR with enhanced redefinition armed + agent: added/removed methods and new classes too. */
  @SerializedName("additive") ADDITIVE,
}

/**
 * Detects what this JVM can redefine, reported to the CLI in the welcome handshake so the instant
 * classifier knows its ceiling per live server.
 *
 * [HostRedefineCapability.ADDITIVE] requires the JBR vendor check AND the actual
 * `-XX:+AllowEnhancedClassRedefinition` launch flag — vendor alone over-reports (JBR without the
 * flag behaves like a stock JVM and would turn every structural patch into a JVM veto round-trip).
 */
object RedefineCapabilities {
  const val ENHANCED_FLAG = "-XX:+AllowEnhancedClassRedefinition"

  fun detect(
      instrumentation: Instrumentation? = AgentAccess.instrumentation(),
      vendor: String = System.getProperty("java.vendor", ""),
      vmName: String = System.getProperty("java.vm.name", ""),
      inputArguments: List<String> = ManagementFactory.getRuntimeMXBean().inputArguments,
  ): HostRedefineCapability {
    if (instrumentation == null) return HostRedefineCapability.NONE
    val jbr =
        vendor.contains("JetBrains", ignoreCase = true) || vmName.contains("JBR", ignoreCase = true)
    val flagged = inputArguments.any { it == ENHANCED_FLAG }
    return if (jbr && flagged) HostRedefineCapability.ADDITIVE else HostRedefineCapability.BODY_ONLY
  }
}
