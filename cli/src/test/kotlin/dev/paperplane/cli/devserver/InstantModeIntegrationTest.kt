package dev.paperplane.cli.devserver

import dev.paperplane.cli.gradle.ProjectMetadata
import dev.paperplane.cli.testing.BytecodeFixtures
import dev.paperplane.cli.testing.BytecodeFixtures.MethodSpec
import dev.paperplane.cli.testing.DevSessionFixture
import dev.paperplane.cli.testing.FakePaperServerManager
import dev.paperplane.cli.testing.FakeVelocityDownloader
import dev.paperplane.cli.testing.FakeVelocityManager
import dev.paperplane.cli.ui.TerminalUI.PhaseEnd
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The instant lane's contract with each dev mode's rebuild: a patched cycle must never pay for the
 * mode's swap machinery (restart must not stop the server; blue-green must not save the world or
 * touch the standby; hot-reload must not send a load request), and the manual escape hatch
 * ([forceFullSwap]) must skip the lane entirely and run the mode's full path.
 */
class InstantModeIntegrationTest {

  @TempDir lateinit var tempDir: File

  private fun classesDir(): File = File(tempDir, "build/classes/java/main").apply { mkdirs() }

  private fun writeLogic(marker: Int) {
    BytecodeFixtures.writeClassFile(
        classesDir(),
        "com.example.Logic",
        BytecodeFixtures.generateClass(
            name = "com/example/Logic",
            methods = listOf(MethodSpec("tick", body = BytecodeFixtures.bodyReturning(marker))),
        ),
    )
  }

  private fun fixtureWithClasses(): Pair<DevSessionFixture, ProjectMetadata> {
    val fixture = DevSessionFixture(tempDir).withMetadata()
    val metadata =
        fixture.gradle.nextMetadata!!.copy(
            classesDir = classesDir().absolutePath,
            classesDirs = listOf(classesDir().absolutePath),
        )
    fixture.gradle.nextMetadata = metadata
    fixture.gradle.nextMetadataFast = metadata
    return fixture to metadata
  }

  // ── Restart mode ────────────────────────────────────────────────────

  @Test
  fun `a patched restart-mode rebuild never stops the server or builds the jar`() {
    val (fixture, metadata) = fixtureWithClasses()
    val server = FakePaperServerManager(fixture.ppDir, fixture.downloader, fixture.ui)
    val mode = RestartMode(fixture.session, server)
    writeLogic(1)
    mode.lane.confirmFullSwap(mode.baseline)
    writeLogic(2)
    val paperJar = File(fixture.ppDir, "paper.jar").apply { writeText("fake") }

    lateinit var end: PhaseEnd
    fixture.ui.phase {
      end = mode.rebuild(metadata, paperJar)
      PhaseEnd.None
    }

    assertEquals(PhaseEnd.Watching, end)
    assertTrue(fixture.terminal.writes.any { it.contains("Patched 1 class (instant)") })
    assertFalse(server.calls.contains("stop"), "a patched cycle must not restart the server")
    assertFalse(fixture.gradle.calls.contains("build"), "a patched cycle must not build the jar")
    assertEquals(1, server.sentInstantSwaps.size)
  }

  @Test
  fun `a forced restart-mode rebuild skips the lane and runs the full stop-build-start path`() {
    val (fixture, metadata) = fixtureWithClasses()
    val server = FakePaperServerManager(fixture.ppDir, fixture.downloader, fixture.ui)
    val mode = RestartMode(fixture.session, server)
    writeLogic(1)
    mode.lane.confirmFullSwap(mode.baseline)
    val paperJar = File(fixture.ppDir, "paper.jar").apply { writeText("fake") }

    lateinit var end: PhaseEnd
    fixture.ui.phase {
      end = mode.rebuild(metadata, paperJar, forceFullSwap = true)
      PhaseEnd.None
    }

    assertEquals(PhaseEnd.Watching, end)
    assertTrue(server.sentInstantSwaps.isEmpty(), "the escape hatch must bypass the lane")
    assertTrue(server.calls.contains("stop"))
    assertTrue(fixture.gradle.calls.contains("build"))
  }

  // ── Blue-green mode ─────────────────────────────────────────────────

  @Test
  fun `a patched blue-green rebuild stays on the active slot and never touches the standby`() {
    val (fixture, metadata) = fixtureWithClasses()
    val active =
        FakePaperServerManager(File(fixture.ppDir, "server"), fixture.downloader, fixture.ui)
    val standby =
        FakePaperServerManager(File(fixture.ppDir, "server-swap"), fixture.downloader, fixture.ui)
    val mode =
        BlueGreenMode(
            session = fixture.session,
            servers =
                mapOf(BlueGreenMode.Slot.SERVER to active, BlueGreenMode.Slot.SWAP to standby),
            velocityDownloader = FakeVelocityDownloader(File(fixture.ppDir, "cache")),
            velocityManager = FakeVelocityManager(File(fixture.ppDir, "proxy"), fixture.ui),
        )
    writeLogic(1)
    mode.lane.confirmFullSwap(mode.baselines[BlueGreenMode.Slot.SERVER]!!)
    writeLogic(2)
    val paperJar = File(fixture.ppDir, "paper.jar").apply { writeText("fake") }

    lateinit var result: Pair<BlueGreenMode.Slot, PhaseEnd>
    fixture.ui.phase {
      result = mode.rebuild(metadata, paperJar)
      PhaseEnd.None
    }

    assertEquals(BlueGreenMode.Slot.SERVER to PhaseEnd.Watching, result)
    assertTrue(fixture.terminal.writes.any { it.contains("Patched 1 class (instant)") })
    assertFalse(active.calls.contains("saveWorld"), "a patched cycle must not force a world save")
    assertTrue(standby.calls.isEmpty(), "a patched cycle must never touch the standby")
    assertEquals(1, active.sentInstantSwaps.size)
  }

