package dev.paperplane.cli.devserver.instant

import java.io.PrintWriter
import java.io.StringWriter
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.util.Textifier
import org.objectweb.asm.util.TraceMethodVisitor

/**
 * Grades one rebuild's change-set (baseline vs candidate) into a [RedefineRequirement] plus the
 * patch payload or the named escalation reasons.
 *
 * The rule set is a **whitelist**: only change shapes positively argued safe are admitted
 * (retained-method body edits; unannotated added/removed methods; new classes). Everything not
 * recognized escalates — a false "safe" verdict produces silently stale behavior, the exact sin
 * this tool positions against, while a false escalation merely costs one normal swap.
 *
 * The lifecycle gate lives here too: changes the JVM would happily redefine but that can never
 * take effect — new/changed annotated methods (registration-driven frameworks scan once), bodies
 * of `onEnable`/`onDisable`/`onLoad` on the plugin main class and of `<clinit>` (already ran,
 * never rerun), field changes (existing instances keep default-initialized state) — are UNSAFE
 * with a user-facing description of exactly why.
 */
class ChangeClassifier {

  fun classify(
      baseline: BuildCandidate,
      candidate: BuildCandidate,
      mainClass: String,
  ): InstantClassification {
    val escalations = mutableListOf<Escalation>()
    val patches = mutableListOf<ClassPatch>()
    val newClasses = mutableListOf<ClassPatch>()
    var requirement = RedefineRequirement.NONE

    fun raise(level: RedefineRequirement) {
      if (level > requirement) requirement = level
    }

    for (path in (baseline.resourceCrcs.keys + candidate.resourceCrcs.keys).sorted()) {
      if (baseline.resourceCrcs[path] != candidate.resourceCrcs[path]) {
        escalations +=
            Escalation(EscalationKind.RESOURCE_CHANGED, "resource $path ${changeWord(path, baseline, candidate)}")
        raise(RedefineRequirement.UNSAFE)
      }
    }

    for (fqcn in baseline.classes.keys.sorted()) {
      if (fqcn !in candidate.classes) {
        escalations += Escalation(EscalationKind.CLASS_REMOVED, "class ${simple(fqcn)} removed")
        raise(RedefineRequirement.UNSAFE)
      }
    }

    for (fqcn in candidate.classes.keys.sorted()) {
      val newBytes = candidate.classes.getValue(fqcn)
      val oldBytes = baseline.classes[fqcn]
      if (oldBytes == null) {
        newClasses += ClassPatch(fqcn, 0L, newBytes)
        raise(RedefineRequirement.ADDITIVE)
        continue
      }
      if (oldBytes.contentEquals(newBytes)) continue

      val verdict = analyzeClass(fqcn, oldBytes, newBytes, mainClass)
      escalations += verdict.escalations
      raise(verdict.level)
      if (
          verdict.level == RedefineRequirement.BODY_ONLY ||
              verdict.level == RedefineRequirement.ADDITIVE
      ) {
        patches += ClassPatch(fqcn, BuildCandidate.crc32(oldBytes), newBytes)
      }
    }

    // An UNSAFE change-set is never partially applied — drop the payload so no caller can.
    val unsafe = requirement == RedefineRequirement.UNSAFE
    return InstantClassification(
        requirement = requirement,
        patches = if (unsafe) emptyList() else patches,
        newClasses = if (unsafe) emptyList() else newClasses,
        escalations = escalations,
    )
  }

  // ── Per-class analysis ──────────────────────────────────────────────

  private class ClassVerdict(val level: RedefineRequirement, val escalations: List<Escalation>)

