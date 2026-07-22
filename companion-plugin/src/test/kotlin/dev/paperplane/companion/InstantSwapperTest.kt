package dev.paperplane.companion

import java.io.File
import java.net.URLClassLoader
import java.util.Base64
import java.util.logging.Logger
import java.util.zip.CRC32
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

/**
 * Drives [InstantSwapper.apply] end to end with a [FakeInstrumentation], a scripted CRC registry
 * (standing in for the agent's load-hook registry), and real classloaders over ASM-generated
 * classes: the verification ladder (loaded-CRC vs expected), the already-current skip, the JVM
 * veto, and both new-class paths (overlay splice into a URLClassLoader — the test JVM runs with
 * the same `--add-opens` the LaunchSpec guarantees — and refusal on loaders that can't receive
 * classes).
 */
class InstantSwapperTest {

  @TempDir lateinit var tempDir: File

  private val crcRegistry = mutableMapOf<Pair<ClassLoader, String>, Long>()
  private val patchedClasses = mutableSetOf<Pair<ClassLoader, String>>()

  private fun generateClass(internalName: String, marker: Int): ByteArray {
    val cw = ClassWriter(0)
    cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
    val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "marker", "()I", null, null)
    mv.visitCode()
    mv.visitLdcInsn(marker)
    mv.visitInsn(Opcodes.IRETURN)
    mv.visitMaxs(2, 1)
    mv.visitEnd()
    cw.visitEnd()
    return cw.toByteArray()
  }

  private fun crc(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

  private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

  /** Writes [bytes] as `com/example/<Simple>.class` under a fresh dir and returns a loader on it. */
  private fun loaderWith(fqcn: String, bytes: ByteArray): URLClassLoader {
    val dir = File(tempDir, "classes-${System.nanoTime()}").apply { mkdirs() }
    val target = File(dir, fqcn.replace('.', '/') + ".class")
    target.parentFile.mkdirs()
    target.writeBytes(bytes)
    return URLClassLoader(arrayOf(dir.toURI().toURL()), null)
  }

  private fun swapper(inst: FakeInstrumentation?) =
      InstantSwapper(
          Logger.getLogger("test"),
          File(tempDir, "overlay"),
          instrumentationProvider = { inst },
          loadedCrcProvider = { loader, name ->
            crcRegistry[loader to name] ?: AgentAccess.UNKNOWN_CRC
          },
          wasPatchedProvider = { loader, name -> (loader to name) in patchedClasses },
          crcUpdater = { loader, name, crc ->
            crcRegistry[loader to name] = crc
            patchedClasses.add(loader to name)
          },
      )

  private fun patchRequest(fqcn: String, expectedCrc: Long, newBytes: ByteArray) =
      HostInstantSwapRequest(
          requestId = "i1",
          pluginName = "Sample",
          classes = listOf(HostInstantClassEntry(fqcn, expectedCrc, b64(newBytes))),
      )

  // ── Patch verification ladder ───────────────────────────────────────

  @Test
  fun `a verified body patch redefines atomically and reports applied`() {
    val fqcn = "com.example.Patch"
    val v1 = generateClass("com/example/Patch", 1)
    val v2 = generateClass("com/example/Patch", 2)
    val loader = loaderWith(fqcn, v1)
    crcRegistry[loader to fqcn] = crc(v1)
    val inst = FakeInstrumentation()

    val outcome = swapper(inst).apply(patchRequest(fqcn, crc(v1), v2), loader)

    val applied = assertInstanceOf(InstantSwapper.Outcome.Applied::class.java, outcome)
    assertEquals(1, applied.patched)
    assertEquals(0, applied.defined)
    assertTrue(inst.redefined.single().definitionClassFile.contentEquals(v2))
    assertEquals(
        crc(v2),
        crcRegistry[loader to fqcn],
        "a landed patch must advance the registry so the next verify passes",
    )
  }

  @Test
  fun `baseline drift refuses before anything is redefined`() {
    val fqcn = "com.example.Patch"
    val v0 = generateClass("com/example/Patch", 0) // what the JVM actually runs
    val v1 = generateClass("com/example/Patch", 1) // what the CLI thinks it runs
    val v2 = generateClass("com/example/Patch", 2)
    val loader = loaderWith(fqcn, v0)
    crcRegistry[loader to fqcn] = crc(v0)
    val inst = FakeInstrumentation()

    val outcome = swapper(inst).apply(patchRequest(fqcn, crc(v1), v2), loader)

    val refused = assertInstanceOf(InstantSwapper.Outcome.Refused::class.java, outcome)
    assertTrue(refused.reason.contains("baseline drift"), refused.reason)
    assertTrue(inst.redefined.isEmpty(), "a refused request must touch nothing")
  }

  @Test
  fun `a define-time transform admits when the loader's source bytes match the baseline`() {
    // Native Paper loading: Commodore re-encodes every legacy-plugin class between jar and
    // defineClass, so the agent's define record never equals the build CRC. The loader's raw
    // source bytes are the pre-transform truth.
    val fqcn = "com.example.Patch"
    val v1 = generateClass("com/example/Patch", 1) // the baseline build — on disk for the loader
    val transformed = generateClass("com/example/Patch", 99) // what the server actually defined
    val v2 = generateClass("com/example/Patch", 2)
    val loader = loaderWith(fqcn, v1)
    crcRegistry[loader to fqcn] = crc(transformed)
    val inst = FakeInstrumentation()

    val outcome = swapper(inst).apply(patchRequest(fqcn, crc(v1), v2), loader)

    val applied = assertInstanceOf(InstantSwapper.Outcome.Applied::class.java, outcome)
    assertEquals(1, applied.patched)
    assertTrue(inst.redefined.single().definitionClassFile.contentEquals(v2))
  }

  @Test
  fun `a patched class never falls back to the source check`() {
    // Once a patch landed, the loader's jar/dir no longer describes what's running — only the
    // patch record may vouch. A stale CLI baseline that happens to match the on-disk source must
    // still refuse.
    val fqcn = "com.example.Patch"
    val v1 = generateClass("com/example/Patch", 1)
    val patchedLive = generateClass("com/example/Patch", 3)
    val v2 = generateClass("com/example/Patch", 2)
    val loader = loaderWith(fqcn, v1)
    crcRegistry[loader to fqcn] = crc(patchedLive)
    patchedClasses.add(loader to fqcn)
    val inst = FakeInstrumentation()

    val outcome = swapper(inst).apply(patchRequest(fqcn, crc(v1), v2), loader)

    val refused = assertInstanceOf(InstantSwapper.Outcome.Refused::class.java, outcome)
    assertTrue(refused.reason.contains("baseline drift"), refused.reason)
    assertTrue(inst.redefined.isEmpty(), "a refused request must touch nothing")
  }

  @Test
  fun `a loader that serves no source bytes refuses on define mismatch`() {
    val fqcn = "com.example.Hidden"
    val v1 = generateClass("com/example/Hidden", 1)
    val transformed = generateClass("com/example/Hidden", 99)
    val v2 = generateClass("com/example/Hidden", 2)
    // Defines the class but exposes no .class resource — the source check has nothing to verify.
    val loader =
        object : ClassLoader(null) {
          override fun findClass(name: String): Class<*> =
              if (name == fqcn) defineClass(name, v1, 0, v1.size)
              else throw ClassNotFoundException(name)
        }
    crcRegistry[loader to fqcn] = crc(transformed)
    val inst = FakeInstrumentation()

    val outcome = swapper(inst).apply(patchRequest(fqcn, crc(v1), v2), loader)

    val refused = assertInstanceOf(InstantSwapper.Outcome.Refused::class.java, outcome)
    assertTrue(refused.reason.contains("baseline drift"), refused.reason)
  }

  @Test
  fun `already-current classes count as patched without a redefinition`() {
    val fqcn = "com.example.Patch"
    val v2 = generateClass("com/example/Patch", 2)
    val loader = loaderWith(fqcn, v2)
    // The JVM already runs the new bytes (e.g. a directory-backed loader force-loaded them).
    crcRegistry[loader to fqcn] = crc(v2)
    val inst = FakeInstrumentation()

    val outcome = swapper(inst).apply(patchRequest(fqcn, 12345L, v2), loader)

    val applied = assertInstanceOf(InstantSwapper.Outcome.Applied::class.java, outcome)
    assertEquals(1, applied.patched)
    assertTrue(inst.redefined.isEmpty(), "nothing to redefine when bytes are already current")
  }

  @Test
  fun `the JVM veto reports failed with the reason`() {
    val fqcn = "com.example.Patch"
    val v1 = generateClass("com/example/Patch", 1)
    val v2 = generateClass("com/example/Patch", 2)
    val loader = loaderWith(fqcn, v1)
    crcRegistry[loader to fqcn] = crc(v1)
    val inst =
        FakeInstrumentation().apply {
          redefineThrows = UnsupportedOperationException("class redefinition failed")
        }

    val outcome = swapper(inst).apply(patchRequest(fqcn, crc(v1), v2), loader)

    val failed = assertInstanceOf(InstantSwapper.Outcome.Failed::class.java, outcome)
    assertTrue(failed.reason.contains("JVM rejected"), failed.reason)
  }

  @Test
  fun `a loadable class with no load record refuses - unverifiable is unpatchable`() {
    val fqcn = "com.example.Patch"
    val v1 = generateClass("com/example/Patch", 1)
    val v2 = generateClass("com/example/Patch", 2)
    val loader = loaderWith(fqcn, v1)
    // Registry deliberately empty: the agent never saw this class load.

    val outcome = swapper(FakeInstrumentation()).apply(patchRequest(fqcn, crc(v1), v2), loader)

    val refused = assertInstanceOf(InstantSwapper.Outcome.Refused::class.java, outcome)
    assertTrue(refused.reason.contains("no load record"), refused.reason)
  }

  @Test
  fun `a changed class the live loader cannot resolve refuses`() {
    val loader = URLClassLoader(emptyArray(), null)
    val inst = FakeInstrumentation()

    val outcome =
        swapper(inst).apply(patchRequest("com.example.Ghost", 1L, byteArrayOf(1)), loader)

    val refused = assertInstanceOf(InstantSwapper.Outcome.Refused::class.java, outcome)
    assertTrue(refused.reason.contains("not loadable"), refused.reason)
  }

  @Test
  fun `no instrumentation refuses with the agent named`() {
    val outcome =
        swapper(null)
            .apply(patchRequest("com.example.Patch", 1L, byteArrayOf(1)), javaClass.classLoader)

    val refused = assertInstanceOf(InstantSwapper.Outcome.Refused::class.java, outcome)
    assertTrue(refused.reason.contains("instrumentation"), refused.reason)
  }

  @Test
  fun `an undecodable payload fails rather than guessing`() {
    val request =
        HostInstantSwapRequest(
            requestId = "i1",
            pluginName = "Sample",
            classes = listOf(HostInstantClassEntry("com.example.Patch", 1L, "!!!not-base64!!!")),
        )

    val outcome = swapper(FakeInstrumentation()).apply(request, javaClass.classLoader)

    assertInstanceOf(InstantSwapper.Outcome.Failed::class.java, outcome)
  }

  // ── New classes ─────────────────────────────────────────────────────

  @Test
  fun `a new class is spliced into a URLClassLoader and becomes loadable from it`() {
    val fqcn = "com.example.Added"
    val bytes = generateClass("com/example/Added", 7)
    val loader = URLClassLoader(emptyArray(), null)
    val inst = FakeInstrumentation()

    val request =
        HostInstantSwapRequest(
            requestId = "i1",
            pluginName = "Sample",
            newClasses = listOf(HostInstantClassEntry(fqcn, 0L, b64(bytes))),
        )
    val outcome = swapper(inst).apply(request, loader)

    val applied = assertInstanceOf(InstantSwapper.Outcome.Applied::class.java, outcome)
    assertEquals(1, applied.defined)
    val loaded = Class.forName(fqcn, false, loader)
    assertSame(loader, loaded.classLoader, "the new class must load through the plugin loader")
  }

  @Test
  fun `a new class on a loader that cannot receive classes refuses with the reason`() {
    val plainLoader = object : ClassLoader(null) {}
    val request =
        HostInstantSwapRequest(
            requestId = "i1",
            pluginName = "Sample",
            newClasses =
                listOf(
                    HostInstantClassEntry(
                        "com.example.Added",
                        0L,
                        b64(generateClass("com/example/Added", 7)),
                    )
                ),
        )

    val outcome = swapper(FakeInstrumentation()).apply(request, plainLoader)

    val refused = assertInstanceOf(InstantSwapper.Outcome.Refused::class.java, outcome)
    assertTrue(refused.reason.contains("cannot receive new classes"), refused.reason)
  }
}
