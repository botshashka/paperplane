package dev.paperplane.cli.watcher

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FileWatcherTest {

  @TempDir lateinit var tempDir: File

  @Test
  fun `detects new file creation`() {
    val srcDir = File(tempDir, "src")
    srcDir.mkdirs()
    File(srcDir, "Existing.kt").writeText("initial")

    val changes = CopyOnWriteArrayList<List<String>>()
    val latch = CountDownLatch(1)
    val watcher =
        FileWatcher(srcDir, debounceMs = 200) {
          changes.add(it)
          latch.countDown()
        }
    watcher.start()

    Thread.sleep(600) // Let initial snapshot settle
    File(srcDir, "New.kt").writeText("new file")

    assertTrue(latch.await(5, TimeUnit.SECONDS), "Change should be detected")
    watcher.stop()

    val allChanged = changes.flatten()
    // The watcher lowercases emitted paths on Windows (case-insensitive-filesystem dedup),
    // so filename assertions here and below must be case-insensitive.
    assertTrue(
        allChanged.any { it.endsWith("New.kt", ignoreCase = true) },
        "New.kt should be in changes",
    )
  }

  @Test
  fun `detects file modification`() {
    val srcDir = File(tempDir, "src")
    srcDir.mkdirs()
    val file = File(srcDir, "Main.kt")
    file.writeText("original")

    val changes = CopyOnWriteArrayList<List<String>>()
    val latch = CountDownLatch(1)
    val watcher =
        FileWatcher(srcDir, debounceMs = 200) {
          changes.add(it)
          latch.countDown()
        }
    watcher.start()

    Thread.sleep(600)
    file.writeText("modified")

    assertTrue(latch.await(5, TimeUnit.SECONDS), "Change should be detected")
    watcher.stop()

    val allChanged = changes.flatten()
    assertTrue(allChanged.any { it.endsWith("Main.kt", ignoreCase = true) })
  }

  @Test
  fun `ignores class files`() {
    val srcDir = File(tempDir, "src")
    srcDir.mkdirs()
    File(srcDir, "Keep.kt").writeText("initial")

    val changes = CopyOnWriteArrayList<List<String>>()
    val latch = CountDownLatch(1)
    val watcher =
        FileWatcher(srcDir, debounceMs = 200) {
          changes.add(it)
          latch.countDown()
        }
    watcher.start()

    Thread.sleep(600)
    // Create both a .class and a .kt file at the same time
    File(srcDir, "Ignored.class").writeText("bytecode")
    File(srcDir, "Detected.kt").writeText("source")

    assertTrue(latch.await(5, TimeUnit.SECONDS))
    watcher.stop()

    val allChanged = changes.flatten()
    assertTrue(allChanged.none { it.endsWith(".class") }, ".class files should be ignored")
    assertTrue(allChanged.any { it.endsWith("Detected.kt", ignoreCase = true) })
  }

  @Test
  fun `ignores build directory`() {
    val srcDir = File(tempDir, "src")
    srcDir.mkdirs()
    val buildDir = File(srcDir, "build")
    buildDir.mkdirs()

    val changes = CopyOnWriteArrayList<List<String>>()
    val latch = CountDownLatch(1)
    val watcher =
        FileWatcher(srcDir, debounceMs = 200) {
          changes.add(it)
          latch.countDown()
        }
    watcher.start()

    Thread.sleep(600)
    File(buildDir, "Output.class").writeText("build output")
    File(srcDir, "Source.kt").writeText("source")

    assertTrue(latch.await(5, TimeUnit.SECONDS))
    watcher.stop()

    val allChanged = changes.flatten()
    assertTrue(allChanged.none { it.contains("build") }, "build/ contents should be ignored")
  }

  @Test
  fun `detects changes to extra files outside the watched dir`() {
    val srcDir = File(tempDir, "src")
    srcDir.mkdirs()
    val buildFile = File(tempDir, "build.gradle.kts")
    buildFile.writeText("// initial")

    val changes = CopyOnWriteArrayList<List<String>>()
    val latch = CountDownLatch(1)
    val watcher =
        FileWatcher(srcDir, debounceMs = 200, extraFiles = listOf(buildFile)) {
          changes.add(it)
          latch.countDown()
        }
    watcher.start()

    Thread.sleep(600)
    buildFile.writeText("// modified")

    assertTrue(latch.await(5, TimeUnit.SECONDS), "Extra-file change should be detected")
    watcher.stop()

    val allChanged = changes.flatten()
    assertTrue(
        allChanged.any { it.endsWith("build.gradle.kts") },
        "build.gradle.kts should appear in changes",
    )
  }

  @Test
  fun `extra files missing on disk are tolerated`() {
    val srcDir = File(tempDir, "src")
    srcDir.mkdirs()
    val missing = File(tempDir, "does-not-exist.kts")
    val present = File(tempDir, "build.gradle.kts")
    present.writeText("// initial")

    val changes = CopyOnWriteArrayList<List<String>>()
    val latch = CountDownLatch(1)
    val watcher =
        FileWatcher(srcDir, debounceMs = 200, extraFiles = listOf(missing, present)) {
          changes.add(it)
          latch.countDown()
        }
    watcher.start()

    Thread.sleep(600)
    present.writeText("// modified")

    assertTrue(latch.await(5, TimeUnit.SECONDS), "Present extra-file change should be detected")
    watcher.stop()
  }

  @Test
  fun `a file saved while onChange is running still triggers the next cycle`() {
    val srcDir = File(tempDir, "src")
    srcDir.mkdirs()
    File(srcDir, "Existing.kt").writeText("initial")

    val changes = CopyOnWriteArrayList<List<String>>()
    val firstFired = CountDownLatch(1)
    val secondFired = CountDownLatch(2)
    val watcher =
        FileWatcher(srcDir, debounceMs = 200) {
          changes.add(it)
          // Simulates a user save landing mid-rebuild: written inside onChange, i.e. exactly the
          // window the post-handle re-snapshot used to silently absorb.
          if (firstFired.count == 1L) File(srcDir, "SavedDuringRebuild.kt").writeText("edit")
          firstFired.countDown()
          secondFired.countDown()
        }
    watcher.start()

    Thread.sleep(600)
    File(srcDir, "First.kt").writeText("change 1")

    assertTrue(firstFired.await(5, TimeUnit.SECONDS), "First change should fire")
    assertTrue(
        secondFired.await(5, TimeUnit.SECONDS),
        "A save landing during onChange must trigger another cycle, not be rebased away",
    )
    watcher.stop()

    val allChanged = changes.flatten()
    assertTrue(allChanged.any { it.endsWith("SavedDuringRebuild.kt", ignoreCase = true) })
  }

  @Test
  fun `detects file deletion`() {
    val srcDir = File(tempDir, "src")
    srcDir.mkdirs()
    val file = File(srcDir, "ToDelete.kt")
    file.writeText("will be deleted")

    val changes = CopyOnWriteArrayList<List<String>>()
    val latch = CountDownLatch(1)
    val watcher =
        FileWatcher(srcDir, debounceMs = 200) {
          changes.add(it)
          latch.countDown()
        }
    watcher.start()

    Thread.sleep(600)
    file.delete()

    assertTrue(latch.await(5, TimeUnit.SECONDS), "Deletion should be detected")
    watcher.stop()

    val allChanged = changes.flatten()
    assertTrue(allChanged.any { it.endsWith("ToDelete.kt", ignoreCase = true) })
  }
}
