package dev.paperplane.cli.devserver.instant

import dev.paperplane.cli.testing.BytecodeFixtures
import dev.paperplane.cli.testing.BytecodeFixtures.MethodSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.objectweb.asm.Opcodes

class ChangeClassifierTest {

  private val classifier = ChangeClassifier()

  private val eventHandler = "Lorg/bukkit/event/EventHandler;"
  private val mainClass = "com.example.MainPlugin"

  private fun candidate(
      classes: Map<String, ByteArray>,
      resources: Map<String, Long> = emptyMap(),
  ) = BuildCandidate(classes, resources)

  private fun classify(
      old: Map<String, ByteArray>,
      new: Map<String, ByteArray>,
      oldResources: Map<String, Long> = emptyMap(),
      newResources: Map<String, Long> = emptyMap(),
  ) = classifier.classify(candidate(old, oldResources), candidate(new, newResources), mainClass)

  private fun kinds(result: InstantClassification) = result.escalations.map { it.kind }.toSet()

  // ── Output layout ───────────────────────────────────────────────────

  @Test
  fun `moved output directories escalate even when every class byte matches`() {
    // A build-config edit relocates the output dirs; the next capture reads a different tree.
    // Without the layout gate, unchanged classes would all read as absent from the baseline and
    // ship as phantom "new classes" the companion no-ops on while the CLI reports them patched.
    val bytes =
        BytecodeFixtures.generateClass(
            methods = listOf(MethodSpec("tick", body = BytecodeFixtures.bodyReturning(1)))
        )
    val baseline =
        BuildCandidate(
            mapOf("com.example.Test" to bytes),
            emptyMap(),
            sourceDirs = listOf("classes:/proj/build/classes/java/main"),
        )
    val moved =
        BuildCandidate(
            mapOf("com.example.Test" to bytes),
            emptyMap(),
            sourceDirs = listOf("classes:/proj/build/classes/kotlin/main"),
        )

    val result = classifier.classify(baseline, moved, mainClass)

    assertEquals(RedefineRequirement.UNSAFE, result.requirement)
    assertEquals(setOf(EscalationKind.OUTPUT_LAYOUT_CHANGED), kinds(result))
    assertTrue(result.patches.isEmpty(), "an UNSAFE change-set must never carry a payload")
  }

  // ── BODY_ONLY ───────────────────────────────────────────────────────

  @Test
  fun `a retained method body change is BODY_ONLY with one patch carrying the old crc`() {
    val old =
        BytecodeFixtures.generateClass(
            methods = listOf(MethodSpec("tick", body = BytecodeFixtures.bodyReturning(1)))
        )
    val new =
        BytecodeFixtures.generateClass(
            methods = listOf(MethodSpec("tick", body = BytecodeFixtures.bodyReturning(2)))
        )

    val result = classify(mapOf("com.example.Test" to old), mapOf("com.example.Test" to new))

    assertEquals(RedefineRequirement.BODY_ONLY, result.requirement)
    assertEquals(1, result.patches.size)
    assertEquals("com.example.Test", result.patches[0].fqcn)
    assertEquals(BuildCandidate.crc32(old), result.patches[0].expectedLoadedCrc32)
    assertTrue(result.escalations.isEmpty())
  }

  @Test
  fun `an onEnable body change on a non-plugin class is plain BODY_ONLY`() {
    val old =
        BytecodeFixtures.generateClass(
            methods = listOf(MethodSpec("onEnable", body = BytecodeFixtures.bodyReturning(1)))
        )
    val new =
        BytecodeFixtures.generateClass(
            methods = listOf(MethodSpec("onEnable", body = BytecodeFixtures.bodyReturning(2)))
        )

    val result = classify(mapOf("com.example.Helper" to old), mapOf("com.example.Helper" to new))

    assertEquals(RedefineRequirement.BODY_ONLY, result.requirement)
  }

  // ── NONE (debug-only) ───────────────────────────────────────────────

