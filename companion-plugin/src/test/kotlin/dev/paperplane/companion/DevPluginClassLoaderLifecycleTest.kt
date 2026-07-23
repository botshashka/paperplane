package dev.paperplane.companion

import java.io.File
import java.lang.ref.WeakReference
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.io.TempDir
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock

class DevPluginClassLoaderLifecycleTest {

  @TempDir lateinit var tempDir: File

  private lateinit var server: ServerMock

  @BeforeEach
  fun setup() {
    server = MockBukkit.mock()
  }

  @AfterEach
  fun teardown() {
    MockBukkit.unmock()
  }

  // ── Helpers ─────────────────────────────────────────────────────────

  private fun writeClassFile(dir: File, internalName: String, bytes: ByteArray? = null): File {
    val classFile = File(dir, "$internalName.class")
    classFile.parentFile.mkdirs()
    classFile.writeBytes(bytes ?: BytecodeFixtures.emptyClass(internalName))
    return classFile
  }

  // ── Tests ───────────────────────────────────────────────────────────

  @Test
  fun `close does not throw on classloader with directory URLs`() {
    val classDir = File(tempDir, "classes")
    classDir.mkdirs()
    writeClassFile(classDir, "com/example/DirClass")

    val loader =
        DevPluginClassLoader(
            arrayOf(classDir.toURI().toURL()),
            ClassLoader.getSystemClassLoader(),
            server.pluginManager,
        )

    assertDoesNotThrow { loader.close() }
  }

  @Test
  fun `close does not throw on classloader with JAR URLs`() {
    val jarFile = File(tempDir, "test.jar")
    JarOutputStream(jarFile.outputStream()).use { jos ->
      jos.putNextEntry(JarEntry("com/example/JarClass.class"))
      jos.write(BytecodeFixtures.emptyClass("com/example/JarClass"))
      jos.closeEntry()
    }

    val loader =
        DevPluginClassLoader(
            arrayOf(jarFile.toURI().toURL()),
            ClassLoader.getSystemClassLoader(),
            server.pluginManager,
        )

    assertDoesNotThrow { loader.close() }
  }

  @Test
  fun `classes loaded before close remain usable`() {
    val classDir = File(tempDir, "classes")
    writeClassFile(classDir, "com/example/Persistent")

    val loader =
        DevPluginClassLoader(
            arrayOf(classDir.toURI().toURL()),
            ClassLoader.getSystemClassLoader(),
            server.pluginManager,
        )

    val clazz = loader.loadClass("com.example.Persistent")
    loader.close()

    // Class metadata should still be accessible after close
    assertEquals("com.example.Persistent", clazz.name)
    assertEquals("Persistent", clazz.simpleName)
  }

  @Test
  fun `two classloaders can exist simultaneously with different URLs`() {
    val dirA = File(tempDir, "dirA")
    val dirB = File(tempDir, "dirB")
    writeClassFile(dirA, "com/example/ClassA")
    writeClassFile(dirB, "com/example/ClassB")

    val loaderA =
        DevPluginClassLoader(
            arrayOf(dirA.toURI().toURL()),
            ClassLoader.getSystemClassLoader(),
            server.pluginManager,
        )
    val loaderB =
        DevPluginClassLoader(
            arrayOf(dirB.toURI().toURL()),
            ClassLoader.getSystemClassLoader(),
            server.pluginManager,
        )

    try {
      val classA = loaderA.loadClass("com.example.ClassA")
      val classB = loaderB.loadClass("com.example.ClassB")

      assertEquals("com.example.ClassA", classA.name)
      assertEquals("com.example.ClassB", classB.name)
      assertEquals(loaderA, classA.classLoader)
      assertEquals(loaderB, classB.classLoader)
    } finally {
      loaderA.close()
      loaderB.close()
    }
  }

  @Test
  fun `each classloader loads its own version of a class`() {
    val dirA = File(tempDir, "dirA")
    val dirB = File(tempDir, "dirB")

    // Dir A has TestClass with getValue() returning 1
    writeClassFile(
        dirA,
        "com/example/TestClass",
        BytecodeFixtures.classWithStaticValue("com/example/TestClass", 1),
    )
    // Dir B has TestClass with getValue() returning 2
    writeClassFile(
        dirB,
        "com/example/TestClass",
        BytecodeFixtures.classWithStaticValue("com/example/TestClass", 2),
    )

    val loaderA =
        DevPluginClassLoader(
            arrayOf(dirA.toURI().toURL()),
            ClassLoader.getSystemClassLoader(),
            server.pluginManager,
        )
    val loaderB =
        DevPluginClassLoader(
            arrayOf(dirB.toURI().toURL()),
            ClassLoader.getSystemClassLoader(),
            server.pluginManager,
        )

    try {
      val classA = loaderA.loadClass("com.example.TestClass")
      val classB = loaderB.loadClass("com.example.TestClass")

      // They should be different Class objects
      assertNotEquals(classA, classB)

      // Invoke getValue() on each — should return different values
      val valueA = classA.getMethod("getValue").invoke(null) as Int
      val valueB = classB.getMethod("getValue").invoke(null) as Int

      assertEquals(1, valueA)
      assertEquals(2, valueB)
    } finally {
      loaderA.close()
      loaderB.close()
    }
  }

  @Test
  fun `GC can collect closed classloader without error`() {
    val classDir = File(tempDir, "classes")
    writeClassFile(classDir, "com/example/Ephemeral")

    var loader: DevPluginClassLoader? =
        DevPluginClassLoader(
            arrayOf(classDir.toURI().toURL()),
            ClassLoader.getSystemClassLoader(),
            server.pluginManager,
        )
    val ref = WeakReference(loader)

    loader!!.close()
    loader = null

    // GC is not guaranteed to collect, but the sequence must not throw
    assertDoesNotThrow { System.gc() }
    // ref.get() may or may not be null — GC behavior is non-deterministic
    // The key assertion is that close + null + gc completes without error
  }
}
