package dev.paperplane.cli.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GradleBridgeTest {

  // ── Build error pattern matching ──────────────────────────────────

  @Test
  fun `pattern matches Java compiler error`() {
    val line =
        "/home/user/project/src/main/java/com/example/Main.java:42: error: cannot find symbol"
    val match = GradleBridge.BUILD_ERROR_PATTERN.find(line)
    assertNotNull(match)
    val (file, lineNum, message) = match!!.destructured
    assertEquals("/home/user/project/src/main/java/com/example/Main.java", file)
    assertEquals("42", lineNum)
    assertEquals("cannot find symbol", message)
  }

  @Test
  fun `pattern matches Kotlin compiler error`() {
    val line =
        "/home/user/project/src/main/kotlin/com/example/Plugin.kt:15: error: unresolved reference: foo"
    val match = GradleBridge.BUILD_ERROR_PATTERN.find(line)
    assertNotNull(match)
    val (file, lineNum, message) = match!!.destructured
    assertTrue(file.endsWith(".kt"))
    assertEquals("15", lineNum)
    assertEquals("unresolved reference: foo", message)
  }

  @Test
  fun `pattern does not match non-error output`() {
    val lines =
        listOf(
            "> Task :compileKotlin FAILED",
            "BUILD FAILED in 2s",
            "3 actionable tasks: 1 executed, 2 up-to-date",
            "* What went wrong:",
            "",
        )
    for (line in lines) {
      assertNull(GradleBridge.BUILD_ERROR_PATTERN.find(line), "Should not match: $line")
    }
  }

  @Test
  fun `pattern matches multiple errors in output`() {
    val output =
        """
        > Task :compileKotlin FAILED
        /src/main/kotlin/A.kt:10: error: type mismatch
        /src/main/kotlin/B.kt:20: error: unresolved reference
        /src/main/java/C.java:30: error: incompatible types
        BUILD FAILED
        """
            .trimIndent()
    val matches = GradleBridge.BUILD_ERROR_PATTERN.findAll(output).toList()
    assertEquals(3, matches.size)
    assertEquals("10", matches[0].destructured.component2())
    assertEquals("20", matches[1].destructured.component2())
    assertEquals("30", matches[2].destructured.component2())
  }

  @Test
  fun `pattern extracts short file path after src`() {
    val line = "/long/path/to/project/src/main/kotlin/com/example/Foo.kt:5: error: something wrong"
    val match = GradleBridge.BUILD_ERROR_PATTERN.find(line)!!
    val file = match.destructured.component1()
    val shortFile = file.substringAfter("src/")
    assertEquals("main/kotlin/com/example/Foo.kt", shortFile)
  }

  @Test
  fun `pattern does not match warning lines`() {
    val line = "/src/main/kotlin/Foo.kt:5: warning: deprecated API"
    assertNull(GradleBridge.BUILD_ERROR_PATTERN.find(line))
  }

  @Test
  fun `pattern handles Windows-style paths`() {
    val line = "C:\\Users\\dev\\src\\Main.java:10: error: ';' expected"
    val match = GradleBridge.BUILD_ERROR_PATTERN.find(line)
    assertNotNull(match)
    assertEquals("10", match!!.destructured.component2())
  }

  // ── isTaskNotFoundMessage ─────────────────────────────────────────
  // Pins the Gradle error-message wordings that GradleBridge.format relies on to distinguish
  // "spotless not configured" from "spotless failed." If a future Gradle bump changes these
  // strings, these tests break loudly instead of silently routing a missing-task error down the
  // generic "format failed" path.

  @Test
  fun `isTaskNotFoundMessage matches classic not-found wording`() {
    val msg = "Task 'spotlessApply' not found in root project 'my-plugin'."
    assertTrue(GradleBridge.isTaskNotFoundMessage(msg, "spotlessApply"))
  }

  @Test
  fun `isTaskNotFoundMessage matches cannot-locate wording`() {
    val msg = "Cannot locate tasks that match 'spotlessApply' as task 'spotlessApply' not found"
    assertTrue(GradleBridge.isTaskNotFoundMessage(msg, "spotlessApply"))
  }

  @Test
  fun `isTaskNotFoundMessage matches lowercase variant`() {
    val msg = "task 'spotlessCheck' not found in project"
    assertTrue(GradleBridge.isTaskNotFoundMessage(msg, "spotlessCheck"))
  }

  @Test
  fun `isTaskNotFoundMessage rejects unrelated errors`() {
    val msg =
        "Execution failed for task ':spotlessApply'. Step 'ktfmt' found problem in file Foo.kt"
    assertEquals(false, GradleBridge.isTaskNotFoundMessage(msg, "spotlessApply"))
  }

  @Test
  fun `isTaskNotFoundMessage rejects null message`() {
    assertEquals(false, GradleBridge.isTaskNotFoundMessage(null, "spotlessApply"))
  }

  @Test
  fun `isTaskNotFoundMessage is task-name specific`() {
    val msg = "Task 'spotlessApply' not found in root project"
    // Looking for a different task — should not match
    assertEquals(false, GradleBridge.isTaskNotFoundMessage(msg, "otherTask"))
  }

  // ── MetadataResult ────────────────────────────────────────────────

  @Test
  fun `MetadataResult Success exposes metadata via metadataOrNull`() {
    val meta =
        ProjectMetadata(
            jarPath = "build/libs/x.jar",
            paperApiVersion = "1.21",
            mainClass = "x.X",
            pluginName = "X",
            projectDir = "/p",
            version = "1.0",
        )
    assertEquals(meta, MetadataResult.Success(meta).metadataOrNull)
  }

  @Test
  fun `MetadataResult PluginNotApplied exposes null via metadataOrNull`() {
    assertNull(MetadataResult.PluginNotApplied.metadataOrNull)
  }

  @Test
  fun `MetadataResult TaskFailed exposes null via metadataOrNull`() {
    assertNull(MetadataResult.TaskFailed.metadataOrNull)
  }
}
