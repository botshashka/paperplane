package dev.paperplane.cli.devserver.instant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BaselineTrackerTest {

  private fun candidate(
      vararg classes: Pair<String, ByteArray>,
      resources: Map<String, Long> = emptyMap(),
  ) = BuildCandidate(classes.toMap(), resources)

  @Test
  fun `starts unseeded and confirmFullSwap seeds the whole candidate`() {
    val tracker = BaselineTracker()
    assertFalse(tracker.seeded)

    val c = candidate("com.example.A" to byteArrayOf(1), resources = mapOf("config.yml" to 7L))
    tracker.confirmFullSwap(c)

    assertTrue(tracker.seeded)
    assertEquals(c, tracker.baseline)
  }

  @Test
  fun `confirmPatched advances only the confirmed classes and never resources`() {
    val tracker = BaselineTracker()
    tracker.confirmFullSwap(
        candidate(
            "com.example.A" to byteArrayOf(1),
            "com.example.B" to byteArrayOf(2),
            resources = mapOf("config.yml" to 7L),
        )
    )

    val next =
        candidate(
            "com.example.A" to byteArrayOf(10),
            "com.example.B" to byteArrayOf(20),
            resources = mapOf("config.yml" to 99L),
        )
    tracker.confirmPatched(next, listOf("com.example.A"))

    val baseline = tracker.baseline!!
    assertTrue(baseline.classes.getValue("com.example.A").contentEquals(byteArrayOf(10)))
    assertTrue(
        baseline.classes.getValue("com.example.B").contentEquals(byteArrayOf(2)),
        "unconfirmed classes must stay at the loaded baseline",
    )
    assertEquals(7L, baseline.resourceCrcs["config.yml"], "patches never touch resources")
  }

  @Test
  fun `confirmPatched adds confirmed new classes to the baseline`() {
    val tracker = BaselineTracker()
    tracker.confirmFullSwap(candidate("com.example.A" to byteArrayOf(1)))

    val next = candidate("com.example.A" to byteArrayOf(1), "com.example.New" to byteArrayOf(9))
    tracker.confirmPatched(next, listOf("com.example.New"))

    assertTrue(tracker.baseline!!.classes.getValue("com.example.New").contentEquals(byteArrayOf(9)))
  }

  @Test
  fun `confirmPatched on an unseeded tracker is a no-op`() {
    val tracker = BaselineTracker()
    tracker.confirmPatched(candidate("com.example.A" to byteArrayOf(1)), listOf("com.example.A"))
    assertFalse(tracker.seeded, "a patch can only be confirmed against a seeded baseline")
  }

  @Test
  fun `reset discards the baseline until the next confirmed load`() {
    val tracker = BaselineTracker()
    tracker.confirmFullSwap(candidate("com.example.A" to byteArrayOf(1)))
    tracker.reset()
    assertFalse(tracker.seeded)
    assertNull(tracker.baseline)
  }

  @Test
  fun `two trackers are independent - the blue-green per-slot requirement`() {
    val blue = BaselineTracker()
    val green = BaselineTracker()
    blue.confirmFullSwap(candidate("com.example.A" to byteArrayOf(1)))

    assertTrue(blue.seeded)
    assertFalse(green.seeded)
  }
}
