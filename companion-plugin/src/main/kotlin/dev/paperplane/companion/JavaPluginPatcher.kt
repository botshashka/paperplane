package dev.paperplane.companion

import org.objectweb.asm.*
import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.Instrumentation
import java.security.ProtectionDomain
import java.util.logging.Logger

/**
 * Patches JavaPlugin's no-arg constructor so that it no longer throws
 * IllegalStateException when the classloader is not a PluginClassLoader.
 *
 * **Why this is needed**: Directory-based hot-reload uses [DevPluginClassLoader]
 * (which extends URLClassLoader, not PluginClassLoader). JavaPlugin's constructor
 * checks `this.getClass().getClassLoader() instanceof PluginClassLoader` and throws
 * if it fails. Without this patch, we'd have to use Unsafe.allocateInstance() which
 * bypasses ALL constructors and field initializers — causing NPEs for any plugin
 * with object-type field initializers (e.g., `AtomicInteger`, `HashMap`).
 *
 * **What the patch does**: Replaces the throw path with an early return. When
 * the classloader IS a PluginClassLoader (normal server loading), the original
 * `initialize(this)` call is preserved. When it's NOT (dev-mode loading),
 * the constructor simply returns — [PaperInternals.initializePlugin] handles
 * the JavaPlugin field setup separately.
 */
object JavaPluginPatcher {

    private val logger = Logger.getLogger("PaperPlane")

    @Volatile
    var isPatched: Boolean = false
        private set

    /**
     * Patches JavaPlugin's constructor if Instrumentation is available.
     * Safe to call multiple times — only patches once.
     *
     * @return true if patched (now or previously), false if agent not available
     */
    fun patchIfNeeded(): Boolean {
        if (isPatched) return true

        val inst = resolveInstrumentation()
        if (inst == null) {
            logger.warning(
                "JavaPluginPatcher: Instrumentation not available (agent not loaded). " +
                "Directory-based reload will fall back to Unsafe.allocateInstance()."
            )
            return false
        }

        return try {
            val transformer = JavaPluginTransformer()
            inst.addTransformer(transformer, true)
            inst.retransformClasses(org.bukkit.plugin.java.JavaPlugin::class.java)
            inst.removeTransformer(transformer)
            isPatched = true
            logger.info("JavaPlugin constructor patched for dev-mode class loading")
            true
        } catch (e: Exception) {
            logger.warning("Failed to patch JavaPlugin constructor: ${e.message}")
            false
        }
    }

    private fun resolveInstrumentation(): Instrumentation? {
        return try {
            val agentClass = ClassLoader.getSystemClassLoader()
                .loadClass("dev.paperplane.agent.PaperPlaneAgent")
            agentClass.getMethod("getInstrumentation").invoke(null) as? Instrumentation
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * ASM ClassFileTransformer that rewrites JavaPlugin's no-arg constructor.
 *
 * Original:
 * ```
 * super();
 * ClassLoader cl = this.getClass().getClassLoader();
 * if (!(cl instanceof PluginClassLoader)) throw new IllegalStateException(...);
 * ((PluginClassLoader) cl).initialize(this);
 * ```
 *
 * Transformed:
 * ```
 * super();
 * ClassLoader cl = this.getClass().getClassLoader();
 * if (cl instanceof PluginClassLoader) {
 *     ((PluginClassLoader) cl).initialize(this);
 * }
 * // else: just return — PaperInternals handles setup
 * ```
 */
internal class JavaPluginTransformer : ClassFileTransformer {

    companion object {
        private const val TARGET_CLASS = "org/bukkit/plugin/java/JavaPlugin"
        private const val PCL_CLASS = "org/bukkit/plugin/java/PluginClassLoader"
    }

    override fun transform(
        loader: ClassLoader?,
        className: String?,
        classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain?,
        classfileBuffer: ByteArray?
    ): ByteArray? {
        if (className != TARGET_CLASS || classfileBuffer == null) return null

        val reader = ClassReader(classfileBuffer)
        val writer = ClassWriter(reader, ClassWriter.COMPUTE_FRAMES)
        val superName = reader.superName

        reader.accept(object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitMethod(
                access: Int,
                name: String?,
                descriptor: String?,
                signature: String?,
                exceptions: Array<out String>?
            ): MethodVisitor {
                val mv = super.visitMethod(access, name, descriptor, signature, exceptions)

                // Only rewrite the no-arg constructor
                if (name == "<init>" && descriptor == "()V") {
                    return RewrittenInitVisitor(mv, superName)
                }
                return mv
            }
        }, ClassReader.EXPAND_FRAMES)

        return writer.toByteArray()
    }
}

/**
 * Replaces the entire body of JavaPlugin.<init>()V with a safe version
 * that skips the PluginClassLoader check instead of throwing.
 */
private class RewrittenInitVisitor(
    mv: MethodVisitor,
    private val superName: String
) : MethodVisitor(Opcodes.ASM9, mv) {

    private var started = false

    override fun visitCode() {
        super.visitCode()
        started = true

        val skipLabel = Label()

        // super() — call whatever the actual superclass is
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false)

        // ClassLoader cl = this.getClass().getClassLoader();
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getClassLoader", "()Ljava/lang/ClassLoader;", false)
        mv.visitVarInsn(Opcodes.ASTORE, 1)

        // if (cl instanceof PluginClassLoader)
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitTypeInsn(Opcodes.INSTANCEOF, "org/bukkit/plugin/java/PluginClassLoader")
        mv.visitJumpInsn(Opcodes.IFEQ, skipLabel)

        // ((PluginClassLoader) cl).initialize(this);
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitTypeInsn(Opcodes.CHECKCAST, "org/bukkit/plugin/java/PluginClassLoader")
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "org/bukkit/plugin/java/PluginClassLoader",
            "initialize",
            "(Lorg/bukkit/plugin/java/JavaPlugin;)V",
            false
        )

        // skip:
        mv.visitLabel(skipLabel)
        mv.visitFrame(Opcodes.F_APPEND, 1, arrayOf<Any>("java/lang/ClassLoader"), 0, null)
        mv.visitInsn(Opcodes.RETURN)

        mv.visitMaxs(2, 2)
        mv.visitEnd()
    }

