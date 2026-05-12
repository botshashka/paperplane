package dev.paperplane.companion

import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.Instrumentation
import java.security.ProtectionDomain
import java.util.logging.Logger
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Patches JavaPlugin's no-arg constructor so that it no longer throws IllegalStateException when
 * the classloader is not a PluginClassLoader.
 *
 * **Why this is needed**: Directory-based hot-reload uses [DevPluginClassLoader] (which extends
 * URLClassLoader, not PluginClassLoader). JavaPlugin's constructor checks
 * `this.getClass().getClassLoader() instanceof PluginClassLoader` and throws if it fails. Without
 * this patch, we'd have to use Unsafe.allocateInstance() which bypasses ALL constructors and field
 * initializers — causing NPEs for any plugin with object-type field initializers (e.g.,
 * `AtomicInteger`, `HashMap`).
 *
 * **What the patch does**: Replaces the throw path with an early return. When the classloader IS a
 * PluginClassLoader (normal server loading), the original `initialize(this)` call is preserved.
 * When it's NOT (dev-mode loading), the constructor simply returns —
 * `InnerPluginHost` handles the JavaPlugin field setup separately via `ReflectionProbe`.
 */
object JavaPluginPatcher {

  private val logger = Logger.getLogger("PaperPlane")

  @Volatile
  var isPatched: Boolean = false
    private set

  /**
   * Returns the agent's [Instrumentation] handle, or `null` if the agent isn't loaded. Exposed so
   * other components (e.g. the host's NMS-class probe) can reuse the agent's API without
   * duplicating the lookup.
   */
  fun instrumentation(): Instrumentation? = resolveInstrumentation()

  /**
   * Patches JavaPlugin's constructor if Instrumentation is available. Safe to call multiple times —
   * only patches once.
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

    val transformer = JavaPluginTransformer()
    inst.addTransformer(transformer, true)
    return try {
      inst.retransformClasses(org.bukkit.plugin.java.JavaPlugin::class.java)
      isPatched = true
      logger.info("JavaPlugin constructor patched for dev-mode class loading")
      true
    } catch (t: Throwable) {
      // Catch Throwable: JVMTI wraps verifier failures as InternalError, not VerifyError,
      // and future failure modes may use yet other types. Walk the cause chain so the real
      // diagnostic (typically a VerifyError nested in an InternalError) reaches the user.
      val sb = StringBuilder("Failed to patch JavaPlugin constructor: ")
      var cur: Throwable? = t
      var depth = 0
      while (cur != null && depth < 5) {
        if (depth > 0) sb.append(" -> ")
        sb.append(cur.javaClass.name).append(": ").append(cur.message ?: "<no message>")
        cur = cur.cause
        depth++
      }
      logger.warning(sb.toString())
      t.stackTrace.take(5).forEach { logger.warning("  at $it") }
      false
    } finally {
      inst.removeTransformer(transformer)
    }
  }

  private fun resolveInstrumentation(): Instrumentation? {
    return try {
      val agentClass =
          ClassLoader.getSystemClassLoader().loadClass("dev.paperplane.agent.PaperPlaneAgent")
      agentClass.getMethod("getInstrumentation").invoke(null) as? Instrumentation
    } catch (_: ReflectiveOperationException) {
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
 * // else: just return — InnerPluginHost handles setup via ReflectionProbe
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
      classfileBuffer: ByteArray?,
  ): ByteArray? {
    if (className != TARGET_CLASS || classfileBuffer == null) return null

    val reader = ClassReader(classfileBuffer)
    val writer =
        object : ClassWriter(reader, COMPUTE_FRAMES) {
          override fun getCommonSuperClass(type1: String, type2: String): String {
            // Default impl uses Class.forName() on the agent's loader, which can't see
            // Paper-internal types. Resolve via the classloader that loaded JavaPlugin so
            // frame computation works for any future rewrite that introduces reference-type
            // merge points in <init>()V. See git log for f374fce — this guards against the
            // regression that fix shipped for.
            val cl =
                classBeingRedefined?.classLoader ?: return super.getCommonSuperClass(type1, type2)
            return try {
              val c1 = Class.forName(type1.replace('/', '.'), false, cl)
              val c2 = Class.forName(type2.replace('/', '.'), false, cl)
              when {
                c1.isAssignableFrom(c2) -> type1
                c2.isAssignableFrom(c1) -> type2
                c1.isInterface || c2.isInterface -> "java/lang/Object"
                else -> {
                  var current = c1
                  while (!current.isAssignableFrom(c2)) {
                    current = current.superclass
                  }
                  current.name.replace('.', '/')
                }
              }
            } catch (_: ClassNotFoundException) {
              "java/lang/Object"
            }
          }
        }
    val superName = reader.superName

    reader.accept(
        object : ClassVisitor(Opcodes.ASM9, writer) {
          override fun visitMethod(
              access: Int,
              name: String?,
              descriptor: String?,
              signature: String?,
              exceptions: Array<out String>?,
          ): MethodVisitor? {
            // Drop the original no-arg constructor entirely so ASM never traverses its
            // instructions — prevents leftover ops from leaking into the rewritten body
            // emitted in visitEnd().
            if (name == "<init>" && descriptor == "()V") return null
            return super.visitMethod(access, name, descriptor, signature, exceptions)
          }

          override fun visitEnd() {
            emitNoArgInit(cv, superName)
            super.visitEnd()
          }
        },
        ClassReader.EXPAND_FRAMES,
    )

    return writer.toByteArray()
  }

  private fun emitNoArgInit(cv: ClassVisitor, superName: String) {
    val mv = cv.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null) ?: return
    mv.visitCode()
    val skip = Label()

    // super.<init>()
    mv.visitVarInsn(Opcodes.ALOAD, 0)
    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false)

    // ClassLoader cl = this.getClass().getClassLoader();
    mv.visitVarInsn(Opcodes.ALOAD, 0)
    mv.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "java/lang/Object",
        "getClass",
        "()Ljava/lang/Class;",
        false,
    )
    mv.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "java/lang/Class",
        "getClassLoader",
        "()Ljava/lang/ClassLoader;",
        false,
    )
    mv.visitVarInsn(Opcodes.ASTORE, 1)

    // if (cl instanceof PluginClassLoader)
    mv.visitVarInsn(Opcodes.ALOAD, 1)
    mv.visitTypeInsn(Opcodes.INSTANCEOF, "org/bukkit/plugin/java/PluginClassLoader")
    mv.visitJumpInsn(Opcodes.IFEQ, skip)

    // ((PluginClassLoader) cl).initialize(this);
    mv.visitVarInsn(Opcodes.ALOAD, 1)
    mv.visitTypeInsn(Opcodes.CHECKCAST, "org/bukkit/plugin/java/PluginClassLoader")
    mv.visitVarInsn(Opcodes.ALOAD, 0)
    mv.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "org/bukkit/plugin/java/PluginClassLoader",
        "initialize",
        "(Lorg/bukkit/plugin/java/JavaPlugin;)V",
        false,
    )

    mv.visitLabel(skip)
    mv.visitInsn(Opcodes.RETURN)
    // COMPUTE_FRAMES on the writer recomputes maxs and frames; values here are placeholders.
    mv.visitMaxs(0, 0)
    mv.visitEnd()
  }
}
