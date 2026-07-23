package dev.paperplane.cli.devserver.instant

import com.google.gson.annotations.SerializedName

/**
 * What the live server's JVM can redefine in place. Reported by the companion in the socket
 * `welcome` message (it knows at enable time whether the agent premain ran and whether enhanced
 * redefinition is actually armed) and cached per connection — capability is a property of the
 * running JVM, so a leak-restart or engine change re-negotiates it naturally.
 *
 * Mirror of the companion's `HostRedefineCapability`; travels as the lowercase wire values below.
 */
internal enum class RedefineCapability {
  /** No instrumentation agent — nothing can be redefined. */
  @SerializedName("none") NONE,

  /** Stock JVM + agent: method-body-only redefinition. The floor, available everywhere. */
  @SerializedName("body-only") BODY_ONLY,

  /**
   * JBR with `-XX:+AllowEnhancedClassRedefinition` + agent: additionally admits added/removed
   * methods and new classes. Opt-in by construction — reaching this tier requires explicitly
   * configuring JBR (`dev.jbr: on` or a JBR path).
   */
  @SerializedName("additive") ADDITIVE,
}

/**
 * What a rebuild's change-set needs from the JVM to be applied in place. Computed by
 * [ChangeClassifier] as the maximum over all changed classes plus resource changes. The fast lane
 * patches iff `requirement <= capability` (ordinal order below is the lattice) — anything else
 * falls through to the active mode's full swap path with a named reason.
 */
internal enum class RedefineRequirement {
  /** Output changed on disk but nothing observable changed (debug info only). Do nothing. */
  NONE,

  /** Only retained method bodies changed. */
  BODY_ONLY,

  /** Additionally needs added/removed methods and/or new classes (JBR territory). */
  ADDITIVE,

  /**
   * Not safely redefinable on any JVM — a change redefinition can't activate (lifecycle-blind),
   * can't express (hierarchy/fields), or that we refuse to vouch for. Always escalates.
   */
  UNSAFE,
}
