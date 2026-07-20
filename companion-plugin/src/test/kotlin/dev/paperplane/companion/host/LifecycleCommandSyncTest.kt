package dev.paperplane.companion.host

import java.lang.reflect.InvocationTargetException
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [LifecycleCommandSync] against recording fakes. The class is deliberately
 * constructor-injected (the probe resolves the real Paper internals), so these tests pin the fire
 * contract — ordering, exact arguments, exception propagation — without a Paper server. The
 * resolution side is covered by `ReflectionProbeTest`; the end-to-end behavior (commands actually
 * appearing) can only be proven on a real server and is exercised by the Brigadier spike script.
 */
class LifecycleCommandSyncTest {

  private val eventType = Any()
  private val cause = Any()

  private fun sync(runner: Any, paperCommands: Any): LifecycleCommandSync =
      LifecycleCommandSync(
          runner,
          runner.javaClass.getMethod(
              "callReloadableRegistrarEvent",
              Any::class.java,
              Any::class.java,
              Any::class.java,
              Any::class.java,
          ),
          paperCommands,
          paperCommands.javaClass.getMethod("setValid"),
          eventType,
          cause,
      )

  @Test
  fun `fire calls setValid before the re-collection, mirroring Paper's reload sequence`() {
    // Paper's reload sites call PaperCommands.setValid() strictly BEFORE firing the event —
    // the previous fire left the registrar invalid, and firing against an invalid registrar
    // rejects every registration. The order is the contract, not a detail.
    val log = mutableListOf<String>()
    sync(RecordingRunner(log), RecordingPaperCommands(log)).fire()

    assertEquals(listOf("setValid", "callReloadableRegistrarEvent"), log)
  }

  @Test
  fun `fire passes the resolved event type, registrar, Plugin owner class, and cause`() {
    val log = mutableListOf<String>()
    val runner = RecordingRunner(log)
    val paperCommands = RecordingPaperCommands(log)

    sync(runner, paperCommands).fire()

    val args = runner.lastArgs!!
    assertSame(eventType, args[0], "must fire the resolved COMMANDS event type")
    assertSame(paperCommands, args[1], "the registrar must be the PaperCommands instance itself")
    assertSame(
        Plugin::class.java,
        args[2],
        "owner class must be Plugin — the same filter Paper's own reload uses for regular plugins",
    )
    assertSame(cause, args[3], "must fire with the resolved RELOAD cause")
  }

  @Test
  fun `fire propagates handler exceptions for the host to downgrade`() {
    val log = mutableListOf<String>()
    val ex =
        assertThrows(InvocationTargetException::class.java) {
          sync(ThrowingRunner(), RecordingPaperCommands(log)).fire()
        }
    assertTrue(
        ex.targetException.message!!.contains("handler boom"),
        "the handler's own exception must ride out as the target exception",
    )
  }
}

/** Fake with the same shape [LifecycleCommandSync] invokes on the real LifecycleEventRunner. */
class RecordingRunner(private val log: MutableList<String>) {
  var lastArgs: List<Any?>? = null

  @Suppress("unused") // invoked reflectively
  fun callReloadableRegistrarEvent(eventType: Any?, registrar: Any?, owner: Any?, cause: Any?) {
    log += "callReloadableRegistrarEvent"
    lastArgs = listOf(eventType, registrar, owner, cause)
  }
}

/** Fake with the same shape [LifecycleCommandSync] invokes on the real PaperCommands. */
class RecordingPaperCommands(private val log: MutableList<String>) {
  @Suppress("unused") // invoked reflectively
  fun setValid() {
    log += "setValid"
  }
}

/** Models a plugin's COMMANDS handler throwing during collection. */
class ThrowingRunner {
  @Suppress("unused", "UNUSED_PARAMETER") // invoked reflectively
  fun callReloadableRegistrarEvent(eventType: Any?, registrar: Any?, owner: Any?, cause: Any?) {
    error("handler boom")
  }
}