  @Test
  fun `a line-number-only change is NONE with no patches`() {
    val old =
        BytecodeFixtures.generateClass(
            methods = listOf(MethodSpec("tick", body = BytecodeFixtures.bodyWithLineNumber(7)))
        )
    val new =
        BytecodeFixtures.generateClass(
            methods = listOf(MethodSpec("tick", body = BytecodeFixtures.bodyWithLineNumber(8)))
        )
    check(!old.contentEquals(new)) { "fixture must differ on disk" }

    val result = classify(mapOf("com.example.Test" to old), mapOf("com.example.Test" to new))

    assertEquals(RedefineRequirement.NONE, result.requirement)
    assertTrue(result.patches.isEmpty())
    assertTrue(result.escalations.isEmpty())
  }

  @Test
  fun `identical outputs are NONE`() {
    val bytes = BytecodeFixtures.generateClass(methods = listOf(MethodSpec("tick")))
    val result = classify(mapOf("com.example.Test" to bytes), mapOf("com.example.Test" to bytes))
    assertEquals(RedefineRequirement.NONE, result.requirement)
  }

  // ── UNSAFE: structural changes ──────────────────────────────────────

  @Test
  fun `an added unannotated method is UNSAFE with a reason naming the method`() {
    val old = BytecodeFixtures.generateClass(methods = listOf(MethodSpec("tick")))
    val new =
        BytecodeFixtures.generateClass(methods = listOf(MethodSpec("tick"), MethodSpec("helper")))

    val result = classify(mapOf("com.example.Test" to old), mapOf("com.example.Test" to new))

    assertEquals(RedefineRequirement.UNSAFE, result.requirement)
    assertEquals(setOf(EscalationKind.METHOD_ADDED), kinds(result))
    assertTrue(
        result.escalations[0].description.contains("helper"),
        "escalation must name the added method; got: ${result.escalations[0].description}",
    )
    assertTrue(result.patches.isEmpty())
  }

  @Test
  fun `a removed unannotated method is UNSAFE with a reason naming the method`() {
    val old =
        BytecodeFixtures.generateClass(methods = listOf(MethodSpec("tick"), MethodSpec("helper")))
    val new = BytecodeFixtures.generateClass(methods = listOf(MethodSpec("tick")))

    val result = classify(mapOf("com.example.Test" to old), mapOf("com.example.Test" to new))

    assertEquals(RedefineRequirement.UNSAFE, result.requirement)
    assertEquals(setOf(EscalationKind.METHOD_REMOVED), kinds(result))
    assertTrue(result.escalations[0].description.contains("helper"))
  }

  @Test
  fun `a new class is UNSAFE with a reason naming it and carries no payload`() {
    val existing = BytecodeFixtures.generateClass(methods = listOf(MethodSpec("tick")))
    val added = BytecodeFixtures.generateClass(name = "com/example/Added")

    val result =
        classify(
            mapOf("com.example.Test" to existing),
            mapOf("com.example.Test" to existing, "com.example.Added" to added),
        )

    assertEquals(RedefineRequirement.UNSAFE, result.requirement)
    assertEquals(setOf(EscalationKind.CLASS_ADDED), kinds(result))
    assertTrue(result.escalations[0].description.contains("Added"))
    assertTrue(result.patches.isEmpty(), "an UNSAFE change-set must never carry a payload")
  }

  @Test
  fun `an inner-class attribute change alone is UNSAFE`() {
    val old = BytecodeFixtures.generateClass(methods = listOf(MethodSpec("tick")))
    val new =
        BytecodeFixtures.generateClass(
            methods = listOf(MethodSpec("tick")),
            innerClassName = "com/example/Test\$1",
        )

    val result = classify(mapOf("com.example.Test" to old), mapOf("com.example.Test" to new))

    assertEquals(RedefineRequirement.UNSAFE, result.requirement)
    assertEquals(setOf(EscalationKind.NEST_CHANGE), kinds(result))
  }

  // ── UNSAFE: lifecycle gate ──────────────────────────────────────────

