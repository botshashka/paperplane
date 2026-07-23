package dev.paperplane.companion

import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock

class DevPluginClassLoaderTest {

  @TempDir lateinit var tempDir: File

  private lateinit var server: ServerMock
  private var classLoader: DevPluginClassLoader? = null

  @BeforeEach
  fun setup() {
    server = MockBukkit.mock()
  }

  @AfterEach
  fun teardown() {
    classLoader?.close()
    MockBukkit.unmock()
  }

  // ── Helpers ─────────────────────────────────────────────────────────

  private fun writeClassFile(dir: File, internalName: String): File {
    val classFile = File(dir, "$internalName.class")
    classFile.parentFile.mkdirs()
    classFile.writeBytes(BytecodeFixtures.emptyClass(internalName))
    return classFile
  }

  // ── Tests ───────────────────────────────────────────────────────────

  @Test
  fun `loads class from directory URL`() {
    val classDir = File(tempDir, "classes")
    writeClassFile(classDir, "com/example/FromDir")

    classLoader =
        DevPluginClassLoader(
            arrayOf(classDir.toURI().toURL()),
            ClassLoader.getSystemClassLoader(),
            server.pluginManager,
        )

    val clazz = classLoader!!.loadClass("com.example.FromDir")
    assertEquals("com.example.FromDir", clazz.name)
  }

  @Test
  fun `loads class from JAR URL`() {
    val jarFile = File(tempDir, "test.jar")
    JarOutputStream(jarFile.outputStream()).use { jos ->
      jos.putNextEntry(JarEntry("com/example/InJar.class"))
      jos.write(BytecodeFixtures.emptyClass("com/example/InJar"))
      jos.closeEntry()
    }

    classLoader =
        DevPluginClassLoader(
            arrayOf(jarFile.toURI().toURL()),
            ClassLoader.getSystemClassLoader(),
            server.pluginManager,
        )

    val clazz = classLoader!!.loadClass("com.example.InJar")
    assertEquals("com.example.InJar", clazz.name)
  }

  @Test
  fun `delegates to parent for JDK classes`() {
    classLoader =
        DevPluginClassLoader(emptyArray(), ClassLoader.getSystemClassLoader(), server.pluginManager)

    val clazz = classLoader!!.loadClass("java.lang.String")
    assertEquals(String::class.java, clazz)
  }

  @Test
  fun `throws ClassNotFoundException when class exists nowhere`() {
    classLoader =
        DevPluginClassLoader(emptyArray(), ClassLoader.getSystemClassLoader(), server.pluginManager)

    assertThrows(ClassNotFoundException::class.java) {
      classLoader!!.loadClass("com.example.DoesNotExist")
    }
  }

  @Test
  fun `resources loadable via getResourceAsStream`() {
    val resourceDir = File(tempDir, "resources")
    resourceDir.mkdirs()
    val testFile = File(resourceDir, "test.txt")
    testFile.writeText("hello paperplane")

    classLoader =
        DevPluginClassLoader(
            arrayOf(resourceDir.toURI().toURL()),
            ClassLoader.getSystemClassLoader(),
            server.pluginManager,
        )

    val stream = classLoader!!.getResourceAsStream("test.txt")
    assertNotNull(stream)
    assertEquals("hello paperplane", stream!!.bufferedReader().readText())
  }

  @Test
  fun `close does not throw`() {
    classLoader =
        DevPluginClassLoader(emptyArray(), ClassLoader.getSystemClassLoader(), server.pluginManager)

    classLoader!!.close()
    classLoader = null // prevent double-close in @AfterEach
  }
}
