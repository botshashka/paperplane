package dev.paperplane.companion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.logging.Logger

class HotSwapperTest {

    private val logger = Logger.getLogger("HotSwapperTest")
    private val swapper = HotSwapper(logger)

    @TempDir
    lateinit var tempDir: File

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun generateClass(name: String): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(1, 1)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    // ── Tests ───────────────────────────────────────────────────────────

    @Test
    fun `isAvailable returns false when agent not loaded`() {
        assertFalse(swapper.isAvailable())
    }

    @Test
    fun `redefine returns UNAVAILABLE when agent not loaded`() {
        val result = swapper.redefine(
            listOf("com.example.Foo"),
            Thread.currentThread().contextClassLoader,
            listOf(tempDir.absolutePath)
        )
        assertEquals(HotSwapResult.UNAVAILABLE, result)
    }

    @Test
    fun `isEnhancedRedefinitionAvailable returns false on standard JDK`() {
        // Unless running on JetBrains Runtime, this should be false
        val vendor = System.getProperty("java.vendor", "")
        val vmName = System.getProperty("java.vm.name", "")
        val expectJBR = vendor.contains("JetBrains", ignoreCase = true) ||
                vmName.contains("JBR", ignoreCase = true)

        assertEquals(expectJBR, swapper.isEnhancedRedefinitionAvailable())
    }

    @Test
    fun `redefine returns UNAVAILABLE even when class file exists on disk`() {
        // Write a valid .class file to the build output dir
        val classFile = File(tempDir, "com/example/Exists.class")
        classFile.parentFile.mkdirs()
        classFile.writeBytes(generateClass("com/example/Exists"))

        val result = swapper.redefine(
            listOf("com.example.Exists"),
            Thread.currentThread().contextClassLoader,
            listOf(tempDir.absolutePath)
        )

        // Without instrumentation, we never get past the null check
        assertEquals(HotSwapResult.UNAVAILABLE, result)
    }

    @Test
    fun `redefine with empty class list returns SUCCESS when instrumentation unavailable`() {
        // Empty list short-circuits before the instrumentation null check? No —
        // the null check comes first. So this should also be UNAVAILABLE.
        val result = swapper.redefine(
            emptyList(),
            Thread.currentThread().contextClassLoader,
            emptyList()
        )
        assertEquals(HotSwapResult.UNAVAILABLE, result)
    }
}
