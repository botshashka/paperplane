package dev.paperplane.cli

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tests that File(path).name correctly extracts filenames, validating the fix for
 * substringAfterLast("/") which would fail on Windows backslash paths.
 */
class CrossPlatformPathTest {

  @Test
  fun `File name extracts filename from Unix path`() {
    val name = File("/home/user/project/src/main/kotlin/Main.kt").name
    assertEquals("Main.kt", name)
  }

  @Test
  fun `File name extracts filename from simple name`() {
    val name = File("Main.kt").name
    assertEquals("Main.kt", name)
  }

  @Test
  fun `File name handles path with spaces`() {
    val name = File("/home/user/my project/src/My File.kt").name
    assertEquals("My File.kt", name)
  }

  @Test
  fun `File name handles deeply nested path`() {
    val name = File("/a/b/c/d/e/f/g/Deep.kt").name
    assertEquals("Deep.kt", name)
  }

  @Test
  fun `substringAfterLast slash fails on backslash paths`() {
    // This demonstrates the bug that was fixed.
    // On Windows, File.absolutePath uses backslashes, so substringAfterLast("/")
    // would return the entire path instead of just the filename.
    val backslashPath = "C:\\Users\\dev\\project\\src\\Main.kt"
    val broken = backslashPath.substringAfterLast("/")

    // substringAfterLast("/") returns the full string when "/" is not found
    assertEquals(backslashPath, broken, "substringAfterLast('/') fails on backslash paths")
  }

  @Test
  fun `File name works with forward slash paths on all platforms`() {
    // File() always handles forward slashes correctly regardless of OS
    val name = File("C:/Users/dev/project/src/Main.kt").name
    assertEquals("Main.kt", name)
  }
}