  private fun analyzeClass(
      fqcn: String,
      oldBytes: ByteArray,
      newBytes: ByteArray,
      mainClass: String,
  ): ClassVerdict {
    val oldNode = parse(oldBytes)
    val newNode = parse(newBytes)
    val name = simple(fqcn)
    if (oldNode == null || newNode == null) {
      return ClassVerdict(
          RedefineRequirement.UNSAFE,
          listOf(
              Escalation(EscalationKind.UNPARSEABLE_CLASS, "could not parse bytecode of $name")
          ),
      )
    }

    val escalations = mutableListOf<Escalation>()
    var level = RedefineRequirement.NONE
    fun raise(l: RedefineRequirement) {
      if (l > level) level = l
    }

    if (
        oldNode.superName != newNode.superName ||
            (oldNode.interfaces ?: emptyList<String>()) !=
                (newNode.interfaces ?: emptyList<String>()) ||
            (oldNode.permittedSubclasses ?: emptyList<String>()) !=
                (newNode.permittedSubclasses ?: emptyList<String>())
    ) {
      escalations +=
          Escalation(EscalationKind.HIERARCHY_CHANGE, "superclass or interfaces changed on $name")
      raise(RedefineRequirement.UNSAFE)
    }

    if (
        oldNode.access != newNode.access ||
            annotationsFingerprint(oldNode.visibleAnnotations, oldNode.invisibleAnnotations) !=
                annotationsFingerprint(newNode.visibleAnnotations, newNode.invisibleAnnotations)
    ) {
      escalations +=
          Escalation(
              EscalationKind.CLASS_DECLARATION_CHANGED,
              "class declaration (modifiers or annotations) changed on $name",
          )
      raise(RedefineRequirement.UNSAFE)
    }

    compareFields(oldNode, newNode, name, escalations, ::raise)
    compareMethods(oldNode, newNode, fqcn, name, mainClass, escalations, ::raise)

    // Adding a nested/anonymous class rewrites the outer class's InnerClasses/NestMembers
    // attributes; the stock JVM rejects redefinition on a nest-attribute change, so this needs
    // the ADDITIVE tier even when every retained body is untouched.
    if (nestFingerprint(oldNode) != nestFingerprint(newNode)) {
      raise(RedefineRequirement.ADDITIVE)
    }

    return ClassVerdict(level, escalations)
  }