  // ── Hot-reload mode ─────────────────────────────────────────────────

  @Test
  fun `a patched hot-reload rebuild sends no load request and keeps the baseline current`() {
    val (fixture, metadata) = fixtureWithClasses()
    val server = FakePaperServerManager(fixture.ppDir, fixture.downloader, fixture.ui)
    val mode = HotReloadMode(fixture.session, server)
    writeLogic(1)
    mode.lane.confirmFullSwap(mode.baseline)
    writeLogic(2)

    lateinit var end: PhaseEnd
    fixture.ui.phase {
      end = mode.rebuild(metadata)
      PhaseEnd.None
    }

    assertEquals(PhaseEnd.Watching, end)
    assertTrue(fixture.terminal.writes.any { it.contains("Patched 1 class (instant)") })
    assertTrue(server.sentLoadRequests.isEmpty(), "a patched cycle must not host-reload")
    assertFalse(
        fixture.terminal.writes.any { it.contains("Strategy:") },
        "triggerReload must not run on the patched path",
    )
  }

  @Test
  fun `a no-change hot-reload rebuild reports honestly and does nothing`() {
    val (fixture, metadata) = fixtureWithClasses()
    val server = FakePaperServerManager(fixture.ppDir, fixture.downloader, fixture.ui)
    val mode = HotReloadMode(fixture.session, server)
    writeLogic(1)
    mode.lane.confirmFullSwap(mode.baseline)

    lateinit var end: PhaseEnd
    fixture.ui.phase {
      end = mode.rebuild(metadata)
      PhaseEnd.None
    }

    assertEquals(PhaseEnd.Watching, end)
    assertTrue(
        fixture.terminal.writes.any { it.contains("No code changes") },
        "the honest nothing-to-do line must reach the user; got: ${fixture.terminal.writes}",
    )
    assertTrue(server.sentInstantSwaps.isEmpty(), "nothing changed — nothing to patch")
    assertTrue(server.sentLoadRequests.isEmpty(), "nothing changed — nothing to reload")
  }

  @Test
  fun `a disabled lane falls through to the full path with no instant chatter`() {
    // dev.instant: false is the only rebuild shape with zero mode-level coverage elsewhere: the
    // lane still compiles, then the mode's full swap runs as if the lane didn't exist — silently,
    // because a switched-off feature must not nag every rebuild.
    val fixture =
        DevSessionFixture(
                tempDir,
                config =
                    dev.paperplane.cli.config.PaperPlaneConfig(
                        dev = dev.paperplane.cli.config.DevConfig(instant = false)
                    ),
            )
            .withMetadata()
    val metadata =
        fixture.gradle.nextMetadata!!.copy(
            classesDir = classesDir().absolutePath,
            classesDirs = listOf(classesDir().absolutePath),
        )
    fixture.gradle.nextMetadata = metadata
    fixture.gradle.nextMetadataFast = metadata
    val server = FakePaperServerManager(fixture.ppDir, fixture.downloader, fixture.ui)
    val mode = HotReloadMode(fixture.session, server)
    writeLogic(1)
    mode.lane.confirmFullSwap(mode.baseline)
    writeLogic(2)

    lateinit var end: PhaseEnd
    fixture.ui.phase {
      end = mode.rebuild(metadata)
      PhaseEnd.None
    }

    assertEquals(PhaseEnd.Watching, end)
    assertTrue(server.sentInstantSwaps.isEmpty(), "a disabled lane must never send a patch")
    assertEquals(1, server.sentLoadRequests.size, "the full reload must run instead")
    assertFalse(
        fixture.terminal.writes.any { it.contains("Instant:") },
        "a disabled lane escalates silently; got: ${fixture.terminal.writes}",
    )
  }

  @Test
  fun `an escalated hot-reload rebuild prints the reason and falls through to the host reload`() {
    val (fixture, metadata) = fixtureWithClasses()
    val server = FakePaperServerManager(fixture.ppDir, fixture.downloader, fixture.ui)
    val mode = HotReloadMode(fixture.session, server)
    writeLogic(1)
    mode.lane.confirmFullSwap(mode.baseline)
    // Structural change: an added field escalates.
    File(classesDir(), "com/example/Logic.class")
        .writeBytes(
            BytecodeFixtures.generateClass(
                name = "com/example/Logic",
                fields = listOf(Triple(org.objectweb.asm.Opcodes.ACC_PRIVATE, "speed", "I")),
                methods = listOf(MethodSpec("tick", body = BytecodeFixtures.bodyReturning(1))),
            )
        )

    lateinit var end: PhaseEnd
    fixture.ui.phase {
      end = mode.rebuild(metadata)
      PhaseEnd.None
    }

    assertEquals(PhaseEnd.Watching, end)
    assertTrue(
        fixture.terminal.writes.any { it.contains("field speed") && it.contains("full reload") },
        "the escalation must print its named reason; got: ${fixture.terminal.writes}",
    )
    assertEquals(1, server.sentLoadRequests.size, "the fall-through must host-reload")
  }
}