  @Test
  fun `an added annotated method is UNSAFE and names the annotation and method`() {
    val old = BytecodeFixtures.generateClass(methods = listOf(MethodSpec("tick")))
    val new =
        BytecodeFixtures.generateClass(
            methods =
                listOf(
                    MethodSpec("tick"),
                    MethodSpec(
                        "onJump",
                        desc = "(Lorg/bukkit/event/Event;)V",
                        annotations = listOf(eventHandler),
                        body = { it.visitInsn(Opcodes.RETURN) },
                    ),
                )
        )

    val result = classify(mapOf("com.example.Arena" to old), mapOf("com.example.Arena" to new))

    assertEquals(RedefineRequirement.UNSAFE, result.requirement)
    assertEquals(setOf(EscalationKind.ANNOTATED_METHOD_ADDED), kinds(result))
    val description = result.escalations[0].description
    assertTrue(
        description.contains("@EventHandler") &&
            description.contains("onJump") &&
            description.contains("Arena"),
        "escalation must name annotation, method and class; got: $description",
    )
    assertTrue(result.patches.isEmpty())
  }

  @Test
  fun `a removed annotated method is UNSAFE`() {
    val old =
        BytecodeFixtures.generateClass(
            methods =
                listOf(MethodSpec("tick"), MethodSpec("onJump", annotations = listOf(eventHandler)))
        )
    val new = BytecodeFixtures.generateClass(methods = listOf(MethodSpec("tick")))

    val result = classify(mapOf("com.example.Arena" to old), mapOf("com.example.Arena" to new))

    assertEquals(RedefineRequirement.UNSAFE, result.requirement)
    assertEquals(setOf(EscalationKind.ANNOTATED_METHOD_REMOVED), kinds(result))
  }

  @Test
  fun `an added method annotated only on a parameter is UNSAFE with the annotated label`() {
    // The developer-annotation gate must see parameter annotations too (a scan-once framework can
    // key on them), and with no method-level annotation to name, the label falls back to
    // "annotated" rather than inventing one.
    val old =
        BytecodeFixtures.generateClass(
            methods = listOf(MethodSpec("tick", body = BytecodeFixtures.bodyReturning(1)))
        )
    val new =
        BytecodeFixtures.generateClass(
            methods =
                listOf(
                    MethodSpec("tick", body = BytecodeFixtures.bodyReturning(1)),
                    MethodSpec(
                        "handle",
                        desc = "(I)V",
                        parameterAnnotations = listOf(eventHandler),
                    ),
                )
        )

    val result = classify(mapOf("com.example.Test" to old), mapOf("com.example.Test" to new))

    assertEquals(RedefineRequirement.UNSAFE, result.requirement)
    assertEquals(setOf(EscalationKind.ANNOTATED_METHOD_ADDED), kinds(result))
    assertTrue(
        result.escalations[0].description.contains("annotated method handle"),
        result.escalations[0].description,
    )
  }

  @Test
  fun `a throws-clause change on a retained method is UNSAFE`() {
    val old =
        BytecodeFixtures.generateClass(
            methods = listOf(MethodSpec("tick", body = BytecodeFixtures.bodyReturning(1)))
        )
    val new =
        BytecodeFixtures.generateClass(
            methods =
                listOf(
                    MethodSpec(
                        "tick",
                        exceptions = arrayOf("java/io/IOException"),
                        body = BytecodeFixtures.bodyReturning(1),
                    )
                )
        )

    val result = classify(mapOf("com.example.Test" to old), mapOf("com.example.Test" to new))

    assertEquals(RedefineRequirement.UNSAFE, result.requirement)
    assertEquals(setOf(EscalationKind.METHOD_DECLARATION_CHANGED), kinds(result))
  }

  @Test
  fun `adding an annotation to a retained method is UNSAFE`() {
    val old = BytecodeFixtures.generateClass(methods = listOf(MethodSpec("onJump")))
    val new =
        BytecodeFixtures.generateClass(
            methods = listOf(MethodSpec("onJump", annotations = listOf(eventHandler)))
        )

    val result = classify(mapOf("com.example.Arena" to old), mapOf("com.example.Arena" to new))

    assertEquals(RedefineRequirement.UNSAFE, result.requirement)
    assertEquals(setOf(EscalationKind.METHOD_DECLARATION_CHANGED), kinds(result))
  }

