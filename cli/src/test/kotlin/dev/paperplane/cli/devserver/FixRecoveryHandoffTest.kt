package dev.paperplane.cli.devserver

import dev.paperplane.cli.config.DevConfig
import dev.paperplane.cli.config.PaperPlaneConfig
import dev.paperplane.cli.testing.DevSessionFixture
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Regression test for the fix-recovery → main-loop handoff bug.
 *
 * Before the fix, `DevSession.runFixWatcher` blocked on `while (true) Thread.sleep(...)` and never
 * exited on a successful fix, leaving all three dev-server modes permanently stuck under the
 * fix-recovery callback. This test drives the real `FileWatcher` against a real temp directory and
 * asserts that `runFixRecoveryAndWait` unblocks and returns the recovered [DevSession.RunningState]
 * exactly when the `onFix` callback reports success.
 *
 * Uses a 100 ms debounce so the poll (500 ms) + debounce (100 ms) cycle completes in ~0.6–1.2 s per
 * change. Total test duration is well under 5 s.
 */
class FixRecoveryHandoffTest {

  @TempDir lateinit var tempDir: File

  private fun newFixture(): DevSessionFixture {
    // src/ must exist for the FileWatcher to have something to poll.
    File(tempDir, "src").mkdirs()
    return DevSessionFixture(
        tempDir = tempDir,
        config = PaperPlaneConfig(dev = DevConfig(debounceMs = FAST_DEBOUNCE_MS)),
    )
  }

  @Test
  fun `runFixRecoveryAndWait returns the recovered state once onFix reports success`() {
    val fixture = newFixture()
    val scriptedMetadata = fixture.gradle.nextMetadata!!
    val scriptedPaperJar = File(tempDir, "paper.jar").apply { writeText("fake") }
    val expectedState = DevSession.RunningState(scriptedMetadata, scriptedPaperJar)

    val invocations = AtomicInteger(0)
    val firstCallEntered = CountDownLatch(1)
    val result = AtomicReference<DevSession.RunningState?>()
    val onShutdownCalled = AtomicInteger(0)

    val thread =
        Thread(
            {
              result.set(
                  fixture.session.runFixRecoveryAndWait(
                      onShutdown = { onShutdownCalled.incrementAndGet() },
                  ) { _ ->
                    val call = invocations.incrementAndGet()
                    firstCallEntered.countDown()
                    // First change event: still broken. Second: recovered.
                    if (call == 1) null else expectedState
                  }
              )
            },
            "test-fix-recovery-wait",
        )
    thread.isDaemon = true
    thread.start()

    // Give the FileWatcher a moment to snapshot its baseline.
    Thread.sleep(WATCHER_STARTUP_MS)

    // First change — should invoke onFix with null (still broken).
    File(tempDir, "src/first.txt").writeText("change 1")
    assertTrue(
        firstCallEntered.await(TEST_STEP_TIMEOUT_MS, TimeUnit.MILLISECONDS),
        "onFix should fire within $TEST_STEP_TIMEOUT_MS ms of the first file change",
    )
    // Thread should still be waiting — the first call returned null (KeepWaiting).
    assertTrue(thread.isAlive, "runFixRecoveryAndWait should keep blocking after a KeepWaiting")

    // Second change — should invoke onFix again and return the scripted state.
    File(tempDir, "src/second.txt").writeText("change 2")
    thread.join(TEST_STEP_TIMEOUT_MS)

    assertTrue(
        !thread.isAlive,
        "runFixRecoveryAndWait should return after onFix reports a Recovered state " +
            "(got ${invocations.get()} invocations)",
    )
    assertNotNull(result.get(), "runFixRecoveryAndWait should return a non-null state on recovery")
    assertSame(expectedState, result.get(), "should hand back the exact state onFix produced")
    assertEquals(0, onShutdownCalled.get(), "onShutdown must not fire on a successful recovery")
    assertEquals(2, invocations.get(), "onFix should have been invoked exactly twice")
  }

  @Test
  fun `runFixRecoveryAndWait returns null and runs onShutdown when interrupted`() {
    val fixture = newFixture()
    val onShutdownCalled = CountDownLatch(1)
    val result = AtomicReference<DevSession.RunningState?>(DevSession.RunningState(
        fixture.gradle.nextMetadata!!,
        File(tempDir, "paper.jar"),
    ))

    val thread =
        Thread(
            {
              result.set(
                  fixture.session.runFixRecoveryAndWait(
                      onShutdown = { onShutdownCalled.countDown() },
                  ) { _ ->
                    null // always "still broken"
                  }
              )
            },
            "test-fix-recovery-interrupt",
        )
    thread.isDaemon = true
    thread.start()

    // Let the watcher start + first poll.
    Thread.sleep(WATCHER_STARTUP_MS)
    thread.interrupt()
    thread.join(TEST_STEP_TIMEOUT_MS)

    assertTrue(!thread.isAlive, "runFixRecoveryAndWait must exit on interrupt")
    assertNull(result.get(), "should return null when interrupted")
    assertTrue(
        onShutdownCalled.await(TEST_STEP_TIMEOUT_MS, TimeUnit.MILLISECONDS),
        "onShutdown must run on the interrupt path",
    )
  }

  companion object {
    private const val FAST_DEBOUNCE_MS = 100L

    /** Give the [dev.paperplane.cli.watcher.FileWatcher] enough time to snapshot its baseline. */
    private const val WATCHER_STARTUP_MS = 750L

    /**
     * Per-step timeout. FileWatcher polls at 500 ms, debounce is [FAST_DEBOUNCE_MS], so a full
     * change → callback cycle takes up to ~1100 ms. 5 s leaves ample headroom for CI jitter.
     */
    private const val TEST_STEP_TIMEOUT_MS = 5000L
  }
}
