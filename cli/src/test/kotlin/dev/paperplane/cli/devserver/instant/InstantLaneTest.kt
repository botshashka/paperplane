package dev.paperplane.cli.devserver.instant

import dev.paperplane.cli.config.DevConfig
import dev.paperplane.cli.config.PaperPlaneConfig
import dev.paperplane.cli.devserver.InstantSwapReport
import dev.paperplane.cli.devserver.InstantSwapStatus
import dev.paperplane.cli.devserver.InstantWaitResult
import dev.paperplane.cli.gradle.ProjectMetadata
import dev.paperplane.cli.testing.BytecodeFixtures
import dev.paperplane.cli.testing.BytecodeFixtures.MethodSpec
import dev.paperplane.cli.testing.DevSessionFixture
import dev.paperplane.cli.testing.FakePaperServerManager
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Drives [InstantLane.attempt] with all-fake collaborators and real class files on disk: the
 * patched path (compile → classify → send → confirm, no jar build), the escalation ladder with
 * named reasons (unsafe, capability shortfall, preconditions), the no-change path, baseline
 * bookkeeping, the reflection-framework cap, and build-config metadata invalidation.
 */
class InstantLaneTest {

  @TempDir lateinit var tempDir: File

  private fun classesDir(): File = File(tempDir, "build/classes/java/main").apply { mkdirs() }

  private fun writeClass(fqcn: String, bytes: ByteArray) {
    val file = File(classesDir(), fqcn.replace('.', '/') + ".class")
    file.parentFile.mkdirs()
    file.writeBytes(bytes)
  }

  private fun metadata(
      depend: List<String> = emptyList(),
  ): ProjectMetadata =
      ProjectMetadata(
          jarPath = "build/libs/test.jar",
          paperApiVersion = "1.21.4",
          mainClass = "com.example.MainPlugin",
          pluginName = "TestPlugin",
          projectDir = tempDir.absolutePath,
          version = "1.0.0",
          classesDir = classesDir().absolutePath,
          classesDirs = listOf(classesDir().absolutePath),
          resourcesDir = "",
          depend = depend,
      )

  private class Setup(
      val fixture: DevSessionFixture,
      val lane: InstantLane,
      val server: FakePaperServerManager,
      val baseline: BaselineTracker,
      val metadata: ProjectMetadata,
  )

  private fun setup(
      instantEnabled: Boolean = true,
      capability: RedefineCapability = RedefineCapability.BODY_ONLY,
      metadata: ProjectMetadata = metadata(),
  ): Setup {
    val fixture =
        DevSessionFixture(
            tempDir,
            config = PaperPlaneConfig(dev = DevConfig(instant = instantEnabled)),
        )
    fixture.gradle.nextMetadata = metadata
    fixture.gradle.nextMetadataFast = metadata
    val server =
        FakePaperServerManager(fixture.ppDir, fixture.downloader, fixture.ui).apply {
          this.capability = capability
        }
    val lane = InstantLane(fixture.session)
    return Setup(fixture, lane, server, BaselineTracker(), metadata)
  }

  private fun bodyV(v: Int): ByteArray =
      BytecodeFixtures.generateClass(
          name = "com/example/Logic",
          methods = listOf(MethodSpec("tick", body = BytecodeFixtures.bodyReturning(v))),
      )

  /** Seeds the baseline from what's currently on disk (the mode's confirm-full-swap moment). */
  private fun seed(setup: Setup) {
    setup.lane.confirmFullSwap(setup.baseline)
    check(setup.baseline.confirmed() != null)
  }

  private fun attempt(setup: Setup): InstantOutcome {
    lateinit var outcome: InstantOutcome
    setup.fixture.ui.phase {
      outcome = setup.lane.attempt(setup.server, setup.metadata, setup.baseline)
      dev.paperplane.cli.ui.TerminalUI.PhaseEnd.None
    }
    return outcome
  }

  // ── Patched path ────────────────────────────────────────────────────

  @Test
  fun `a body-only change compiles, patches, confirms the baseline, and skips the jar build`() {
    val setup = setup()
    writeClass("com.example.Logic", bodyV(1))
    seed(setup)
    writeClass("com.example.Logic", bodyV(2))

    val outcome = attempt(setup)

    val patched = assertInstanceOf(InstantOutcome.Patched::class.java, outcome)
    assertEquals(1, patched.patchedCount)
    val swap = setup.server.sentInstantSwaps.single()
    assertEquals("com.example.Logic", swap.classes.single().fqcn)
    assertEquals(
        BuildCandidate.crc32(bodyV(1)),
        swap.classes.single().expectedCrc32,
        "the patch must carry the baseline's CRC for companion verification",
    )
    assertTrue(
        setup.baseline.confirmed()!!.classes.getValue("com.example.Logic").contentEquals(bodyV(2)),
        "a confirmed patch must advance the baseline",
    )
    assertTrue(setup.fixture.gradle.calls.contains("compileOnly"))
    assertFalse(
        setup.fixture.gradle.calls.contains("build"),
        "the patched path must never pay for a jar build",
    )
  }

