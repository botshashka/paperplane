package dev.paperplane.cli.devserver.instant

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode

/**
 * Grades one rebuild's change-set (baseline vs candidate) into a [RedefineRequirement] plus the
 * patch payload or the named escalation reasons. [Fingerprints] decides what counts as a
 * difference; this decides what a difference means.
 *
 * The rule set is a **whitelist**: only change shapes positively argued safe are admitted
 * (retained-method body edits; unannotated added/removed methods; new classes). Everything not
 * recognized escalates — a false "safe" verdict produces silently stale behavior, the exact sin
 * this tool positions against, while a false escalation merely costs one normal swap.
 *
 * That posture is enforced structurally rather than by enumeration: when every fingerprint compares
 * equal, [Fingerprints.canonicalBytes] re-serializes both sides and escalates if they still differ,
 * so a delta the fingerprints don't model can never fall through to NONE. NONE therefore means
 * exactly one thing — a debug-only diff (line numbers, local names), which the server need not be
 * told about.
 *
 * The lifecycle gate lives here too: changes the JVM would happily redefine but that can never take
 * effect — new/changed annotated methods (registration-driven frameworks scan once), bodies of
 * `onEnable`/`onDisable`/`onLoad` on the plugin main class, of run-once constructors, and of
 * `<clinit>` (already ran, never rerun), field changes (existing instances keep default-initialized
 * state) — are UNSAFE with a user-facing description of exactly why.
 */
class ChangeClassifier {

  fun classify(
      baseline: BuildCandidate,
      candidate: BuildCandidate,
      mainClass: String,
  ): InstantClassification {
    val verdict = Verdict()
    val patches = mutableListOf<ClassPatch>()
    val newClasses = mutableListOf<ClassPatch>()

    compareResources(baseline, candidate, verdict)
    compareRemovedClasses(baseline, candidate, verdict)
    compareClasses(baseline, candidate, mainClass, verdict, patches, newClasses)

    // An UNSAFE change-set is never partially applied — drop the payload so no caller can.
    val unsafe = verdict.level == RedefineRequirement.UNSAFE
    return InstantClassification(
        requirement = verdict.level,
        patches = if (unsafe) emptyList() else patches,
        newClasses = if (unsafe) emptyList() else newClasses,
        escalations = verdict.escalations,
        additiveNotes = verdict.additiveNotes,
    )
  }

  // ── Change-set traversal ────────────────────────────────────────────

  private fun compareResources(
      baseline: BuildCandidate,
      candidate: BuildCandidate,
      verdict: Verdict,
  ) {
    for (path in (baseline.resourceCrcs.keys + candidate.resourceCrcs.keys).sorted()) {
      if (baseline.resourceCrcs[path] != candidate.resourceCrcs[path]) {
        verdict.escalate(
            EscalationKind.RESOURCE_CHANGED,
            "resource $path ${changeWord(path, baseline, candidate)}",
        )
      }
    }
  }

  private fun compareRemovedClasses(
      baseline: BuildCandidate,
      candidate: BuildCandidate,
      verdict: Verdict,
  ) {
    for (fqcn in baseline.classes.keys.sorted()) {
      if (fqcn !in candidate.classes) {
        verdict.escalate(EscalationKind.CLASS_REMOVED, "class ${simple(fqcn)} removed")
      }
    }
  }

  private fun compareClasses(
      baseline: BuildCandidate,
      candidate: BuildCandidate,
      mainClass: String,
      verdict: Verdict,
      patches: MutableList<ClassPatch>,
      newClasses: MutableList<ClassPatch>,
  ) {
    for (fqcn in candidate.classes.keys.sorted()) {
      val newBytes = candidate.classes.getValue(fqcn)
      val oldBytes = baseline.classes[fqcn]
      if (oldBytes == null) {
        newClasses += ClassPatch(fqcn, 0L, newBytes)
        verdict.additive("new class ${simple(fqcn)}")
        continue
      }
      if (oldBytes.contentEquals(newBytes)) continue

      val classVerdict = analyzeClass(fqcn, oldBytes, newBytes, mainClass)
      verdict.merge(classVerdict)
      if (
          classVerdict.level == RedefineRequirement.BODY_ONLY ||
              classVerdict.level == RedefineRequirement.ADDITIVE
      ) {
        patches += ClassPatch(fqcn, baseline.classCrc(fqcn), newBytes)
      }
    }
  }

  // ── Per-class analysis ──────────────────────────────────────────────

  private fun analyzeClass(
      fqcn: String,
      oldBytes: ByteArray,
      newBytes: ByteArray,
      mainClass: String,
  ): Verdict {
    val verdict = Verdict()
    val name = simple(fqcn)
    val oldNode = Fingerprints.parse(oldBytes)
    val newNode = Fingerprints.parse(newBytes)
    if (oldNode == null || newNode == null) {
      verdict.escalate(EscalationKind.UNPARSEABLE_CLASS, "could not parse bytecode of $name")
      return verdict
    }

    compareHierarchy(oldNode, newNode, name, verdict)
    compareDeclaration(oldNode, newNode, name, verdict)
    compareFields(oldNode, newNode, name, verdict)
    compareMethods(oldNode, newNode, fqcn, name, mainClass, verdict)
    compareNest(oldNode, newNode, name, verdict)

    if (verdict.level == RedefineRequirement.NONE) {
      checkUnmodeled(oldNode, newNode, name, verdict)
    }
    return verdict
  }