  private fun compareFields(
      oldNode: ClassNode,
      newNode: ClassNode,
      name: String,
      escalations: MutableList<Escalation>,
      raise: (RedefineRequirement) -> Unit,
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
            fieldFingerprint(old) != fieldFingerprint(new) -> "changed"
            else -> continue
          }
      escalations += Escalation(EscalationKind.FIELD_CHANGE, "field $fieldName $what on $name")
      raise(RedefineRequirement.UNSAFE)
    }
  }

  private fun compareMethods(
      oldNode: ClassNode,
      newNode: ClassNode,
      fqcn: String,
      name: String,
      mainClass: String,
      escalations: MutableList<Escalation>,
      raise: (RedefineRequirement) -> Unit,
  ) {
    val oldMethods = oldNode.methods.associateBy { it.name + it.desc }
    val newMethods = newNode.methods.associateBy { it.name + it.desc }

    for (key in (oldMethods.keys + newMethods.keys).sorted()) {
      val old = oldMethods[key]
      val new = newMethods[key]
      when {
        old == null -> {
          val m = new!!
          if (hasAnnotations(m)) {
            escalations +=
                Escalation(
                    EscalationKind.ANNOTATED_METHOD_ADDED,
                    "new ${annotationLabel(m)} method ${m.name} on $name — " +
                        "a redefine can't activate it",
                )
            raise(RedefineRequirement.UNSAFE)
          } else {
            raise(RedefineRequirement.ADDITIVE)
          }
        }
        new == null -> {
          if (hasAnnotations(old)) {
            escalations +=
                Escalation(
                    EscalationKind.ANNOTATED_METHOD_REMOVED,
                    "removed ${annotationLabel(old)} method ${old.name} on $name",
                )
            raise(RedefineRequirement.UNSAFE)
          } else {
            raise(RedefineRequirement.ADDITIVE)
          }
        }
        else -> {
          if (methodDeclarationFingerprint(old) != methodDeclarationFingerprint(new)) {
            escalations +=
                Escalation(
                    EscalationKind.METHOD_DECLARATION_CHANGED,
                    "declaration of ${old.name} (modifiers, throws, or annotations) " +
                        "changed on $name",
                )
            raise(RedefineRequirement.UNSAFE)
          } else if (bodyFingerprint(old) != bodyFingerprint(new)) {
            when {
              old.name == "<clinit>" -> {
                escalations +=
                    Escalation(
                        EscalationKind.CLINIT_BODY,
                        "static initializer changed on $name — it only ever runs once",
                    )
                raise(RedefineRequirement.UNSAFE)
              }
              old.name in LIFECYCLE_METHODS &&
                  old.desc == "()V" &&
                  isPluginMain(fqcn, oldNode, mainClass) -> {
                escalations +=
                    Escalation(
                        EscalationKind.LIFECYCLE_BODY,
                        "${old.name} changed on $name — it already ran and won't rerun",
                    )
                raise(RedefineRequirement.UNSAFE)
              }
              else -> raise(RedefineRequirement.BODY_ONLY)
            }
          }
        }
      }
    }
  }

  // ── Fingerprints ────────────────────────────────────────────────────

  private fun parse(bytes: ByteArray): ClassNode? =
      try {
        ClassNode().also {
          ClassReader(bytes).accept(it, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        }
      } catch (_: Exception) {
        null
      }

  private fun bodyFingerprint(method: MethodNode): String {
    val printer = Textifier()
    val visitor = TraceMethodVisitor(printer)
    method.tryCatchBlocks?.forEach { it.accept(visitor) }
    method.instructions.accept(visitor)
    val out = StringWriter()
    printer.print(PrintWriter(out))
    return out.toString()
  }

  private fun methodDeclarationFingerprint(m: MethodNode): String =
      "${m.access}:${m.exceptions ?: emptyList<String>()}:" +
          annotationsFingerprint(m.visibleAnnotations, m.invisibleAnnotations) +
          ":" +
          parameterAnnotationsFingerprint(m)

  private fun fieldFingerprint(f: FieldNode): String =
      "${f.access}:${f.desc}:${f.value}:" +
          annotationsFingerprint(f.visibleAnnotations, f.invisibleAnnotations)

  private fun nestFingerprint(node: ClassNode): String =
      "${node.nestHostClass}:${(node.nestMembers ?: emptyList()).sorted()}:" +
          (node.innerClasses ?: emptyList())
              .map { "${it.name}:${it.outerName}:${it.innerName}:${it.access}" }
              .sorted()

  private fun annotationsFingerprint(vararg lists: List<AnnotationNode>?): String =
      lists
          .flatMap { it ?: emptyList() }
          .map { serializeAnnotation(it) }
          .sorted()
          .joinToString(";")

  private fun parameterAnnotationsFingerprint(m: MethodNode): String {
    val all = (m.visibleParameterAnnotations ?: emptyArray()) +
        (m.invisibleParameterAnnotations ?: emptyArray())
    return all.joinToString("|") { param ->
      (param ?: emptyList()).map { serializeAnnotation(it) }.sorted().joinToString(";")
    }
  }

  private fun serializeAnnotation(node: AnnotationNode): String =
      node.desc + "{" + serializeValue(node.values) + "}"

  private fun serializeValue(value: Any?): String =
      when (value) {
        null -> ""
        is AnnotationNode -> serializeAnnotation(value)
        is List<*> -> value.joinToString(",", "[", "]") { serializeValue(it) }
        is Array<*> -> value.joinToString(",", "[", "]") { serializeValue(it) }
        is BooleanArray -> value.contentToString()
        is ByteArray -> value.contentToString()
        is CharArray -> value.contentToString()
        is ShortArray -> value.contentToString()
        is IntArray -> value.contentToString()
        is LongArray -> value.contentToString()
        is FloatArray -> value.contentToString()
        is DoubleArray -> value.contentToString()
        else -> value.toString()
      }

  private fun hasAnnotations(m: MethodNode): Boolean =
      !m.visibleAnnotations.isNullOrEmpty() ||
          !m.invisibleAnnotations.isNullOrEmpty() ||
          m.visibleParameterAnnotations?.any { !it.isNullOrEmpty() } == true ||
          m.invisibleParameterAnnotations?.any { !it.isNullOrEmpty() } == true

  /** "@EventHandler" for the method's first annotation, "annotated" if only parameters carry one. */
  private fun annotationLabel(m: MethodNode): String {
    val first =
        m.visibleAnnotations?.firstOrNull() ?: m.invisibleAnnotations?.firstOrNull()
            ?: return "annotated"
    return "@" + first.desc.substringAfterLast('/').removeSuffix(";")
  }

  private fun isPluginMain(fqcn: String, node: ClassNode, mainClass: String): Boolean =
      fqcn == mainClass || node.superName == "org/bukkit/plugin/java/JavaPlugin"

  private fun simple(fqcn: String): String = fqcn.substringAfterLast('.')

  private fun changeWord(path: String, baseline: BuildCandidate, candidate: BuildCandidate): String =
      when {
        path !in baseline.resourceCrcs -> "added"
        path !in candidate.resourceCrcs -> "removed"
        else -> "changed"
      }

  private companion object {
    val LIFECYCLE_METHODS = setOf("onEnable", "onDisable", "onLoad")
  }
}
