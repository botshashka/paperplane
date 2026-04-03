package dev.paperplane.companion

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.logging.Logger

class HotSwapperJbrTest {

    private val logger = Logger.getLogger("HotSwapperJbrTest")
    private val swapper = HotSwapper(logger)
    private val detector = ClassChangeDetector()

    @TempDir
    lateinit var tempDir: File

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun generateClassWithMethods(
        name: String,
        methods: List<Pair<String, String>>
    ): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)

        // Constructor
        val init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        init.visitCode()
        init.visitVarInsn(Opcodes.ALOAD, 0)
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        init.visitInsn(Opcodes.RETURN)
        init.visitMaxs(1, 1)
        init.visitEnd()

        for ((mname, desc) in methods) {
            val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, mname, desc, null, null)
            mv.visitCode()
            mv.visitInsn(Opcodes.RETURN)
            mv.visitMaxs(1, 1)
            mv.visitEnd()
        }

        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun generateClassWithMethodBody(
        name: String,
        methodName: String,
        bodyInstructions: (org.objectweb.asm.MethodVisitor) -> Unit
    ): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)

        val init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        init.visitCode()
        init.visitVarInsn(Opcodes.ALOAD, 0)
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        init.visitInsn(Opcodes.RETURN)
        init.visitMaxs(1, 1)
        init.visitEnd()

        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, "()V", null, null)
        mv.visitCode()
        bodyInstructions(mv)
        mv.visitMaxs(2, 2)
        mv.visitEnd()

        cw.visitEnd()
        return cw.toByteArray()
    }

    // ── Tests ───────────────────────────────────────────────────────────

    @Test
    fun `isEnhancedRedefinitionAvailable returns false on standard JDK`() {
        val vendor = System.getProperty("java.vendor", "")
        val vmName = System.getProperty("java.vm.name", "")
        val runningOnJbr = vendor.contains("JetBrains", ignoreCase = true) ||
                vmName.contains("JBR", ignoreCase = true)

        // On standard JDK (CI/dev), should be false; on JBR, should be true
        assertEquals(runningOnJbr, swapper.isEnhancedRedefinitionAvailable())
    }

    @Test
    fun `structural change detected when method is added`() {
        val oldBytes = generateClassWithMethods(
            "com/example/Plugin",
            listOf("onEnable" to "()V")
        )
        val newBytes = generateClassWithMethods(
            "com/example/Plugin",
            listOf("onEnable" to "()V", "onDisable" to "()V")
        )

        // ClassChangeDetector should flag this as structural
        assertFalse(detector.isMethodBodyOnly(oldBytes, newBytes))
    }

    @Test
    fun `method-body-only change detected when only implementation changes`() {
        val oldBytes = generateClassWithMethodBody("com/example/Plugin", "onEnable") { mv ->
            mv.visitInsn(Opcodes.RETURN)
        }
        val newBytes = generateClassWithMethodBody("com/example/Plugin", "onEnable") { mv ->
            // Different body: push and pop before returning
            mv.visitInsn(Opcodes.ICONST_1)
            mv.visitInsn(Opcodes.POP)
            mv.visitInsn(Opcodes.RETURN)
        }

        // Same structure, different body -> should be method-body-only
        assertTrue(detector.isMethodBodyOnly(oldBytes, newBytes))
    }

    @Test
    fun `structural change when field is added`() {
        val cw1 = ClassWriter(0)
        cw1.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/example/State", null, "java/lang/Object", null)
        cw1.visitEnd()
        val oldBytes = cw1.toByteArray()

        val cw2 = ClassWriter(0)
        cw2.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/example/State", null, "java/lang/Object", null)
        cw2.visitField(Opcodes.ACC_PRIVATE, "counter", "I", null, null)
        cw2.visitEnd()
        val newBytes = cw2.toByteArray()

        assertFalse(detector.isMethodBodyOnly(oldBytes, newBytes))
    }

    @Test
    fun `structural change when interface is added`() {
        val cw1 = ClassWriter(0)
        cw1.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/example/Handler", null, "java/lang/Object", null)
        cw1.visitEnd()
        val oldBytes = cw1.toByteArray()

        val cw2 = ClassWriter(0)
        cw2.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/example/Handler", null, "java/lang/Object", arrayOf("java/io/Serializable"))
        cw2.visitEnd()
        val newBytes = cw2.toByteArray()

        assertFalse(detector.isMethodBodyOnly(oldBytes, newBytes))
    }

    @Test
    fun `redefine returns UNAVAILABLE on standard JDK without agent`() {
        // On a standard JDK without a Java agent, instrumentation is null
        val classBytes = generateClassWithMethods("com/example/Foo", listOf("bar" to "()V"))
        val classFile = File(tempDir, "com/example/Foo.class")
        classFile.parentFile.mkdirs()
        classFile.writeBytes(classBytes)

        val result = swapper.redefine(
            listOf("com.example.Foo"),
            Thread.currentThread().contextClassLoader,
            listOf(tempDir.absolutePath)
        )

        assertEquals(HotSwapResult.UNAVAILABLE, result)
    }
}
