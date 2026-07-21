package dev.paperplane.cli.devserver.instant

/**
 * One class the fast lane wants to redefine (or define, for new classes) in the live server.
 *
 * [expectedLoadedCrc32] is the CRC32 of the bytes the CLI believes the server is currently
 * running (the baseline); the companion verifies it against the actually-loaded bytes before
 * redefining, so CLI-state drift is a refusal, never a silent mispatch. Zero for new classes
 * (nothing is loaded yet).
 */
class ClassPatch(
    val fqcn: String,
    val expectedLoadedCrc32: Long,
    val bytes: ByteArray,
)

/**
 * Why a change-set cannot be patched in place. Every UNSAFE verdict carries at least one of
 * these; the first is surfaced to the user verbatim ("Instant: <description> — full swap"), which
 * is the honest-reporting half of the lifecycle gate: the tool always says *why* it escalated.
 */
enum class EscalationKind {
  /** New method carrying any annotation — registration-driven frameworks would never see it. */
  ANNOTATED_METHOD_ADDED,

  /** Removed method carrying any annotation — its registration would keep firing stale. */
  ANNOTATED_METHOD_REMOVED,

  /** Access/exceptions/annotations changed on a retained method (e.g. @EventHandler priority). */
  METHOD_DECLARATION_CHANGED,

  /** onEnable/onDisable/onLoad body changed on the plugin main class — already ran, never reruns. */
  LIFECYCLE_BODY,

  /** Static initializer body changed — runs exactly once per class, ever. */
  CLINIT_BODY,

  /** Field added/removed/changed — existing instances would carry default-initialized state. */
  FIELD_CHANGE,

  /** Superclass, interfaces, or permitted subclasses changed. */
  HIERARCHY_CHANGE,

  /** Class-level access flags or annotations changed. */
  CLASS_DECLARATION_CHANGED,

  /** A class disappeared from the build output. */
  CLASS_REMOVED,

  /** A resource (config.yml, generated plugin.yml, …) changed — redefinition can't re-copy it. */
  RESOURCE_CHANGED,

  /** Bytes on one side failed to parse — refuse to vouch for anything. */
  UNPARSEABLE_CLASS,
}

/** A single named escalation reason; [description] is user-facing and self-contained. */
data class Escalation(val kind: EscalationKind, val description: String)

/**
 * The classifier's verdict for one rebuild: the requirement level (max over classes + resources),
 * the patches to apply if the lane runs, and the named reasons if it must not.
 *
 * Invariants: [escalations] is non-empty iff [requirement] is UNSAFE; [patches]/[newClasses] are
 * only meaningful below UNSAFE (an UNSAFE change-set is never partially applied). [additiveNotes]
 * names what pushed the requirement to ADDITIVE ("new class Foo", "method added on Bar") so a
 * capability shortfall on a stock JVM can escalate with a reason as specific as an UNSAFE one.
 */
class InstantClassification(
    val requirement: RedefineRequirement,
    val patches: List<ClassPatch>,
    val newClasses: List<ClassPatch>,
    val escalations: List<Escalation>,
    val additiveNotes: List<String> = emptyList(),
)