  @Test
  fun `a clinit body change is UNSAFE`() {
    val old =
        BytecodeFixtures.generateClass(
            methods =
                listOf(
                    MethodSpec(
                        "<clinit>",
                        access = Opcodes.ACC_STATIC,
                        body = BytecodeFixtures.bodyReturning(1),
                    )
                )
        )
    val new =
        BytecodeFixtures.generateClass(
            methods =
                listOf(
                    MethodSpec(
                        "<clinit>",
                        access = Opcodes.ACC_STATIC,
                        body = BytecodeFixtures.bodyReturning(2),
                    )
                )
        )

    val result = classify(mapOf("com.example.Config" to old), mapOf("com.example.Config" to new))

    assertEquals(RedefineRequirement.UNSAFE, result.requirement)
    assertEquals(setOf(EscalationKind.CLINIT_BODY), kinds(result))
  }

  @Test
  fun `an onEnable body change on the declared main class is UNSAFE`() {
    val old =
        BytecodeFixtures.generateClass(
            name = "com/example/MainPlugin",
            methods = listOf(MethodSpec("onEnable", body = BytecodeFixtures.bodyReturning(1))),
        )
    val new =
        BytecodeFixtures.generateClass(
            name = "com/example/MainPlugin",
            methods = listOf(MethodSpec("onEnable", body = BytecodeFixtures.bodyReturning(2))),
        )

    val result =
        classify(mapOf("com.example.MainPlugin" to old), mapOf("com.example.MainPlugin" to new))

    assertEquals(RedefineRequirement.UNSAFE, result.requirement)
    assertEquals(setOf(EscalationKind.LIFECYCLE_BODY), kinds(result))
  }

  @Test
  fun `an onEnable body change on a JavaPlugin subclass is UNSAFE regardless of metadata`() {
    val old =
        BytecodeFixtures.generateClass(
            name = "com/example/Other",
            superName = "org/bukkit/plugin/java/JavaPlugin",
            methods = listOf(MethodSpec("onEnable", body = BytecodeFixtures.bodyReturning(1))),
        )
    val new =
        BytecodeFixtures.generateClass(
            name = "com/example/Other",
            superName = "org/bukkit/plugin/java/JavaPlugin",
            methods = listOf(MethodSpec("onEnable", body = BytecodeFixtures.bodyReturning(2))),
        )

    val result = classify(mapOf("com.example.Other" to old), mapOf("com.example.Other" to new))

    assertEquals(RedefineRequirement.UNSAFE, result.requirement)
    assertEquals(setOf(EscalationKind.LIFECYCLE_BODY), kinds(result))
  }

  // ── UNSAFE: structure ───────────────────────────────────────────────

  @Test
  fun `a field addition is UNSAFE`() {
    val old = BytecodeFixtures.generateClass()
    val new =
        BytecodeFixtures.generateClass(fields = listOf(Triple(Opcodes.ACC_PRIVATE, "speed", "I")))

    val result = classify(mapOf("com.example.Test" to old), mapOf("com.example.Test" to new))

    assertEquals(RedefineRequirement.UNSAFE, result.requirement)
    assertEquals(setOf(EscalationKind.FIELD_CHANGE), kinds(result))
  }

  @Test
  fun `a superclass change is UNSAFE`() {
    val old = BytecodeFixtures.generateClass()
    val new = BytecodeFixtures.generateClass(superName = "com/example/Base")

    val result = classify(mapOf("com.example.Test" to old), mapOf("com.example.Test" to new))

    assertEquals(RedefineRequirement.UNSAFE, result.requirement)
    assertEquals(setOf(EscalationKind.HIERARCHY_CHANGE), kinds(result))
  }

