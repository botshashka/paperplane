package dev.paperplane.cli.devserver.instant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Golden tests: the classifier graded against **real compiler output**, not synthesized ASM.
 *
 * The rest of [ChangeClassifierTest] builds its bytecode with
 * [dev.paperplane.cli.testing .BytecodeFixtures], which makes each structural delta explicit but
 * tests the classifier's assumptions in lockstep with its own model of what a compiler emits. Real
 * javac and kotlinc emit attributes a hand-rolled fixture never will — `@kotlin.Metadata` whose
 * `d1`/`d2` encode every declared member, `org.jetbrains.annotations.@NotNull` on every
 * reference-typed parameter and return, synthetic accessors — and those are exactly what decide
 * whether the fast lane engages at all for a real plugin. These fixtures are the source of truth
 * for that.
 *
 * Fixture provenance — `cli/src/test/resources/fixtures/golden/`, regenerate the same way:
 * ```
 * // java-Greeter-v{1,2-body,3-added}.class — javac 21
 * package probe;
 * public class Greeter {
 *   public String greet(String name) { return "hello " + name; }   // v2-body: "goodbye " + name
 *   public String format(String name) { return name.toUpperCase(); } // v3-added only
 * }
 *
 * // java-Marked-v{1,2-typeanno-body}.class — javac 21
 * package probe;
 * import java.lang.annotation.*;
 * @Target(ElementType.TYPE_USE) @Retention(RetentionPolicy.RUNTIME) public @interface Tag {}
 *
 * package probe;
 * public class Marked { public String greet(String n) { return "hello " + n; } }
 * // v2-typeanno-body: public @Tag String greet(String n) { return "goodbye " + n; }
 *
 * // kotlin-Probe-v{1,2-body,3-added}.class — kotlinc 2.3.20, jvmToolchain(21)
 * package probe
 * class Probe {
 *   fun greet(name: String): String = "hello $name"                 // v2-body: "goodbye $name"
 *   fun format(name: String): String = name.uppercase()             // v3-added only
 * }
 * ```
 */
class ChangeClassifierGoldenTest {

  private val classifier = ChangeClassifier()

  /** No fixture extends JavaPlugin, so the lifecycle gate never applies to these. */
  private val mainClass = "probe.MainPlugin"

  private fun fixture(name: String): ByteArray =
      checkNotNull(javaClass.getResourceAsStream("/fixtures/golden/$name")) {
            "missing golden fixture $name"
          }
          .use { it.readBytes() }

  private fun classify(fqcn: String, old: String, new: String): InstantClassification =
      classifier.classify(
          BuildCandidate(mapOf(fqcn to fixture(old)), emptyMap()),
          BuildCandidate(mapOf(fqcn to fixture(new)), emptyMap()),
          mainClass,
      )

  // ── javac ───────────────────────────────────────────────────────────

  @Test
  fun `classifies a real javac body-only edit as BODY_ONLY`() {
    val result = classify("probe.Greeter", "java-Greeter-v1.class", "java-Greeter-v2-body.class")

    assertEquals(RedefineRequirement.BODY_ONLY, result.requirement, escalationsOf(result))
    assertEquals(1, result.patches.size)
    assertEquals("probe.Greeter", result.patches.single().fqcn)
  }

  @Test
  fun `a real javac added method escalates with a reason naming the method`() {
    val result = classify("probe.Greeter", "java-Greeter-v1.class", "java-Greeter-v3-added.class")

    assertEquals(RedefineRequirement.UNSAFE, result.requirement, escalationsOf(result))
    assertTrue(
        result.escalations.any {
          it.kind == EscalationKind.METHOD_ADDED && it.description.contains("format")
        },
        "expected a METHOD_ADDED escalation naming the added method; got ${escalationsOf(result)}",
    )
  }

  /**
   * The BODY_ONLY backstop against real compiler output. A type annotation is the credible
   * unmodeled declaration change: javac attaches it as `RuntimeVisibleTypeAnnotations`, the JVM
   * accepts the redefinition without complaint, and a framework that scans type annotations once
   * never sees it — so nothing but this check stands between a body edit carrying one and a
   * silently stale server.
   */
  @Test
  fun `a real javac body edit with a type-annotation change escalates as unmodeled`() {
    val result =
        classify("probe.Marked", "java-Marked-v1.class", "java-Marked-v2-typeanno-body.class")

    assertEquals(RedefineRequirement.UNSAFE, result.requirement, escalationsOf(result))
    assertTrue(
        result.escalations.any { it.kind == EscalationKind.UNMODELED_CHANGE },
        "expected an UNMODELED_CHANGE escalation; got ${escalationsOf(result)}",
    )
    assertTrue(result.patches.isEmpty(), "an UNSAFE change-set must never carry a payload")
  }

  // ── kotlinc ─────────────────────────────────────────────────────────

  /**
   * The load-bearing one for Kotlin plugins on a stock JVM: kotlinc leaves `@kotlin.Metadata`
   * byte-identical across a body-only edit (`d1`/`d2` encode declarations, not bodies), so the
   * class-annotation fingerprint must not move and the edit must stay patchable.
   */
  @Test
  fun `classifies a real kotlinc body-only edit as BODY_ONLY`() {
    val result = classify("probe.Probe", "kotlin-Probe-v1.class", "kotlin-Probe-v2-body.class")

    assertEquals(RedefineRequirement.BODY_ONLY, result.requirement, escalationsOf(result))
    assertEquals(1, result.patches.size)
  }

  /**
   * Adding a Kotlin declaration moves `@kotlin.Metadata`'s `d1`/`d2` **and** attaches
   * `org.jetbrains.annotations.@NotNull` to the new method. Every added method escalates, but the
   * compiler-annotation denylist decides *which reason prints*: without it the escalation would
   * name `@NotNull` or a class-declaration change — compiler noise, not the user's edit. The honest
   * reason names the method.
   */
  @Test
  fun `a real kotlinc added method escalates naming the method, not compiler noise`() {
    val result = classify("probe.Probe", "kotlin-Probe-v1.class", "kotlin-Probe-v3-added.class")

    assertEquals(RedefineRequirement.UNSAFE, result.requirement, escalationsOf(result))
    assertTrue(
        result.escalations.any {
          it.kind == EscalationKind.METHOD_ADDED && it.description.contains("format")
        },
        "expected a METHOD_ADDED escalation naming the added method; got ${escalationsOf(result)}",
    )
    assertTrue(
        result.escalations.none { it.description.contains("NotNull") },
        "the printed reason must name the user's edit, not kotlinc's @NotNull noise",
    )
  }

  private fun escalationsOf(result: InstantClassification): String =
      "escalations: " + result.escalations.joinToString { "${it.kind}: ${it.description}" }
}
