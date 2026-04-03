package dev.paperplane.companion

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Compares class bytecode to determine if a change is method-body-only
 * (safe for Instrumentation.redefineClasses) or structural (requires
 * full classloader reload).
 *
 * This is an optional optimization — the JVM itself is the authoritative
 * validator. But pre-checking avoids the cost of catching
 * UnsupportedOperationException on structural changes.
 */
class ClassChangeDetector {

    /**
     * Returns true if the only differences between old and new bytecode
     * are within method bodies (Code attributes). Returns false if any
     * structural element changed (fields, method signatures, interfaces, etc.).
     */
    fun isMethodBodyOnly(oldBytes: ByteArray, newBytes: ByteArray): Boolean {
        return structuralSignature(oldBytes) == structuralSignature(newBytes)
    }

    /**
     * Extracts a deterministic structural signature from class bytecode.
     * Includes: class name, superclass, interfaces, field declarations,
     * method declarations (name + descriptor + access flags).
     * Excludes: method bodies (Code attributes), line numbers, local vars.
     */
    private fun structuralSignature(bytes: ByteArray): String {
        val reader = ClassReader(bytes)
        val sig = StringBuilder()

        reader.accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(
                version: Int, access: Int, name: String?,
                signature: String?, superName: String?, interfaces: Array<out String>?
            ) {
                sig.append("CLASS:$access:$name:$superName:")
                sig.append(interfaces?.sorted()?.joinToString(",") ?: "")
                sig.append("|")
            }

            override fun visitField(
                access: Int, name: String?, descriptor: String?,
                signature: String?, value: Any?
            ): FieldVisitor? {
                sig.append("FIELD:$access:$name:$descriptor|")
                return null
            }

            override fun visitMethod(
                access: Int, name: String?, descriptor: String?,
                signature: String?, exceptions: Array<out String>?
            ): MethodVisitor? {
                sig.append("METHOD:$access:$name:$descriptor:")
                sig.append(exceptions?.sorted()?.joinToString(",") ?: "")
                sig.append("|")
                return null // Don't visit method body — that's what we're ignoring
            }
        }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)

        return sig.toString()
    }
}