  @Test
  fun `an added interface is UNSAFE`() {
    val old = BytecodeFixtures.generateClass()
    val new = BytecodeFixtures.generateClass(interfaces = arrayOf("org/bukkit/event/Listener"))

    val result = classify(mapOf("com.example.Test" to old), mapOf("com.example.Test" to new))

    assertEquals(RedefineRequirement.UNSAFE, result.requirement)
    assertEquals(setOf(EscalationKind.HIERARCHY_CHANGE), kinds(result))
  }

  @Test
  fun `a class-level annotation change is UNSAFE`() {
    val old = BytecodeFixtures.generateClass()
    val new = BytecodeFixtures.generateClass(classAnnotations = listOf("Lcom/example/Marker;"))

    val result = classify(mapOf("com.example.Test" to old), mapOf("com.example.Test" to new))

    assertEquals(RedefineRequirement.UNSAFE, result.requirement)
    assertEquals(setOf(EscalationKind.CLASS_DECLARATION_CHANGED), kinds(result))
  }

  @Test
  fun `a removed class is UNSAFE`() {
    val kept = BytecodeFixtures.generateClass()
    val gone = BytecodeFixtures.generateClass(name = "com/example/Gone")

    val result =
        classify(
            mapOf("com.example.Test" to kept, "com.example.Gone" to gone),
            mapOf("com.example.Test" to kept),
        )

    assertEquals(RedefineRequirement.UNSAFE, result.requirement)
    assertEquals(setOf(EscalationKind.CLASS_REMOVED), kinds(result))
  }

  @Test
  fun `unparseable bytes are UNSAFE`() {
    val old = BytecodeFixtures.generateClass()
    val garbage = byteArrayOf(1, 2, 3, 4)

    val result = classify(mapOf("com.example.Test" to old), mapOf("com.example.Test" to garbage))

    assertEquals(RedefineRequirement.UNSAFE, result.requirement)
    assertEquals(setOf(EscalationKind.UNPARSEABLE_CLASS), kinds(result))
  }

  // ── UNSAFE: resources ───────────────────────────────────────────────

  @Test
  fun `a changed resource is UNSAFE and names the file`() {
    val bytes = BytecodeFixtures.generateClass()
    val result =
        classify(
            mapOf("com.example.Test" to bytes),
            mapOf("com.example.Test" to bytes),
            oldResources = mapOf("config.yml" to 1L),
            newResources = mapOf("config.yml" to 2L),
        )

    assertEquals(RedefineRequirement.UNSAFE, result.requirement)
    assertEquals(setOf(EscalationKind.RESOURCE_CHANGED), kinds(result))
    assertTrue(result.escalations[0].description.contains("config.yml"))
  }

  @Test
  fun `an added resource is UNSAFE too`() {
    val bytes = BytecodeFixtures.generateClass()
    val result =
        classify(
            mapOf("com.example.Test" to bytes),
            mapOf("com.example.Test" to bytes),
            newResources = mapOf("messages.yml" to 5L),
        )

    assertEquals(RedefineRequirement.UNSAFE, result.requirement)
  }

  @Test
  fun `a removed resource is UNSAFE and says removed`() {
    val bytes = BytecodeFixtures.generateClass()
    val result =
        classify(
            mapOf("com.example.Test" to bytes),
            mapOf("com.example.Test" to bytes),
            oldResources = mapOf("config.yml" to 1L),
        )

    assertEquals(RedefineRequirement.UNSAFE, result.requirement)
    assertEquals(setOf(EscalationKind.RESOURCE_CHANGED), kinds(result))
    assertTrue(
        result.escalations[0].description.contains("removed"),
        result.escalations[0].description,
    )
  }

  // ── Aggregation ─────────────────────────────────────────────────────