    // Suppress all original instructions since we replaced the entire body
    override fun visitInsn(opcode: Int) { if (!started) super.visitInsn(opcode) }
    override fun visitIntInsn(opcode: Int, operand: Int) { if (!started) super.visitIntInsn(opcode, operand) }
    override fun visitVarInsn(opcode: Int, `var`: Int) { if (!started) super.visitVarInsn(opcode, `var`) }
    override fun visitTypeInsn(opcode: Int, type: String?) { if (!started) super.visitTypeInsn(opcode, type) }
    override fun visitFieldInsn(opcode: Int, owner: String?, name: String?, descriptor: String?) { if (!started) super.visitFieldInsn(opcode, owner, name, descriptor) }
    override fun visitMethodInsn(opcode: Int, owner: String?, name: String?, descriptor: String?, isInterface: Boolean) { if (!started) super.visitMethodInsn(opcode, owner, name, descriptor, isInterface) }
    override fun visitJumpInsn(opcode: Int, label: Label?) { if (!started) super.visitJumpInsn(opcode, label) }
    override fun visitLabel(label: Label?) { if (!started) super.visitLabel(label) }
    override fun visitLdcInsn(value: Any?) { if (!started) super.visitLdcInsn(value) }
    override fun visitIincInsn(`var`: Int, increment: Int) { if (!started) super.visitIincInsn(`var`, increment) }
    override fun visitTableSwitchInsn(min: Int, max: Int, dflt: Label?, vararg labels: Label?) { if (!started) super.visitTableSwitchInsn(min, max, dflt, *labels) }
    override fun visitLookupSwitchInsn(dflt: Label?, keys: IntArray?, labels: Array<out Label>?) { if (!started) super.visitLookupSwitchInsn(dflt, keys, labels) }
    override fun visitMultiANewArrayInsn(descriptor: String?, numDimensions: Int) { if (!started) super.visitMultiANewArrayInsn(descriptor, numDimensions) }
    override fun visitTryCatchBlock(start: Label?, end: Label?, handler: Label?, type: String?) { if (!started) super.visitTryCatchBlock(start, end, handler, type) }
    override fun visitLocalVariable(name: String?, descriptor: String?, signature: String?, start: Label?, end: Label?, index: Int) { /* suppress */ }
    override fun visitLineNumber(line: Int, start: Label?) { /* suppress */ }
    override fun visitFrame(type: Int, numLocal: Int, local: Array<out Any>?, numStack: Int, stack: Array<out Any>?) { if (!started) super.visitFrame(type, numLocal, local, numStack, stack) }
    override fun visitMaxs(maxStack: Int, maxLocals: Int) { /* already emitted in visitCode */ }
    override fun visitEnd() { /* already emitted in visitCode */ }
}