  @Test
  fun `the baseline advances only for classes the companion reported applied`() {
    // The companion can legitimately skip a class it was asked to handle. Advancing for everything
    // requested would leave the CLI vouching for bytes the server never took — and because the
    // baseline is what the next classification diffs against, that error is permanent.
    val setup = setup()
    writeClass("com.example.Logic", bodyV(1))
    seed(setup)
    writeClass("com.example.Logic", bodyV(2))
    setup.server.instantWaitResult = { id ->
      InstantWaitResult.Answered(
          InstantSwapReport(
              requestId = id,
              status = InstantSwapStatus.OK,
              patched = 0,
              appliedClasses = emptyList(),
          )
      )
    }

    attempt(setup)

    assertTrue(
        setup.baseline.confirmed()!!.classes.getValue("com.example.Logic").contentEquals(bodyV(1)),
        "a class the companion did not report applied must stay at its old baseline bytes",
    )
  }

  @Test
  fun `an unavailable companion connection escalates instead of patching`() {
    val setup = setup()
    writeClass("com.example.Logic", bodyV(1))
    seed(setup)
    writeClass("com.example.Logic", bodyV(2))
    setup.server.sendInstantSwapResult = false

    val outcome = attempt(setup)

    val escalate = assertInstanceOf(InstantOutcome.Escalate::class.java, outcome)
    val reason = escalate.reason.orEmpty()
    assertTrue(reason.contains("companion connection"), reason)
  }

  @Test
  fun `confirmFullSwap without build metadata unseeds the baseline rather than keeping a stale one`() {
    val setup = setup()
    writeClass("com.example.Logic", bodyV(1))
    seed(setup)

    // The mode reports a successful full swap, but metadata is unavailable this time — so we
    // cannot capture what the server just loaded. Keeping the previous baseline would vouch for
    // the build before the one now running.
    setup.fixture.gradle.nextMetadataFast = null
    setup.fixture.session.seedFastMetadata(null)
    setup.lane.confirmFullSwap(setup.baseline)

    assertNull(
        setup.baseline.confirmed(),
        "an unconfirmable swap must drop to unseeded — the safe direction is refusing to patch",
    )
  }

  @Test
  fun `an identical rebuild reports no change and sends nothing`() {
    val setup = setup()
    writeClass("com.example.Logic", bodyV(1))
    seed(setup)

    val outcome = attempt(setup)

    assertInstanceOf(InstantOutcome.NoChange::class.java, outcome)
    assertTrue(setup.server.sentInstantSwaps.isEmpty())
  }

  // ── Escalations ─────────────────────────────────────────────────────

  @Test
  fun `an unsafe change escalates with the classifier's named reason`() {
    val setup = setup()
    writeClass("com.example.Logic", bodyV(1))
    seed(setup)
    writeClass(
        "com.example.Logic",
        BytecodeFixtures.generateClass(
            name = "com/example/Logic",
            fields = listOf(Triple(org.objectweb.asm.Opcodes.ACC_PRIVATE, "speed", "I")),
            methods = listOf(MethodSpec("tick", body = BytecodeFixtures.bodyReturning(1))),
        ),
    )

    val outcome = attempt(setup)

    val escalate = assertInstanceOf(InstantOutcome.Escalate::class.java, outcome)
    assertTrue(escalate.reason!!.contains("field speed"), escalate.reason)
    assertTrue(setup.server.sentInstantSwaps.isEmpty())
  }

  @Test
  fun `an added method escalates naming the method`() {
    val setup = setup()
    writeClass("com.example.Logic", bodyV(1))
    seed(setup)
    writeClass(
        "com.example.Logic",
        BytecodeFixtures.generateClass(
            name = "com/example/Logic",
            methods =
                listOf(
                    MethodSpec("tick", body = BytecodeFixtures.bodyReturning(1)),
                    MethodSpec("helper"),
                ),
        ),
    )

    val outcome = attempt(setup)

    val escalate = assertInstanceOf(InstantOutcome.Escalate::class.java, outcome)
    assertTrue(escalate.reason.contains("method helper added"), escalate.reason)
    assertTrue(setup.server.sentInstantSwaps.isEmpty())
  }