  @Test
  fun `a mixed change-set takes the maximum requirement and drops the payload on UNSAFE`() {
    val bodyOld =
        BytecodeFixtures.generateClass(
            name = "com/example/A",
            methods = listOf(MethodSpec("tick", body = BytecodeFixtures.bodyReturning(1))),
        )
    val bodyNew =
        BytecodeFixtures.generateClass(
            name = "com/example/A",
            methods = listOf(MethodSpec("tick", body = BytecodeFixtures.bodyReturning(2))),
        )
    val fieldOld = BytecodeFixtures.generateClass(name = "com/example/B")
    val fieldNew =
        BytecodeFixtures.generateClass(
            name = "com/example/B",
            fields = listOf(Triple(Opcodes.ACC_PRIVATE, "speed", "I")),
        )

    val result =
        classify(
            mapOf("com.example.A" to bodyOld, "com.example.B" to fieldOld),
            mapOf("com.example.A" to bodyNew, "com.example.B" to fieldNew),
        )

    assertEquals(RedefineRequirement.UNSAFE, result.requirement)
    assertTrue(
        result.patches.isEmpty(),
        "an UNSAFE change-set must never carry a partial payload",
    )
    assertEquals(setOf(EscalationKind.FIELD_CHANGE), kinds(result))
  }

  @Test
  fun `body change plus new class is UNSAFE and drops the body patch too`() {
    val bodyOld =
        BytecodeFixtures.generateClass(
            name = "com/example/A",
            methods = listOf(MethodSpec("tick", body = BytecodeFixtures.bodyReturning(1))),
        )
    val bodyNew =
        BytecodeFixtures.generateClass(
            name = "com/example/A",
            methods = listOf(MethodSpec("tick", body = BytecodeFixtures.bodyReturning(2))),
        )
    val added = BytecodeFixtures.generateClass(name = "com/example/New")

    val result =
        classify(
            mapOf("com.example.A" to bodyOld),
            mapOf("com.example.A" to bodyNew, "com.example.New" to added),
        )

    assertEquals(RedefineRequirement.UNSAFE, result.requirement)
    assertEquals(setOf(EscalationKind.CLASS_ADDED), kinds(result))
    assertTrue(
        result.patches.isEmpty(),
        "the safe body edit must not ship while the change-set as a whole escalates",
    )
  }

  // ── Run-once gates on ADDED members ─────────────────────────────────
  //
  // The JVM will happily define these; it will simply never call them. Every added method
  // escalates; these pin that the run-once shapes keep their sharper why-it-could-never-take-
  // effect reasons instead of the generic can't-add-members one.

  @Test
  fun `an added static initializer is UNSAFE`() {
    val old = BytecodeFixtures.generateClass(name = "com/example/A")
    val new =
        BytecodeFixtures.generateClass(
            name = "com/example/A",
            methods = listOf(MethodSpec("<clinit>", "()V", Opcodes.ACC_STATIC)),
        )

    val result = classify(mapOf("com.example.A" to old), mapOf("com.example.A" to new))

    assertEquals(RedefineRequirement.UNSAFE, result.requirement)
    assertEquals(setOf(EscalationKind.CLINIT_BODY), kinds(result))
  }

  @Test
  fun `an added lifecycle method on the plugin main class is UNSAFE`() {
    val old = BytecodeFixtures.generateClass(name = "com/example/MainPlugin")
    val new =
        BytecodeFixtures.generateClass(
            name = "com/example/MainPlugin",
            methods = listOf(MethodSpec("onLoad")),
        )

    val result = classify(mapOf(mainClass to old), mapOf(mainClass to new))

    assertEquals(RedefineRequirement.UNSAFE, result.requirement)
    assertEquals(setOf(EscalationKind.LIFECYCLE_BODY), kinds(result))
  }

  // ── Run-once constructors ───────────────────────────────────────────

  @Test
  fun `a constructor body change on a singleton object is UNSAFE`() {
    // The Kotlin `object` shape: a static final field of the class's own type, assigned once in
    // <clinit>, so <init> can never run again.
    fun singleton(body: (org.objectweb.asm.MethodVisitor) -> Unit) =
        BytecodeFixtures.generateClass(
            name = "com/example/Registry",
            fields =
                listOf(
                    Triple(
                        Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
                        "INSTANCE",
                        "Lcom/example/Registry;",
                    )
                ),
            methods = listOf(MethodSpec("<init>", "()V", body = body)),
        )

    val result =
        classify(
            mapOf("com.example.Registry" to singleton(BytecodeFixtures.bodyReturning(1))),
            mapOf("com.example.Registry" to singleton(BytecodeFixtures.bodyReturning(2))),
        )

    assertEquals(RedefineRequirement.UNSAFE, result.requirement)
    assertEquals(setOf(EscalationKind.LIFECYCLE_BODY), kinds(result))
  }