  private fun compareHierarchy(
      oldNode: ClassNode,
      newNode: ClassNode,
      name: String,
      verdict: Verdict,
  ) {
    val changed =
        oldNode.superName != newNode.superName ||
            (oldNode.interfaces ?: emptyList<String>()) !=
                (newNode.interfaces ?: emptyList<String>()) ||
            (oldNode.permittedSubclasses ?: emptyList<String>()) !=
                (newNode.permittedSubclasses ?: emptyList<String>())
    if (changed) {
      verdict.escalate(
          EscalationKind.HIERARCHY_CHANGE,
          "superclass or interfaces changed on $name",
      )
    }
  }

  private fun compareDeclaration(
      oldNode: ClassNode,
      newNode: ClassNode,
      name: String,
      verdict: Verdict,
  ) {
    val changed =
        oldNode.access != newNode.access ||
            oldNode.signature != newNode.signature ||
            Fingerprints.annotations(oldNode.visibleAnnotations, oldNode.invisibleAnnotations) !=
                Fingerprints.annotations(newNode.visibleAnnotations, newNode.invisibleAnnotations)
    if (changed) {
      verdict.escalate(
          EscalationKind.CLASS_DECLARATION_CHANGED,
          "class declaration (modifiers or annotations) changed on $name",
      )
    }
  }

  private fun compareFields(
      oldNode: ClassNode,
      newNode: ClassNode,
      name: String,
      verdict: Verdict,
  ) {
    val oldFields = oldNode.fields.associateBy { it.name }
    val newFields = newNode.fields.associateBy { it.name }
    for (fieldName in (oldFields.keys + newFields.keys).sorted()) {
      val old = oldFields[fieldName]
      val new = newFields[fieldName]
      val what =
          when {
            old == null -> "added"
            new == null -> "removed"
            Fingerprints.field(old) != Fingerprints.field(new) -> "changed"
            else -> continue
          }
      verdict.escalate(EscalationKind.FIELD_CHANGE, "field $fieldName $what on $name")
    }
  }

  private fun compareNest(
      oldNode: ClassNode,
      newNode: ClassNode,
      name: String,
      verdict: Verdict,
  ) {
    // Adding a nested/anonymous class rewrites the outer class's InnerClasses/NestMembers
    // attributes; the stock JVM rejects redefinition on a nest-attribute change, so this needs
    // the ADDITIVE tier even when every retained body is untouched.
    if (Fingerprints.nest(oldNode) != Fingerprints.nest(newNode)) {
      verdict.additive("nested-class change on $name")
    }
  }

  private fun checkUnmodeled(
      oldNode: ClassNode,
      newNode: ClassNode,
      name: String,
      verdict: Verdict,
  ) {
    val oldCanonical = Fingerprints.canonicalBytes(oldNode)
    val newCanonical = Fingerprints.canonicalBytes(newNode)
    if (oldCanonical == null || newCanonical == null) {
      verdict.escalate(
          EscalationKind.UNPARSEABLE_CLASS,
          "could not re-serialize bytecode of $name",
      )
      return
    }
    if (!oldCanonical.contentEquals(newCanonical)) {
      verdict.escalate(
          EscalationKind.UNMODELED_CHANGE,
          "unrecognized bytecode change on $name — refusing to vouch for it",
      )
    }
  }

  // ── Method comparison ───────────────────────────────────────────────

  private fun compareMethods(
      oldNode: ClassNode,
      newNode: ClassNode,
      fqcn: String,
      name: String,
      mainClass: String,
      verdict: Verdict,
  ) {
    val oldMethods = oldNode.methods.associateBy { it.name + it.desc }
    val newMethods = newNode.methods.associateBy { it.name + it.desc }

    for (key in (oldMethods.keys + newMethods.keys).sorted()) {
      val old = oldMethods[key]
      val new = newMethods[key]
      when {
        old == null -> addedMethodVerdict(new!!, fqcn, name, newNode, mainClass, verdict)
        new == null -> removedMethodVerdict(old, name, verdict)
        else -> retainedMethodVerdict(old, new, fqcn, name, oldNode, mainClass, verdict)
      }
    }
  }

