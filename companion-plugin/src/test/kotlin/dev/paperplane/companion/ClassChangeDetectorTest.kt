package dev.paperplane.companion

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class ClassChangeDetectorTest {

    private val detector = ClassChangeDetector()

    // ── Helper ──────────────────────────────────────────────────────────

    private fun generateClass(
        name: String = "com/example/Test",
        superName: String = "java/lang/Object",
        interfaces: Array<String>? = null,
        fields: List<Triple<Int, String, String>> = emptyList(),
        methods: List<Triple<Int, String, String>> = emptyList(),
        methodBodyCustomizer: ((ClassWriter, Int, String, String) -> Unit)? = null
    ): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, superName, interfaces)
        for ((access, fname, desc) in fields) {
            cw.visitField(access, fname, desc, null, null)
        }
        for ((access, mname, desc) in methods) {
            if (methodBodyCustomizer != null) {
                methodBodyCustomizer(cw, access, mname, desc)
            } else {
                val mv = cw.visitMethod(access, mname, desc, null, null)
                mv.visitCode()
                mv.visitInsn(Opcodes.RETURN)
                mv.visitMaxs(1, 1)
                mv.visitEnd()
            }
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    // ── Method-body-only changes (should return true) ───────────────────

    @Test
    fun `identical bytecode is method-body-only`() {
        val bytes = generateClass(
            methods = listOf(Triple(Opcodes.ACC_PUBLIC, "foo", "()V"))
        )
        assertTrue(detector.isMethodBodyOnly(bytes, bytes))
    }

    @Test
    fun `same structure with different method body is method-body-only`() {
        val oldBytes = generateClass(
            methods = listOf(Triple(Opcodes.ACC_PUBLIC, "foo", "()V"))
        ) { cw, access, mname, desc ->
            val mv = cw.visitMethod(access, mname, desc, null, null)
            mv.visitCode()
            mv.visitInsn(Opcodes.RETURN)
            mv.visitMaxs(1, 1)
            mv.visitEnd()
        }

        val newBytes = generateClass(
            methods = listOf(Triple(Opcodes.ACC_PUBLIC, "foo", "()V"))
        ) { cw, access, mname, desc ->
            val mv = cw.visitMethod(access, mname, desc, null, null)
            mv.visitCode()
            // Different body: push 0 onto stack, pop it, then return
            mv.visitInsn(Opcodes.ICONST_0)
            mv.visitInsn(Opcodes.POP)
            mv.visitInsn(Opcodes.RETURN)
            mv.visitMaxs(1, 1)
            mv.visitEnd()
        }

        assertTrue(detector.isMethodBodyOnly(oldBytes, newBytes))
    }

    @Test
    fun `empty class to empty class is method-body-only`() {
        val a = generateClass()
        val b = generateClass()
        assertTrue(detector.isMethodBodyOnly(a, b))
    }

    // ── Structural changes (should return false) ────────────────────────

    @Test
    fun `adding a method is structural`() {
        val old = generateClass()
        val new = generateClass(
            methods = listOf(Triple(Opcodes.ACC_PUBLIC, "newMethod", "()V"))
        )
        assertFalse(detector.isMethodBodyOnly(old, new))
    }

    @Test
    fun `removing a method is structural`() {
        val old = generateClass(
            methods = listOf(Triple(Opcodes.ACC_PUBLIC, "foo", "()V"))
        )
        val new = generateClass()
        assertFalse(detector.isMethodBodyOnly(old, new))
    }

    @Test
    fun `changing method descriptor is structural`() {
        val old = generateClass(
            methods = listOf(Triple(Opcodes.ACC_PUBLIC, "foo", "()V"))
        )
        val new = generateClass(
            methods = listOf(Triple(Opcodes.ACC_PUBLIC, "foo", "(I)V"))
        )
        assertFalse(detector.isMethodBodyOnly(old, new))
    }

    @Test
    fun `adding a field is structural`() {
        val old = generateClass()
        val new = generateClass(
            fields = listOf(Triple(Opcodes.ACC_PRIVATE, "x", "I"))
        )
        assertFalse(detector.isMethodBodyOnly(old, new))
    }

    @Test
    fun `removing a field is structural`() {
        val old = generateClass(
            fields = listOf(Triple(Opcodes.ACC_PRIVATE, "x", "I"))
        )
        val new = generateClass()
        assertFalse(detector.isMethodBodyOnly(old, new))
    }

    @Test
    fun `changing field type is structural`() {
        val old = generateClass(
            fields = listOf(Triple(Opcodes.ACC_PRIVATE, "x", "I"))
        )
        val new = generateClass(
            fields = listOf(Triple(Opcodes.ACC_PRIVATE, "x", "J"))
        )
        assertFalse(detector.isMethodBodyOnly(old, new))
    }

    @Test
    fun `adding an interface is structural`() {
        val old = generateClass()
        val new = generateClass(interfaces = arrayOf("java/io/Serializable"))
        assertFalse(detector.isMethodBodyOnly(old, new))
    }

    @Test
    fun `removing an interface is structural`() {
        val old = generateClass(interfaces = arrayOf("java/io/Serializable"))
        val new = generateClass()
        assertFalse(detector.isMethodBodyOnly(old, new))
    }

    @Test
    fun `changing superclass is structural`() {
        val old = generateClass(superName = "java/lang/Object")
        val new = generateClass(superName = "java/lang/Number")
        assertFalse(detector.isMethodBodyOnly(old, new))
    }

    @Test
    fun `changing method access modifier is structural`() {
        val old = generateClass(
            methods = listOf(Triple(Opcodes.ACC_PUBLIC, "foo", "()V"))
        )
        val new = generateClass(
            methods = listOf(Triple(Opcodes.ACC_PRIVATE, "foo", "()V"))
        )
        assertFalse(detector.isMethodBodyOnly(old, new))
    }
}
