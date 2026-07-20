package dev.paperplane.companion.host

import java.lang.instrument.Instrumentation
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [InnerPluginHost.containsNmsClasses]. Validates the rewritten NMS-detection helper
 * (Instrumentation-based) against curated `allLoadedClasses` returns, replacing the previous
 * JDK-internal `ClassLoader.classes` reflection that had no test coverage.
 */
class InnerPluginHostNmsDetectionTest {

  private class FakeClassLoader(name: String) : ClassLoader(null) {
    private val n = name

    override fun toString() = "FakeClassLoader($n)"
  }

  private fun fakeInstrumentation(loaded: Array<Class<*>>): Instrumentation {
    val handler = InvocationHandler { _, method: Method, args: Array<out Any?>? ->
      when (method.name) {
        "getAllLoadedClasses" -> loaded
        // Defensive: any other method called by the test path is a bug — surface it loudly.
        else -> throw UnsupportedOperationException("Unexpected call: ${method.name}")
      }
    }
    return Proxy.newProxyInstance(
        Instrumentation::class.java.classLoader,
        arrayOf(Instrumentation::class.java),
        handler,
    ) as Instrumentation
  }

  // `Class` is final, so we can't proxy it. Instead, ASM-generate a minimal class whose name
  // matches the prefix under test and define it in a controlled classloader.
  private fun makeClassInLoader(cl: FakeClassLoader, internalName: String): Class<*> {
    // Minimal valid class file for `public class <internalName> {}` extending Object.
    val cw = org.objectweb.asm.ClassWriter(0)
    cw.visit(
        org.objectweb.asm.Opcodes.V17,
        org.objectweb.asm.Opcodes.ACC_PUBLIC,
        internalName,
        null,
        "java/lang/Object",
        null,
    )
    val ctor = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
    ctor.visitCode()
    ctor.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0)
    ctor.visitMethodInsn(
        org.objectweb.asm.Opcodes.INVOKESPECIAL,
        "java/lang/Object",
        "<init>",
        "()V",
        false,
    )
    ctor.visitInsn(org.objectweb.asm.Opcodes.RETURN)
    ctor.visitMaxs(1, 1)
    ctor.visitEnd()
    cw.visitEnd()
    val bytes = cw.toByteArray()
    return DefiningLoader(cl).defineHere(internalName.replace('/', '.'), bytes)
  }

  /** Forwards everything to its parent but exposes `defineClass` for the test. */
  private class DefiningLoader(parent: ClassLoader) : ClassLoader(parent) {
    fun defineHere(name: String, bytes: ByteArray): Class<*> =
        defineClass(name, bytes, 0, bytes.size)
  }

  @Test
  fun `containsNmsClasses returns true when an NMS class is defined by the target loader`() {
    val target = FakeClassLoader("inner-plugin")
    val nmsClass = makeClassInLoader(target, "net/minecraft/server/FakeNms")
    val inst = fakeInstrumentation(arrayOf(nmsClass, String::class.java))

    assertTrue(InnerPluginHost.containsNmsClasses(inst, nmsClass.classLoader))
  }

  @Test
  fun `containsNmsClasses returns true for craftbukkit prefix`() {
    val target = FakeClassLoader("inner-plugin")
    val cbClass = makeClassInLoader(target, "org/bukkit/craftbukkit/FakeCb")
    val inst = fakeInstrumentation(arrayOf(cbClass))

    assertTrue(InnerPluginHost.containsNmsClasses(inst, cbClass.classLoader))
  }

  @Test
  fun `containsNmsClasses returns false when no NMS class is loaded`() {
    val target = FakeClassLoader("inner-plugin")
    val benignClass = makeClassInLoader(target, "com/example/Plain")
    val inst = fakeInstrumentation(arrayOf(benignClass, String::class.java, Int::class.java))

    assertFalse(InnerPluginHost.containsNmsClasses(inst, benignClass.classLoader))
  }

  @Test
  fun `containsNmsClasses ignores NMS classes owned by a different loader`() {
    val target = FakeClassLoader("inner-plugin")
    val other = FakeClassLoader("paper-server")
    val nmsClass = makeClassInLoader(other, "net/minecraft/server/FromPaper")
    val inst = fakeInstrumentation(arrayOf(nmsClass))

    // The plugin's classloader didn't define this NMS class — Paper did. Don't flag the plugin.
    assertFalse(
        InnerPluginHost.containsNmsClasses(inst, target),
        "Only classes defined by the inner-plugin loader should trip the check",
    )
  }
}