  /**
   * An added method is admissible only when nothing external needs to discover it. The run-once
   * gates apply here exactly as they do to retained bodies: a newly added `<clinit>` or `onLoad`
   * would be defined by the JVM and then never invoked.
   */
  private fun addedMethodVerdict(
      method: MethodNode,
      fqcn: String,
      name: String,
      node: ClassNode,
      mainClass: String,
      verdict: Verdict,
  ) {
    when {
      method.name == CLINIT ->
          verdict.escalate(
              EscalationKind.CLINIT_BODY,
              "static initializer added on $name — it only ever runs once",
          )
      isRunOnce(method, fqcn, node, mainClass) ->
          verdict.escalate(
              EscalationKind.LIFECYCLE_BODY,
              "${method.name} added on $name — it already ran and won't rerun",
          )
      Fingerprints.hasDeveloperAnnotations(method) ->
          verdict.escalate(
              EscalationKind.ANNOTATED_METHOD_ADDED,
              "new ${Fingerprints.annotationLabel(method)} method ${method.name} on $name — " +
                  "a redefine can't activate it",
          )
      else -> verdict.additive("method ${method.name} added on $name")
    }
  }

  private fun removedMethodVerdict(method: MethodNode, name: String, verdict: Verdict) {
    if (Fingerprints.hasDeveloperAnnotations(method)) {
      verdict.escalate(
          EscalationKind.ANNOTATED_METHOD_REMOVED,
          "removed ${Fingerprints.annotationLabel(method)} method ${method.name} on $name",
      )
    } else {
      verdict.additive("method ${method.name} removed on $name")
    }
  }

  private fun retainedMethodVerdict(
      old: MethodNode,
      new: MethodNode,
      fqcn: String,
      name: String,
      oldNode: ClassNode,
      mainClass: String,
      verdict: Verdict,
  ) {
    if (Fingerprints.methodDeclaration(old) != Fingerprints.methodDeclaration(new)) {
      verdict.escalate(
          EscalationKind.METHOD_DECLARATION_CHANGED,
          "declaration of ${old.name} (modifiers, throws, or annotations) changed on $name",
      )
      return
    }
    if (Fingerprints.body(old) == Fingerprints.body(new)) return
    if (old.name == CLINIT) {
      verdict.escalate(
          EscalationKind.CLINIT_BODY,
          "static initializer changed on $name — it only ever runs once",
      )
      return
    }
    if (isRunOnce(old, fqcn, oldNode, mainClass)) {
      verdict.escalate(
          EscalationKind.LIFECYCLE_BODY,
          "${old.name} changed on $name — it already ran and won't rerun",
      )
      return
    }
    verdict.raise(RedefineRequirement.BODY_ONLY)
  }

  // ── Run-once predicates ─────────────────────────────────────────────

  /**
   * Whether [method] can only ever have run once, so redefining its body is a silent no-op: the
   * plugin main's lifecycle callbacks and constructor (Bukkit builds one instance per load), and
   * the constructor of a singleton object (Kotlin's `object` builds `INSTANCE` in `<clinit>`).
   */
  private fun isRunOnce(
      method: MethodNode,
      fqcn: String,
      node: ClassNode,
      mainClass: String,
  ): Boolean {
    val onMain = isPluginMain(fqcn, node, mainClass)
    if (method.name in LIFECYCLE_METHODS && method.desc == "()V" && onMain) return true
    return method.name == INIT && (onMain || isSingletonObject(node))
  }

  private fun isPluginMain(fqcn: String, node: ClassNode, mainClass: String): Boolean =
      fqcn == mainClass || node.superName == JAVA_PLUGIN

  /** A `static final` field of the class's own type — the Kotlin `object` / singleton shape. */
  private fun isSingletonObject(node: ClassNode): Boolean {
    val selfDesc = "L${node.name};"
    return node.fields.any {
      it.desc == selfDesc &&
          (it.access and Opcodes.ACC_STATIC) != 0 &&
          (it.access and Opcodes.ACC_FINAL) != 0
    }
  }

  private fun simple(fqcn: String): String = fqcn.substringAfterLast('.')

  private fun changeWord(
      path: String,
      baseline: BuildCandidate,
      candidate: BuildCandidate,
  ): String =
      when {
        path !in baseline.resourceCrcs -> "added"
        path !in candidate.resourceCrcs -> "removed"
        else -> "changed"
      }

  /**
   * The running tally for one class or one whole change-set. Escalating always implies UNSAFE, so
   * [InstantClassification]'s "escalations non-empty iff UNSAFE" invariant holds by construction
   * rather than by every call site remembering to raise.
   */
  private class Verdict {
    var level = RedefineRequirement.NONE
      private set

    val escalations = mutableListOf<Escalation>()
    val additiveNotes = mutableListOf<String>()

    fun raise(l: RedefineRequirement) {
      if (l > level) level = l
    }

    fun escalate(kind: EscalationKind, description: String) {
      escalations += Escalation(kind, description)
      raise(RedefineRequirement.UNSAFE)
    }

    fun additive(note: String) {
      additiveNotes += note
      raise(RedefineRequirement.ADDITIVE)
    }

    fun merge(other: Verdict) {
      escalations += other.escalations
      additiveNotes += other.additiveNotes
      raise(other.level)
    }
  }

  private companion object {
    const val CLINIT = "<clinit>"
    const val INIT = "<init>"
    const val JAVA_PLUGIN = "org/bukkit/plugin/java/JavaPlugin"

    val LIFECYCLE_METHODS = setOf("onEnable", "onDisable", "onLoad")
  }
}
