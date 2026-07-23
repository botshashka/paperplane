package dev.paperplane.cli.devserver.instant

import java.io.PrintWriter
import java.io.StringWriter
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.util.Textifier
import org.objectweb.asm.util.TraceMethodVisitor

/**
 * Structural identity for bytecode: given two versions of a class member, decide whether they are
 * the same *shape*. Pure computation with no policy — [ChangeClassifier] owns what a difference
 * means; this owns only what counts as a difference.
 *
 * Everything here reads a tree parsed by [parse], i.e. with debug information already discarded, so
 * line numbers and local-variable names never register as change.
 */
internal object Fingerprints {

  fun parse(bytes: ByteArray): ClassNode? =
      try {
        ClassNode().also {
          ClassReader(bytes).accept(it, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        }
      } catch (_: Exception) {
        null
      }

  /**
   * The debug-stripped tree re-serialized to a canonical form. Two classes whose canonical bytes
   * match differ only in what [parse] already discarded, so a NONE verdict on them is truthful;
   * anything else is a delta no fingerprint here models, and must escalate.
   */
  fun canonicalBytes(node: ClassNode): ByteArray? =
      try {
        ClassWriter(0).also { node.accept(it) }.toByteArray()
      } catch (_: Exception) {
        null
      }

  /**
   * The class's structural surface serialized without method bodies (SKIP_CODE): everything the
   * fingerprints must have modeled for a BODY_ONLY verdict to be trustworthy. Bodies differ by
   * definition on that path, so [canonicalBytes] cannot serve as its backstop.
   *
   * SKIP_CODE is exactly the right filter because the `Code` attribute — instructions, try/catch
   * ranges, StackMapTable, maxs, local-variable type annotations — is precisely the surface
   * BODY_ONLY licenses to differ. Everything else survives: method/return/parameter annotations
   * (type annotations included), `annotationDefault`, `exceptions`, signatures, record components.
   * `BootstrapMethods` is emitted only for visited invokedynamic instructions, so it drops from
   * both sides symmetrically.
   *
   * Re-parsed from the original bytes rather than reusing a [parse] tree: the caller's nodes are
   * live inputs to the rest of the comparison and must not be mutated or re-visited.
   */
  fun structuralBytes(bytes: ByteArray): ByteArray? =
      try {
        val node = ClassNode()
        ClassReader(bytes)
            .accept(
                node,
                ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
            )
        ClassWriter(0).also { node.accept(it) }.toByteArray()
      } catch (_: Exception) {
        null
      }

  fun body(method: MethodNode): String {
    val printer = Textifier()
    val visitor = TraceMethodVisitor(printer)
    method.tryCatchBlocks?.forEach { it.accept(visitor) }
    method.instructions.accept(visitor)
    val out = StringWriter()
    printer.print(PrintWriter(out))
    return out.toString()
  }

  fun methodDeclaration(m: MethodNode): String =
      "${m.access}:${m.exceptions ?: emptyList<String>()}:${m.signature}:" +
          "${m.parameters?.map { "${it.access}:${it.name}" } ?: emptyList<String>()}:" +
          serializeValue(m.annotationDefault) +
          ":" +
          annotations(m.visibleAnnotations, m.invisibleAnnotations) +
          ":" +
          parameterAnnotations(m)

  fun field(f: FieldNode): String =
      "${f.access}:${f.desc}:${f.signature}:${f.value}:" +
          annotations(f.visibleAnnotations, f.invisibleAnnotations)

  fun nest(node: ClassNode): String =
      "${node.nestHostClass}:${(node.nestMembers ?: emptyList()).sorted()}:" +
          (node.innerClasses ?: emptyList())
              .map { "${it.name}:${it.outerName}:${it.innerName}:${it.access}" }
              .sorted()

  /**
   * Visible and invisible annotations are fingerprinted separately: merging them would make a
   * RUNTIME↔CLASS retention change invisible, and retention is exactly what decides whether a
   * framework can see the annotation at runtime.
   */
  fun annotations(visible: List<AnnotationNode>?, invisible: List<AnnotationNode>?): String =
      "V:" + serializeAll(visible) + "|I:" + serializeAll(invisible)

  fun developerAnnotations(list: List<AnnotationNode>?): List<AnnotationNode> =
      (list ?: emptyList()).filterNot { isCompilerEmitted(it.desc) }

  fun developerAnnotations(m: MethodNode): List<AnnotationNode> =
      developerAnnotations(m.visibleAnnotations) + developerAnnotations(m.invisibleAnnotations)

  /**
   * Whether anything the developer wrote is attached to [m], on the method or on its parameters.
   */
  fun hasDeveloperAnnotations(m: MethodNode): Boolean {
    if (developerAnnotations(m).isNotEmpty()) return true
    val params =
        (m.visibleParameterAnnotations ?: emptyArray()) +
            (m.invisibleParameterAnnotations ?: emptyArray())
    return params.any { developerAnnotations(it).isNotEmpty() }
  }

  /**
   * "@EventHandler" for the first developer annotation, "annotated" if only parameters carry one.
   */
  fun annotationLabel(m: MethodNode): String {
    val first = developerAnnotations(m).firstOrNull() ?: return "annotated"
    return "@" + first.desc.substringAfterLast('/').removeSuffix(";").ifEmpty { first.desc }
  }

  private fun parameterAnnotations(m: MethodNode): String {
    fun render(lists: Array<List<AnnotationNode>?>?) =
        (lists ?: emptyArray()).joinToString("|") { serializeAll(it) }
    return "V:" +
        render(m.visibleParameterAnnotations) +
        "|I:" +
        render(m.invisibleParameterAnnotations)
  }

  private fun serializeAll(list: List<AnnotationNode>?): String =
      developerAnnotations(list).map { serializeAnnotation(it) }.sorted().joinToString(";")

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

  /**
   * Annotation descriptors the compiler emits rather than the developer writing them. No
   * registration-driven framework dispatches on these, and treating them as developer intent makes
   * the tier unusable for Kotlin: kotlinc stamps `@kotlin.Metadata` on every class (its `d1`/`d2`
   * encode the declaration list, so adding any member rewrites it) and `@NotNull`/`@Nullable` on
   * every reference-typed parameter and return.
   *
   * Excluding them from the fingerprints never weakens the payload — the full new class bytes still
   * ship, so a redefined class carries its new metadata.
   */
  private val COMPILER_ANNOTATION_PREFIXES =
      listOf(
          "Lkotlin/Metadata;",
          "Lkotlin/jvm/internal/",
          "Lorg/jetbrains/annotations/",
          "Ljavax/annotation/",
          "Lorg/checkerframework/",
          "Ljava/lang/Deprecated;",
          "Ljava/lang/SuppressWarnings;",
          "Ljava/lang/SafeVarargs;",
          "Ljava/lang/FunctionalInterface;",
      )

  private fun isCompilerEmitted(desc: String): Boolean = COMPILER_ANNOTATION_PREFIXES.any {
    desc.startsWith(it)
  }
}