  @Test
  fun `instant disabled falls through silently after the compile`() {
    val setup = setup(instantEnabled = false)
    writeClass("com.example.Logic", bodyV(1))
    seed(setup)

    val outcome = attempt(setup)

    // Disabled, not a nameless Escalate: a switched-off lane must not nag every rebuild.
    assertInstanceOf(InstantOutcome.Disabled::class.java, outcome)
    assertTrue(setup.fixture.gradle.calls.contains("compileOnly"))
  }

  @Test
  fun `a stopped server, a missing baseline, and a NONE capability each escalate with a reason`() {
    val down = setup()
    writeClass("com.example.Logic", bodyV(1))
    seed(down)
    down.server.runningResult = false
    assertTrue(
        assertInstanceOf(InstantOutcome.Escalate::class.java, attempt(down))
            .reason!!
            .contains("server not running")
    )

    val unseeded = setup()
    assertTrue(
        assertInstanceOf(InstantOutcome.Escalate::class.java, attempt(unseeded))
            .reason!!
            .contains("baseline")
    )

    val incapable = setup(capability = RedefineCapability.NONE)
    writeClass("com.example.Logic", bodyV(1))
    seed(incapable)
    assertTrue(
        assertInstanceOf(InstantOutcome.Escalate::class.java, attempt(incapable))
            .reason!!
            .contains("capability")
    )
  }

  @Test
  fun `a refused report escalates with the companion's reason and never advances the baseline`() {
    val setup = setup()
    writeClass("com.example.Logic", bodyV(1))
    seed(setup)
    writeClass("com.example.Logic", bodyV(2))
    setup.server.instantWaitResult = { id ->
      InstantWaitResult.Answered(
          InstantSwapReport(
              requestId = id,
              status = InstantSwapStatus.REFUSED,
              reason = "baseline drift on com.example.Logic",
          )
      )
    }

    val outcome = attempt(setup)

    val escalate = assertInstanceOf(InstantOutcome.Escalate::class.java, outcome)
    assertTrue(escalate.reason!!.contains("baseline drift"), escalate.reason)
    assertTrue(
        setup.baseline.confirmed()!!.classes.getValue("com.example.Logic").contentEquals(bodyV(1)),
        "an unconfirmed patch must not advance the baseline",
    )
  }

  @Test
  fun `a compile failure ends the attempt as CompileFailed`() {
    val setup = setup()
    writeClass("com.example.Logic", bodyV(1))
    seed(setup)
    setup.fixture.gradle.nextBuildResult = false

    assertInstanceOf(InstantOutcome.CompileFailed::class.java, attempt(setup))
    assertTrue(setup.fixture.terminal.writes.any { it.contains("Build failed") })
  }

  // ── Capability banner ───────────────────────────────────────────────

  @Test
  fun `the capability label always reports the tier ceiling and why`() {
    val bodyOnly = setup()
    assertEquals("body-only", bodyOnly.lane.capabilityLabel(bodyOnly.server))

    val none = setup(capability = RedefineCapability.NONE)
    assertEquals("off (no agent in the server JVM)", none.lane.capabilityLabel(none.server))

    val disabled = setup(instantEnabled = false)
    assertEquals("off (dev.instant: false)", disabled.lane.capabilityLabel(disabled.server))
  }

  // ── Metadata invalidation ───────────────────────────────────────────

  @Test
  fun `a build-config change drops the cached fast metadata`() {
    val setup = setup()
    writeClass("com.example.Logic", bodyV(1))
    seed(setup)
    val callsAfterSeed = setup.fixture.gradle.calls.count { it == "metadataFast" }
    check(callsAfterSeed >= 1)

    attempt(setup) // cached — no new metadataFast call
    assertEquals(
        callsAfterSeed,
        setup.fixture.gradle.calls.count { it == "metadataFast" },
        "an unchanged build config must reuse the cached metadata",
    )

    // Drive the real invalidation path, not a listener list: an edit to build.gradle.kts is what
    // moves the classes-dir layout, and the cache must drop for every consumer at once.
    setup.fixture.session.maybeInvalidateGradleConnection(
        listOf(
            dev.paperplane.cli.watcher.FileWatcher.normalizePath(
                java.io.File(setup.fixture.projectDir, "build.gradle.kts").absolutePath
            )
        )
    )
    attempt(setup)
    assertEquals(
        callsAfterSeed + 1,
        setup.fixture.gradle.calls.count { it == "metadataFast" },
        "a build-config change must refetch the metadata",
    )
  }
}
