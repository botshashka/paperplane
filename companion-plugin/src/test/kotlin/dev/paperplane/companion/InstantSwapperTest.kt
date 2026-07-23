package dev.paperplane.companion

import java.io.File
import java.net.URLClassLoader
import java.util.Base64
import java.util.logging.Logger
import java.util.zip.CRC32
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Drives [InstantSwapper.apply] end to end with a [FakeInstrumentation], a scripted CRC registry
 * (standing in for the agent's load-hook registry), and real classloaders over ASM-generated
 * classes: the verification ladder (loaded-CRC vs expected), the already-current skip, and the JVM
 * veto.
 */
class InstantSwapperTest {

  @TempDir lateinit var tempDir: File

  private val crcRegistry = mutableMapOf<Pair<ClassLoader, String>, Long>()
  private val patchedClasses = mutableSetOf<Pair<ClassLoader, String>>()

  private fun crc(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

  private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

  /**
   * Writes [bytes] as `com/example/<Simple>.class` under a fresh dir and returns a loader on it.
   */
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
    val v1 = BytecodeFixtures.classWithMarker("com/example/Patch", 1)
    val v2 = BytecodeFixtures.classWithMarker("com/example/Patch", 2)
    val loader = loaderWith(fqcn, v1)
    crcRegistry[loader to fqcn] = crc(v1)
    val inst = FakeInstrumentation()

    val outcome = swapper(inst).apply(patchRequest(fqcn, crc(v1), v2), loader)

    val applied = assertInstanceOf(InstantSwapper.Outcome.Applied::class.java, outcome)
    assertEquals(1, applied.patched)
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
    val v0 = BytecodeFixtures.classWithMarker("com/example/Patch", 0) // what the JVM actually runs
    val v1 = BytecodeFixtures.classWithMarker("com/example/Patch", 1) // what the CLI thinks it runs
    val v2 = BytecodeFixtures.classWithMarker("com/example/Patch", 2)
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
    val v1 =
        BytecodeFixtures.classWithMarker(
            "com/example/Patch",
            1,
        ) // the baseline build — on disk for the loader
    val transformed =
        BytecodeFixtures.classWithMarker(
            "com/example/Patch",
            99,
        ) // what the server actually defined
    val v2 = BytecodeFixtures.classWithMarker("com/example/Patch", 2)
    val loader = loaderWith(fqcn, v1)
    crcRegistry[loader to fqcn] = crc(transformed)
    val inst = FakeInstrumentation()

    val outcome = swapper(inst).apply(patchRequest(fqcn, crc(v1), v2), loader)

    val applied = assertInstanceOf(InstantSwapper.Outcome.Applied::class.java, outcome)
    assertEquals(1, applied.patched)
    assertTrue(inst.redefined.single().definitionClassFile.contentEquals(v2))
  }

  @Test
  fun `a jar-backed loader admits via the jar's central-directory CRC`() {
    // The native-mode shape end to end: the loader serves the class from a jar (so sourceCrc's
    // jar: URL branch runs and reads the central directory instead of inflating the entry), and
    // the define record disagrees with the jar bytes (Commodore re-encoded at define time).
    val fqcn = "com.example.Patch"
    val v1 = BytecodeFixtures.classWithMarker("com/example/Patch", 1)
    val transformed = BytecodeFixtures.classWithMarker("com/example/Patch", 99)
    val v2 = BytecodeFixtures.classWithMarker("com/example/Patch", 2)
    val jar = File(tempDir, "plugin.jar")
    java.util.jar.JarOutputStream(jar.outputStream()).use { out ->
      out.putNextEntry(java.util.zip.ZipEntry("com/example/Patch.class"))
      out.write(v1)
      out.closeEntry()
    }
    val loader = URLClassLoader(arrayOf(jar.toURI().toURL()), null)
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
    val v1 = BytecodeFixtures.classWithMarker("com/example/Patch", 1)
    val patchedLive = BytecodeFixtures.classWithMarker("com/example/Patch", 3)
    val v2 = BytecodeFixtures.classWithMarker("com/example/Patch", 2)
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
    val v1 = BytecodeFixtures.classWithMarker("com/example/Hidden", 1)
    val transformed = BytecodeFixtures.classWithMarker("com/example/Hidden", 99)
    val v2 = BytecodeFixtures.classWithMarker("com/example/Hidden", 2)
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
    val v2 = BytecodeFixtures.classWithMarker("com/example/Patch", 2)
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
    val v1 = BytecodeFixtures.classWithMarker("com/example/Patch", 1)
    val v2 = BytecodeFixtures.classWithMarker("com/example/Patch", 2)
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
  fun `a loadable class with no load record fails - the force-load already landed`() {
    val fqcn = "com.example.Patch"
    val v1 = BytecodeFixtures.classWithMarker("com/example/Patch", 1)
    val v2 = BytecodeFixtures.classWithMarker("com/example/Patch", 2)
    val loader = loaderWith(fqcn, v1)
    // Registry deliberately empty: the agent never saw this class load. The force-load then
    // defines it — a side effect that has landed — so the honest answer is Failed, not a
    // Refused claiming nothing was touched.

    val outcome = swapper(FakeInstrumentation()).apply(patchRequest(fqcn, crc(v1), v2), loader)

    val failed = assertInstanceOf(InstantSwapper.Outcome.Failed::class.java, outcome)
    assertTrue(failed.reason.contains("no load record"), failed.reason)
  }

  @Test
  fun `a changed class the live loader cannot resolve refuses`() {
    val loader = URLClassLoader(emptyArray(), null)
    val inst = FakeInstrumentation()

    val outcome = swapper(inst).apply(patchRequest("com.example.Ghost", 1L, byteArrayOf(1)), loader)

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

  @Test
  fun `a verifier rejection reports failed rather than escaping`() {
    // VerifyError and friends are Errors, so a catch on Exception never sees them. Letting one
    // escape strands the CLI's await until timeout and reports "no patch answer from the
    // companion" — the failure with the most useful message becomes the one with none.
    val fqcn = "com.example.Patch"
    val v1 = BytecodeFixtures.classWithMarker("com/example/Patch", 1)
    val v2 = BytecodeFixtures.classWithMarker("com/example/Patch", 2)
    val loader = loaderWith(fqcn, v1)
    crcRegistry[loader to fqcn] = crc(v1)
    val inst = FakeInstrumentation().apply { redefineThrows = VerifyError("bad type on operand") }

    val outcome = swapper(inst).apply(patchRequest(fqcn, crc(v1), v2), loader)

    val failed = assertInstanceOf(InstantSwapper.Outcome.Failed::class.java, outcome)
    assertTrue(failed.reason.contains("VerifyError"), failed.reason)
    assertTrue(failed.reason.contains("bad type on operand"), failed.reason)
  }

  @Test
  fun `a multi-class request reports every applied fqcn - redefined and already-current alike`() {
    // appliedClasses is what the CLI advances its baseline against, so both shapes of "verifiably
    // running the requested bytes" must be named: the redefined class AND the already-current one
    // (which never enters the redefinition batch).
    val redefined = "com.example.PatchA"
    val current = "com.example.PatchB"
    val aV1 = BytecodeFixtures.classWithMarker("com/example/PatchA", 1)
    val aV2 = BytecodeFixtures.classWithMarker("com/example/PatchA", 2)
    val bV2 = BytecodeFixtures.classWithMarker("com/example/PatchB", 2)
    val dir = File(tempDir, "multi").apply { mkdirs() }
    for ((fqcn, bytes) in mapOf(redefined to aV1, current to bV2)) {
      val target = File(dir, fqcn.replace('.', '/') + ".class")
      target.parentFile.mkdirs()
      target.writeBytes(bytes)
    }
    val loader = URLClassLoader(arrayOf(dir.toURI().toURL()), null)
    crcRegistry[loader to redefined] = crc(aV1)
    crcRegistry[loader to current] = crc(bV2)
    val request =
        HostInstantSwapRequest(
            requestId = "i1",
            pluginName = "Sample",
            classes =
                listOf(
                    HostInstantClassEntry(redefined, crc(aV1), b64(aV2)),
                    HostInstantClassEntry(current, 12345L, b64(bV2)),
                ),
        )
    val inst = FakeInstrumentation()

    val outcome = swapper(inst).apply(request, loader)

    val applied = assertInstanceOf(InstantSwapper.Outcome.Applied::class.java, outcome)
    assertEquals(2, applied.patched)
    assertEquals(listOf(redefined, current), applied.appliedClasses)
    assertEquals(1, inst.redefined.size, "the already-current class must not be redefined")
    assertEquals(redefined, inst.redefined.single().definitionClass.name)
  }

  @Test
  fun `a class resolved through a parent loader refuses instead of claiming a side effect`() {
    // The registry is keyed by defining loader, so a class the parent loaded long ago has no
    // record under the requested child. Reading "unrecorded" as "the force-load defined it"
    // downgrades an honest Refused to Failed and tells the CLI something was touched when the
    // class had been resident all along.
    val fqcn = "com.example.Patch"
    val v0 = BytecodeFixtures.classWithMarker("com/example/Patch", 0)
    val v1 = BytecodeFixtures.classWithMarker("com/example/Patch", 1)
    val v2 = BytecodeFixtures.classWithMarker("com/example/Patch", 2)
    val parent = loaderWith(fqcn, v0)
    val child = URLClassLoader(emptyArray(), parent)
    crcRegistry[parent to fqcn] = crc(v0)
    val inst = FakeInstrumentation()

    val outcome = swapper(inst).apply(patchRequest(fqcn, crc(v1), v2), child)

    val refused = assertInstanceOf(InstantSwapper.Outcome.Refused::class.java, outcome)
    assertTrue(refused.reason.contains("baseline drift"), refused.reason)
    assertTrue(inst.redefined.isEmpty(), "a refused request must touch nothing")
  }

  @Test
  fun `a class that fails to link refuses with the linkage error named`() {
    // A plugin class referencing a since-removed dependency throws NoClassDefFoundError on the
    // force-load — a LinkageError, which the ClassNotFoundException catch never sees.
    val loader =
        object : ClassLoader(null) {
          override fun findClass(name: String): Class<*> =
              throw NoClassDefFoundError("com/missing/Dep")
        }

    val outcome =
        swapper(FakeInstrumentation())
            .apply(patchRequest("com.example.Broken", 1L, byteArrayOf(1)), loader)

    val refused = assertInstanceOf(InstantSwapper.Outcome.Refused::class.java, outcome)
    assertTrue(refused.reason.contains("failed to link"), refused.reason)
  }

  @Test
  fun `a class resolved from the bootstrap loader is never patched`() {
    // Delegation can resolve a requested name to a JDK class whose loader is null. Reported as
    // Failed, not Refused: the class had no load record, so the force-load conservatism already
    // gave up the "nothing was touched" promise before the loader check ran.
    val outcome =
        swapper(FakeInstrumentation())
            .apply(patchRequest("java.lang.String", 1L, byteArrayOf(1)), javaClass.classLoader)

    val failed = assertInstanceOf(InstantSwapper.Outcome.Failed::class.java, outcome)
    assertTrue(failed.reason.contains("bootstrap loader"), failed.reason)
  }

  @Test
  fun `an unmodifiable-class veto reports failed with the reason`() {
    val fqcn = "com.example.Patch"
    val v1 = BytecodeFixtures.classWithMarker("com/example/Patch", 1)
    val v2 = BytecodeFixtures.classWithMarker("com/example/Patch", 2)
    val loader = loaderWith(fqcn, v1)
    crcRegistry[loader to fqcn] = crc(v1)
    val inst =
        FakeInstrumentation().apply {
          redefineThrows = java.lang.instrument.UnmodifiableClassException("cannot modify")
        }

    val outcome = swapper(inst).apply(patchRequest(fqcn, crc(v1), v2), loader)

    val failed = assertInstanceOf(InstantSwapper.Outcome.Failed::class.java, outcome)
    assertTrue(failed.reason.contains("JVM rejected"), failed.reason)
  }

  @Test
  fun `an unexpected redefine exception reports failed rather than escaping`() {
    val fqcn = "com.example.Patch"
    val v1 = BytecodeFixtures.classWithMarker("com/example/Patch", 1)
    val v2 = BytecodeFixtures.classWithMarker("com/example/Patch", 2)
    val loader = loaderWith(fqcn, v1)
    crcRegistry[loader to fqcn] = crc(v1)
    val inst = FakeInstrumentation().apply { redefineThrows = IllegalStateException("boom") }

    val outcome = swapper(inst).apply(patchRequest(fqcn, crc(v1), v2), loader)

    val failed = assertInstanceOf(InstantSwapper.Outcome.Failed::class.java, outcome)
    assertTrue(failed.reason.contains("IllegalStateException"), failed.reason)
    assertTrue(failed.reason.contains("boom"), failed.reason)
  }

  @Test
  fun `a JVM without redefinition support refuses`() {
    val fqcn = "com.example.Patch"
    val v1 = BytecodeFixtures.classWithMarker("com/example/Patch", 1)
    val loader = loaderWith(fqcn, v1)
    val inst = FakeInstrumentation().apply { redefineSupported = false }

    val outcome = swapper(inst).apply(patchRequest(fqcn, crc(v1), v1), loader)

    val refused = assertInstanceOf(InstantSwapper.Outcome.Refused::class.java, outcome)
    assertTrue(refused.reason.contains("does not support class redefinition"), refused.reason)
  }
}
