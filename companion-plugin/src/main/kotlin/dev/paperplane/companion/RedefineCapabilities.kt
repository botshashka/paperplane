package dev.paperplane.companion

import com.google.gson.annotations.SerializedName
import java.lang.instrument.Instrumentation

/**
 * Mirror of the CLI's `RedefineCapability`, serialized as the lowercase wire values. Body-only is
 * the ceiling by design (ADR 0005), so the only question a JVM answers is whether the agent premain
 * ran.
 */
enum class HostRedefineCapability {
  /** No instrumentation agent — nothing can be redefined. */
  @SerializedName("none") NONE,

  /** Agent present: method-body-only redefinition. */
  @SerializedName("body-only") BODY_ONLY,
}

/**
 * Detects what this JVM can redefine, reported to the CLI in the welcome handshake so the instant
 * lane knows whether patching is possible on this live server at all.
 */
object RedefineCapabilities {
  fun detect(
      instrumentation: Instrumentation? = AgentAccess.instrumentation()
  ): HostRedefineCapability =
      if (instrumentation == null) HostRedefineCapability.NONE else HostRedefineCapability.BODY_ONLY
}
