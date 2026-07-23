package dev.paperplane.cli.devserver.instant

import com.google.gson.annotations.SerializedName

/**
 * What the live server's JVM can redefine in place. Reported by the companion in the socket
 * `welcome` message (it knows at enable time whether the agent premain ran) and cached per
 * connection — capability is a property of the running JVM, so a leak-restart or engine change
 * re-negotiates it naturally.
 *
 * Mirror of the companion's `HostRedefineCapability`; travels as the lowercase wire values below.
 * Body-only is the ceiling by design (ADR 0005): the only question a JVM answers is whether the
 * agent is present.
 */
internal enum class RedefineCapability {
  /** No instrumentation agent — nothing can be redefined. */
  @SerializedName("none") NONE,

  /** Agent present: method-body-only redefinition. */
  @SerializedName("body-only") BODY_ONLY,
}

/**
 * What a rebuild's change-set needs from the JVM to be applied in place. Computed by
 * [ChangeClassifier] as the maximum over all changed classes plus resource changes. The fast lane
 * patches iff the requirement is BODY_ONLY and the live JVM reports the capability — anything else
 * falls through to the active mode's full swap path with a named reason.
 */
internal enum class RedefineRequirement {
  /** Output changed on disk but nothing observable changed (debug info only). Do nothing. */
  NONE,

  /** Only retained method bodies changed. */
  BODY_ONLY,

  /**
   * Not safely patchable — a change redefinition can't activate (lifecycle-blind), can't express
   * (hierarchy/fields/members/new classes), or that we refuse to vouch for. Always escalates.
   */
  UNSAFE,
}
