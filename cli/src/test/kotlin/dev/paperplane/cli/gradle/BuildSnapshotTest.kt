package dev.paperplane.cli.gradle

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class BuildSnapshotTest {

    @TempDir
    lateinit var tempDir: File

    // ── Snapshot taking ───────────────────────────────────────────────

    @Test
    fun `empty directory produces empty snapshot`() {
        val classesDir = File(tempDir, "classes")
        classesDir.mkdirs()

        val snapshot = BuildSnapshot(classesDir)
        val result = snapshot.take()

        assertTrue(result.isEmpty())
    }

    @Test
    fun `nonexistent directory produces empty snapshot`() {
        val classesDir = File(tempDir, "does-not-exist")

        val snapshot = BuildSnapshot(classesDir)
        val result = snapshot.take()

        assertTrue(result.isEmpty())
    }

    @Test
    fun `empty directory produces empty diff`() {
        val classesDir = File(tempDir, "classes")
        classesDir.mkdirs()

        val snapshot = BuildSnapshot(classesDir)
        val diff = BuildSnapshot.diff(emptyMap(), snapshot.take())

        assertTrue(diff.modified.isEmpty())
        assertTrue(diff.added.isEmpty())
        assertTrue(diff.removed.isEmpty())
    }

    @Test
    fun `single class file produces correct FQCN`() {
        val classesDir = File(tempDir, "classes")
        val classFile = File(classesDir, "com/example/MyPlugin.class")
        classFile.parentFile.mkdirs()
        classFile.writeBytes(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))

        val snapshot = BuildSnapshot(classesDir)
        val result = snapshot.take()

        assertEquals(1, result.size)
        assertTrue(result.containsKey("com/example/MyPlugin.class"))
    }

    @Test
    fun `nested package paths produce correct FQCNs`() {
        val classesDir = File(tempDir, "classes")
        val files = listOf(
            "com/example/deep/nested/ClassA.class",
            "com/example/ClassB.class",
            "org/other/ClassC.class"
        )
        for (path in files) {
            val f = File(classesDir, path)
            f.parentFile.mkdirs()
            f.writeBytes(byteArrayOf(1, 2, 3))
        }

        val snapshot = BuildSnapshot(classesDir)
        val result = snapshot.take()

        assertEquals(3, result.size)
        assertTrue(result.containsKey("com/example/deep/nested/ClassA.class"))
        assertTrue(result.containsKey("com/example/ClassB.class"))
        assertTrue(result.containsKey("org/other/ClassC.class"))
    }

    @Test
    fun `inner classes produce correct FQCNs via pathToFqcn`() {
        assertEquals("com.example.MyPlugin\$Companion", BuildSnapshot.pathToFqcn("com/example/MyPlugin\$Companion.class"))
        assertEquals("com.example.MyPlugin\$1", BuildSnapshot.pathToFqcn("com/example/MyPlugin\$1.class"))
    }

    @Test
    fun `inner class files are included in snapshot`() {
        val classesDir = File(tempDir, "classes")
        val files = listOf(
            "com/example/MyPlugin.class",
            "com/example/MyPlugin\$Companion.class",
            "com/example/MyPlugin\$1.class"
        )
        for (path in files) {
            val f = File(classesDir, path)
            f.parentFile.mkdirs()
            f.writeBytes(byteArrayOf(1, 2, 3))
        }

        val snapshot = BuildSnapshot(classesDir)
        val result = snapshot.take()

        assertEquals(3, result.size)
    }

    @Test
    fun `non-class files are ignored`() {
        val classesDir = File(tempDir, "classes")
        val classFile = File(classesDir, "com/example/MyPlugin.class")
        classFile.parentFile.mkdirs()
        classFile.writeBytes(byteArrayOf(1, 2, 3))

        // Non-class files that should be ignored
        File(classesDir, "META-INF/main.kotlin_module").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(4, 5, 6))
        }
        File(classesDir, "com/example/notes.txt").writeText("not a class")

        val snapshot = BuildSnapshot(classesDir)
        val result = snapshot.take()

        assertEquals(1, result.size)
        assertTrue(result.containsKey("com/example/MyPlugin.class"))
    }

    // ── Diff detection ────────────────────────────────────────────────

    @Test
    fun `diff with no changes produces empty ClassChanges`() {
        val classesDir = File(tempDir, "classes")
        val classFile = File(classesDir, "com/example/MyPlugin.class")
        classFile.parentFile.mkdirs()
        classFile.writeBytes(byteArrayOf(1, 2, 3))

        val snapshot = BuildSnapshot(classesDir)
        val previous = snapshot.take()
        val diff = BuildSnapshot.diff(previous, snapshot.take())

        assertTrue(diff.modified.isEmpty())
        assertTrue(diff.added.isEmpty())
        assertTrue(diff.removed.isEmpty())
    }

    @Test
    fun `diff detects modified file`() {
        val classesDir = File(tempDir, "classes")
        val classFile = File(classesDir, "com/example/MyPlugin.class")
        classFile.parentFile.mkdirs()
        classFile.writeBytes(byteArrayOf(1, 2, 3))

        val snapshot = BuildSnapshot(classesDir)
        val previous = snapshot.take()

        // Modify the file
        classFile.writeBytes(byteArrayOf(4, 5, 6))
        val diff = BuildSnapshot.diff(previous, snapshot.take())

        assertEquals(listOf("com.example.MyPlugin"), diff.modified)
        assertTrue(diff.added.isEmpty())
        assertTrue(diff.removed.isEmpty())
    }

    @Test
    fun `diff detects new file added`() {
        val classesDir = File(tempDir, "classes")
        val classFile = File(classesDir, "com/example/MyPlugin.class")
        classFile.parentFile.mkdirs()
        classFile.writeBytes(byteArrayOf(1, 2, 3))

        val snapshot = BuildSnapshot(classesDir)
        val previous = snapshot.take()

        // Add a new file
        File(classesDir, "com/example/NewClass.class").writeBytes(byteArrayOf(7, 8, 9))
        val diff = BuildSnapshot.diff(previous, snapshot.take())

        assertTrue(diff.modified.isEmpty())
        assertEquals(listOf("com.example.NewClass"), diff.added)
        assertTrue(diff.removed.isEmpty())
    }

    @Test
    fun `diff detects removed file`() {
        val classesDir = File(tempDir, "classes")
        val classFile = File(classesDir, "com/example/MyPlugin.class")
        classFile.parentFile.mkdirs()
        classFile.writeBytes(byteArrayOf(1, 2, 3))
        val otherFile = File(classesDir, "com/example/Helper.class")
        otherFile.writeBytes(byteArrayOf(4, 5, 6))

        val snapshot = BuildSnapshot(classesDir)
        val previous = snapshot.take()

        // Remove a file
        otherFile.delete()
        val diff = BuildSnapshot.diff(previous, snapshot.take())

        assertTrue(diff.modified.isEmpty())
        assertTrue(diff.added.isEmpty())
        assertEquals(listOf("com.example.Helper"), diff.removed)
    }

    @Test
    fun `diff detects mixed changes`() {
        val classesDir = File(tempDir, "classes")
        val modified = File(classesDir, "com/example/Modified.class")
        modified.parentFile.mkdirs()
        modified.writeBytes(byteArrayOf(1, 2, 3))
        val removed = File(classesDir, "com/example/Removed.class")
        removed.writeBytes(byteArrayOf(4, 5, 6))
        val unchanged = File(classesDir, "com/example/Unchanged.class")
        unchanged.writeBytes(byteArrayOf(7, 8, 9))

        val snapshot = BuildSnapshot(classesDir)
        val previous = snapshot.take()

        // Modify one, remove one, add one
        modified.writeBytes(byteArrayOf(10, 11, 12))
        removed.delete()
        File(classesDir, "com/example/Added.class").writeBytes(byteArrayOf(13, 14, 15))

        val diff = BuildSnapshot.diff(previous, snapshot.take())

        assertEquals(listOf("com.example.Modified"), diff.modified)
        assertEquals(listOf("com.example.Added"), diff.added)
        assertEquals(listOf("com.example.Removed"), diff.removed)
    }

    // ── noNewOrRemovedClasses ─────────────────────────────────────────

    @Test
    fun `noNewOrRemovedClasses is true when only modified`() {
        val changes = ClassChanges(
            modified = listOf("com.example.MyPlugin"),
            added = emptyList(),
            removed = emptyList()
        )
        assertTrue(changes.noNewOrRemovedClasses)
    }

    @Test
    fun `noNewOrRemovedClasses is false when any added`() {
        val changes = ClassChanges(
            modified = listOf("com.example.MyPlugin"),
            added = listOf("com.example.NewClass"),
            removed = emptyList()
        )
        assertFalse(changes.noNewOrRemovedClasses)
    }

    @Test
    fun `noNewOrRemovedClasses is false when any removed`() {
        val changes = ClassChanges(
            modified = emptyList(),
            added = emptyList(),
            removed = listOf("com.example.OldClass")
        )
        assertFalse(changes.noNewOrRemovedClasses)
    }

    @Test
    fun `noNewOrRemovedClasses is true when all lists empty`() {
        val changes = ClassChanges(
            modified = emptyList(),
            added = emptyList(),
            removed = emptyList()
        )
        assertTrue(changes.noNewOrRemovedClasses)
    }

    // ── CRC32 behavior ───────────────────────────────────────────────

    @Test
    fun `CRC32 is deterministic for same content`() {
        val file = File(tempDir, "test.class")
        file.writeBytes(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 0, 0, 0, 52))

        val crc1 = BuildSnapshot.crc32(file)
        val crc2 = BuildSnapshot.crc32(file)

        assertEquals(crc1, crc2)
    }

    @Test
    fun `CRC32 detects single byte change`() {
        val file1 = File(tempDir, "a.class")
        val file2 = File(tempDir, "b.class")

        val bytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        file1.writeBytes(bytes)
        file2.writeBytes(bytes.clone().also { it[7] = 9 })

        val crc1 = BuildSnapshot.crc32(file1)
        val crc2 = BuildSnapshot.crc32(file2)

        assertNotEquals(crc1, crc2)
    }

    // ── pathToFqcn ───────────────────────────────────────────────────

    @Test
    fun `pathToFqcn converts forward slashes to dots`() {
        assertEquals("com.example.MyPlugin", BuildSnapshot.pathToFqcn("com/example/MyPlugin.class"))
    }

    @Test
    fun `pathToFqcn handles backslashes`() {
        assertEquals("com.example.MyPlugin", BuildSnapshot.pathToFqcn("com\\example\\MyPlugin.class"))
    }
}
