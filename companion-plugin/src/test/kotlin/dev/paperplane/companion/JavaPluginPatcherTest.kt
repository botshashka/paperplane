package dev.paperplane.companion

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

class JavaPluginPatcherTest {

    private val transformer = JavaPluginTransformer()

    private fun getJavaPluginBytes(): ByteArray {
        return org.bukkit.plugin.java.JavaPlugin::class.java
            .getResourceAsStream("/org/bukkit/plugin/java/JavaPlugin.class")!!
            .readAllBytes()
    }

    @Test
    fun `transformer produces valid bytecode for JavaPlugin`() {
        val original = getJavaPluginBytes()
        val transformed = transformer.transform(
            null, "org/bukkit/plugin/java/JavaPlugin",
            org.bukkit.plugin.java.JavaPlugin::class.java, null, original
        )

        assertNotNull(transformed)
        // Round-trip through ClassReader — would throw on invalid bytecode
        val reader = ClassReader(transformed!!)
        assertEquals("org/bukkit/plugin/java/JavaPlugin", reader.className)
    }

    @Test
    fun `transformer preserves init method`() {
        val original = getJavaPluginBytes()
        val transformed = transformer.transform(
            null, "org/bukkit/plugin/java/JavaPlugin",
            org.bukkit.plugin.java.JavaPlugin::class.java, null, original
        )!!

        var hasNoArgInit = false
        ClassReader(transformed).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(
                access: Int, name: String?, descriptor: String?,
                signature: String?, exceptions: Array<out String>?
            ): MethodVisitor? {
                if (name == "<init>" && descriptor == "()V") hasNoArgInit = true
                return null
            }
        }, 0)

        assertTrue(hasNoArgInit, "Transformed class should still have <init>()V")
    }

    @Test
    fun `transformer does not modify non-JavaPlugin classes`() {
        val bytes = String::class.java
            .getResourceAsStream("/java/lang/String.class")!!
            .readAllBytes()

        val result = transformer.transform(
            null, "java/lang/String",
            String::class.java, null, bytes
        )

        assertNull(result, "Transformer should return null for non-target classes")
    }

    @Test
    fun `transformer preserves all non-init methods`() {
        val original = getJavaPluginBytes()
        val transformed = transformer.transform(
            null, "org/bukkit/plugin/java/JavaPlugin",
            org.bukkit.plugin.java.JavaPlugin::class.java, null, original
        )!!

        fun collectMethods(bytes: ByteArray): Set<String> {
            val methods = mutableSetOf<String>()
            ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int, name: String?, descriptor: String?,
                    signature: String?, exceptions: Array<out String>?
                ): MethodVisitor? {
                    if (name != "<init>" || descriptor != "()V") {
                        methods.add("$name$descriptor")
                    }
                    return null
                }
            }, 0)
            return methods
        }

        val originalMethods = collectMethods(original)
        val transformedMethods = collectMethods(transformed)
        assertEquals(originalMethods, transformedMethods, "Non-init methods should be preserved")
    }

    @Test
    fun `patchIfNeeded returns false when agent not loaded`() {
        // In test env there's no Java agent
        assertFalse(JavaPluginPatcher.patchIfNeeded())
        assertFalse(JavaPluginPatcher.isPatched)
    }

    @Test
    fun `transformed constructor does not contain ATHROW`() {
        val original = getJavaPluginBytes()
        val transformed = transformer.transform(
            null, "org/bukkit/plugin/java/JavaPlugin",
            org.bukkit.plugin.java.JavaPlugin::class.java, null, original
        )!!

        var hasAthrow = false
        ClassReader(transformed).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(
                access: Int, name: String?, descriptor: String?,
                signature: String?, exceptions: Array<out String>?
            ): MethodVisitor? {
                if (name == "<init>" && descriptor == "()V") {
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitInsn(opcode: Int) {
                            if (opcode == Opcodes.ATHROW) hasAthrow = true
                        }
                    }
                }
                return null
            }
        }, 0)

        assertFalse(hasAthrow, "Transformed <init>()V should not contain ATHROW")
    }
}
