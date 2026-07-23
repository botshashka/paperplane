package dev.paperplane.cli.devserver.instant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BaselineTrackerTest {

  private fun candidate(
      vararg classes: Pair<String, ByteArray>,
      resources: Map<String, Long> = emptyMap(),
      sourceDirs: List<String> = emptyList(),
  ) = BuildCandidate(classes.toMap(), resources, sourceDirs)

  @Test
  fun `starts unseeded and confirmFullSwap seeds the whole candidate`() {
    val tracker = BaselineTracker()
    assertNull(tracker.confirmed())

    val c = candidate("com.example.A" to byteArrayOf(1), resources = mapOf("config.yml" to 7L))
    tracker.confirmFullSwap(c)

    assertEquals(c, tracker.confirmed())
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

    val baseline = tracker.confirmed()!!
    assertTrue(baseline.classes.getValue("com.example.A").contentEquals(byteArrayOf(10)))
    assertTrue(
        baseline.classes.getValue("com.example.B").contentEquals(byteArrayOf(2)),
        "unconfirmed classes must stay at the loaded baseline",
    )
    assertEquals(7L, baseline.resourceCrcs["config.yml"], "patches never touch resources")
  }

  @Test
  fun `confirmPatched keeps the baseline's output layout, not the candidate's`() {
    // A patch is only admitted when both captures already agree on the layout, so carrying the
    // candidate's dirs would be equivalent — but the invariant is that only a confirmed *load*
    // may move the layout, and this pins it.
    val tracker = BaselineTracker()
    tracker.confirmFullSwap(
        candidate("com.example.A" to byteArrayOf(1), sourceDirs = listOf("classes:/loaded"))
    )

    tracker.confirmPatched(
        candidate("com.example.A" to byteArrayOf(2), sourceDirs = listOf("classes:/elsewhere")),
        listOf("com.example.A"),
    )

    assertEquals(listOf("classes:/loaded"), tracker.confirmed()!!.sourceDirs)
  }

  @Test
  fun `confirmPatched adds confirmed new classes to the baseline`() {
    val tracker = BaselineTracker()
    tracker.confirmFullSwap(candidate("com.example.A" to byteArrayOf(1)))

    val next = candidate("com.example.A" to byteArrayOf(1), "com.example.New" to byteArrayOf(9))
    tracker.confirmPatched(next, listOf("com.example.New"))

    assertTrue(
        tracker.confirmed()!!.classes.getValue("com.example.New").contentEquals(byteArrayOf(9))
    )
  }

  @Test
  fun `confirmPatched on an unseeded tracker is a no-op`() {
    val tracker = BaselineTracker()
    tracker.confirmPatched(candidate("com.example.A" to byteArrayOf(1)), listOf("com.example.A"))
    assertNull(tracker.confirmed(), "a patch can only be confirmed against a seeded baseline")
  }

  @Test
  fun `reset discards the baseline until the next confirmed load`() {
    val tracker = BaselineTracker()
    tracker.confirmFullSwap(candidate("com.example.A" to byteArrayOf(1)))
    tracker.reset()
    assertNull(tracker.confirmed())
  }

  @Test
  fun `two trackers are independent - the blue-green per-slot requirement`() {
    val blue = BaselineTracker()
    val green = BaselineTracker()
    blue.confirmFullSwap(candidate("com.example.A" to byteArrayOf(1)))

    assertTrue(blue.confirmed() != null)
    assertNull(green.confirmed())
  }
}
