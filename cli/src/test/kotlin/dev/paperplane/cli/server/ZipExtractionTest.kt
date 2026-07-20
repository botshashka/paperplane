package dev.paperplane.cli.server

import dev.paperplane.cli.util.Platform
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class ZipExtractionTest {

  @TempDir lateinit var tempDir: File

  private fun createTestZip(vararg entries: Pair<String, String>): File {
    val zipFile = File(tempDir, "test.zip")
    ZipOutputStream(zipFile.outputStream()).use { zos ->
      for ((name, content) in entries) {
        if (name.endsWith("/")) {
          zos.putNextEntry(ZipEntry(name))
          zos.closeEntry()
        } else {
          zos.putNextEntry(ZipEntry(name))
          zos.write(content.toByteArray())
          zos.closeEntry()
        }
      }
    }
    return zipFile
  }

  @Test
  fun `extracts files with correct content`() {
    val zip = createTestZip("hello.txt" to "Hello, World!", "sub/nested.txt" to "Nested content")
    val targetDir = File(tempDir, "extracted")
    targetDir.mkdirs()

    Platform.extractZip(zip, targetDir)

    assertEquals("Hello, World!", File(targetDir, "hello.txt").readText())
    assertEquals("Nested content", File(targetDir, "sub/nested.txt").readText())
  }

  @Test
  fun `creates intermediate directories`() {
    val zip = createTestZip("a/b/c/deep.txt" to "deep")
    val targetDir = File(tempDir, "extracted")
    targetDir.mkdirs()

    Platform.extractZip(zip, targetDir)

    assertTrue(File(targetDir, "a/b/c/deep.txt").exists())
    assertEquals("deep", File(targetDir, "a/b/c/deep.txt").readText())
  }

  @Test
  fun `handles directory entries`() {
    val zip = createTestZip("mydir/" to "", "mydir/file.txt" to "content")
    val targetDir = File(tempDir, "extracted")
    targetDir.mkdirs()

    Platform.extractZip(zip, targetDir)

    assertTrue(File(targetDir, "mydir").isDirectory)
    assertEquals("content", File(targetDir, "mydir/file.txt").readText())
  }

  @Test
  fun `rejects zip slip attack`() {
    val zip = createTestZip("../../etc/passwd" to "malicious")
    val targetDir = File(tempDir, "extracted")
    targetDir.mkdirs()

    assertThrows<IOException> { Platform.extractZip(zip, targetDir) }
  }

  @Test
  fun `extracts JBR-like structure with bin directory`() {
    val zip =
        createTestZip(
            "jbrsdk-21.0.10/" to "",
            "jbrsdk-21.0.10/bin/" to "",
            "jbrsdk-21.0.10/bin/java" to "#!/bin/sh\necho fake java",
            "jbrsdk-21.0.10/bin/java.exe" to "fake exe",
            "jbrsdk-21.0.10/lib/" to "",
            "jbrsdk-21.0.10/lib/modules" to "modules",
        )
    val targetDir = File(tempDir, "extracted")
    targetDir.mkdirs()

    Platform.extractZip(zip, targetDir)

    assertTrue(File(targetDir, "jbrsdk-21.0.10/bin/java").exists())
    assertTrue(File(targetDir, "jbrsdk-21.0.10/bin/java.exe").exists())
    assertTrue(File(targetDir, "jbrsdk-21.0.10/lib/modules").exists())
  }

  @Test
  fun `handles empty zip`() {
    val zip = createTestZip()
    val targetDir = File(tempDir, "extracted")
    targetDir.mkdirs()

    Platform.extractZip(zip, targetDir)

    // Should complete without error; target dir has no new files
    assertEquals(0, targetDir.listFiles()?.size ?: 0)
  }
}