  @Test
  fun `a constructor body change on an ordinary class is BODY_ONLY`() {
    fun ordinary(body: (org.objectweb.asm.MethodVisitor) -> Unit) =
        BytecodeFixtures.generateClass(
            name = "com/example/Session",
            methods = listOf(MethodSpec("<init>", "()V", body = body)),
        )

    val result =
        classify(
            mapOf("com.example.Session" to ordinary(BytecodeFixtures.bodyReturning(1))),
            mapOf("com.example.Session" to ordinary(BytecodeFixtures.bodyReturning(2))),
        )

    assertEquals(RedefineRequirement.BODY_ONLY, result.requirement)
  }

  // ── Unmodeled-delta backstop ────────────────────────────────────────

  @Test
  fun `bytes that differ in an unmodeled attribute escalate rather than reporting NONE`() {
    // EnclosingMethod is not covered by any fingerprint here. The point of the test is not that
    // attribute in particular — it stands in for "something the classifier does not model", which
    // must never be reported to the user as "no code changes".
    fun withOuter(outer: String?): ByteArray {
      val cw = org.objectweb.asm.ClassWriter(0)
      cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/example/A", null, "java/lang/Object", null)
      if (outer != null) cw.visitOuterClass(outer, "run", "()V")
      cw.visitEnd()
      return cw.toByteArray()
    }

    val result =
        classify(
            mapOf("com.example.A" to withOuter(null)),
            mapOf("com.example.A" to withOuter("com/example/Outer")),
        )

    assertEquals(RedefineRequirement.UNSAFE, result.requirement)
    assertEquals(setOf(EscalationKind.UNMODELED_CHANGE), kinds(result))
  }

  @Test
  fun `a body edit with an unmodeled declaration change escalates rather than shipping`() {
    // BODY_ONLY is the one verdict that ships bytes, so it needs its own backstop: canonicalBytes
    // can't serve, bodies differ there by definition. A return-type annotation is the credible
    // case — the JVM accepts the redefinition, so nothing else vetoes it, and a framework that
    // scans type annotations once never sees the change. Patching it in would be exactly the
    // silent staleness this lane exists to prevent.
    val old =
        BytecodeFixtures.generateClass(
            methods = listOf(MethodSpec("tick", body = BytecodeFixtures.bodyReturning(1)))
        )
    val new =
        BytecodeFixtures.generateClass(
            methods =
                listOf(
                    MethodSpec(
                        "tick",
                        body = BytecodeFixtures.bodyReturning(2),
                        returnTypeAnnotations = listOf("Lcom/example/Tag;"),
                    )
                )
        )

    val result = classify(mapOf("com.example.Test" to old), mapOf("com.example.Test" to new))

    assertEquals(RedefineRequirement.UNSAFE, result.requirement)
    assertEquals(setOf(EscalationKind.UNMODELED_CHANGE), kinds(result))
    assertTrue(result.patches.isEmpty(), "an UNSAFE change-set must never carry a payload")
  }

  @Test
  fun `a debug-only difference is still NONE`() {
    val old =
        BytecodeFixtures.generateClass(
            methods = listOf(MethodSpec("tick", body = BytecodeFixtures.bodyWithLineNumber(10)))
        )
    val new =
        BytecodeFixtures.generateClass(
            methods = listOf(MethodSpec("tick", body = BytecodeFixtures.bodyWithLineNumber(20)))
        )

    val result = classify(mapOf("com.example.Test" to old), mapOf("com.example.Test" to new))

    assertEquals(
        RedefineRequirement.NONE,
        result.requirement,
        "a comment edit that only shifts line numbers must stay a no-op, not a full swap",
    )
    assertTrue(result.escalations.isEmpty())
  }
}
