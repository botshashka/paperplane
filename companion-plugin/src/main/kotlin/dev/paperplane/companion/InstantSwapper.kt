package dev.paperplane.companion

import java.lang.instrument.ClassDefinition
import java.lang.instrument.Instrumentation
import java.lang.instrument.UnmodifiableClassException
import java.net.JarURLConnection
import java.net.URL
import java.net.URLClassLoader
import java.util.Base64
import java.util.logging.Logger
import java.util.zip.CRC32

/**
 * Applies an instant patch: verifies, then redefines the request's classes in the live server.
 *
 * The safety split with the CLI: the CLI's classifier decides *whether* a change-set is safe to
 * patch; this class verifies the CLI's premise — that the server is actually running the bytes the
 * CLI diffed against. The verification source is the agent's load-hook CRC registry
 * ([AgentAccess.loadedCrc]): the filesystem is already overwritten by verify time, and
 * retransform-capture is byte-unfaithful (HotSpot reconstitutes class files for retransformers), so
 * the bytes recorded at definition time are the only truthful record. Natively loaded plugins never
 * match literally — Paper's compatibility pass (Commodore) re-encodes every legacy-plugin class
 * between jar and defineClass — so a define-record mismatch falls back to comparing the defining
 * loader's raw source bytes against the baseline; patched classes must match their patch record
 * exactly. Any mismatch refuses the whole request. The JVM's own
 * [UnmodifiableClassException]/[UnsupportedOperationException] veto stays as the final backstop,
 * and `redefineClasses` is all-or-nothing, so a failure never leaves half a patch applied.
 */
class InstantSwapper(
    private val logger: Logger,
    private val instrumentationProvider: () -> Instrumentation? = { AgentAccess.instrumentation() },
    private val loadedCrcProvider: (ClassLoader, String) -> Long = AgentAccess::loadedCrc,
    private val wasPatchedProvider: (ClassLoader, String) -> Boolean = AgentAccess::wasPatched,
    private val crcUpdater: (ClassLoader, String, Long) -> Unit = AgentAccess::updateCrc,
) {
  sealed class Outcome {
    /**
     * Everything applied. [patched] counts redefined + already-current classes; [appliedClasses]
     * names every class now verifiably running the requested bytes, which is what the CLI advances
     * its baseline against.
     */
    data class Applied(
        val patched: Int,
        val appliedClasses: List<String>,
    ) : Outcome()

    /** A precondition or verification check said no; nothing was touched. */
    data class Refused(val reason: String) : Outcome()

    /** The redefine attempt itself errored (JVM veto included); nothing was applied. */
    data class Failed(val reason: String) : Outcome()
  }

  fun apply(request: HostInstantSwapRequest, pluginClassLoader: ClassLoader): Outcome {
    val inst =
        instrumentationProvider() ?: return Outcome.Refused("instrumentation agent not loaded")
    if (!inst.isRedefineClassesSupported) {
      return Outcome.Refused("JVM does not support class redefinition")
    }

    val patches = decode(request.classes) ?: return Outcome.Failed("undecodable class payload")

    // Refused promises "nothing was touched", and that promise expires the moment a force-load
    // defines a class that wasn't loaded before. Verification is necessarily interleaved with that
    // side effect — the CRC registry holds nothing for a class until it loads, which is exactly
    // why the force-load exists — so once one has landed, a later no is reported as Failed. Both
    // send the CLI down the full swap path; only the promise differs.
    var landed = false
    fun stop(reason: String): Outcome =
        if (landed) Outcome.Failed(reason) else Outcome.Refused(reason)

    val definitions = mutableListOf<ClassDefinition>()
    val applied = mutableListOf<String>()
    var alreadyCurrent = 0
    for ((entry, newBytes) in patches) {
      // Force-load a changed-but-unloaded class (no initialization) so a jar-backed loader's
      // stale bytes are in the redefinition batch too — otherwise a later lazy load would
      // resurrect the old bytes from the jar. The load also populates the agent's CRC registry.
      val wasRecorded = loadedCrcProvider(pluginClassLoader, entry.fqcn) != AgentAccess.UNKNOWN_CRC
      val cls =
          try {
            Class.forName(entry.fqcn, false, pluginClassLoader)
          } catch (e: ClassNotFoundException) {
            return stop("class ${entry.fqcn} is not loadable in the live server")
          } catch (e: LinkageError) {
            return stop("class ${entry.fqcn} failed to link: ${e.message}")
          }
      if (!wasRecorded) landed = true
      // Verify against the loader that actually DEFINED the class (delegation may have resolved
      // it elsewhere — patching a parent-owned class is never what the CLI vouched for).
      val definingLoader =
          cls.classLoader ?: return stop("class ${entry.fqcn} resolved from the bootstrap loader")

      when (val loadedCrc = loadedCrcProvider(definingLoader, entry.fqcn)) {
        AgentAccess.UNKNOWN_CRC -> return stop("no load record for ${entry.fqcn} — cannot verify")
        // A freshly force-loaded class on a directory-backed loader already read the new bytes.
        crc32(newBytes) -> {
          alreadyCurrent++
          applied += entry.fqcn
        }
        entry.expectedCrc32 -> {
          definitions += ClassDefinition(cls, newBytes)
          applied += entry.fqcn
        }
        else -> {
          // The defined bytes don't literally match the CLI's baseline. That is expected for
          // natively loaded plugins: Paper's compatibility pass (Commodore) re-encodes every
          // legacy-plugin class at define time, so the define CRC never equals the build CRC.
          // The loader's own resource bytes are pre-transform — if THEY match the baseline, the
          // running class is verifiably derived from it. Never applies to a patched class: its
          // source no longer describes what's running, so only the patch record may vouch.
          val sourceCrc = sourceCrc(definingLoader, entry.fqcn)
          if (wasPatchedProvider(definingLoader, entry.fqcn) || sourceCrc != entry.expectedCrc32) {
            logger.fine(
                "Instant verify mismatch on ${entry.fqcn}: loaded=$loadedCrc " +
                    "expected=${entry.expectedCrc32} source=$sourceCrc new=${crc32(newBytes)}"
            )
            return stop(
                "baseline drift on ${entry.fqcn} — the server is not running the bytes " +
                    "the CLI diffed against"
            )
          }
          definitions += ClassDefinition(cls, newBytes)
          applied += entry.fqcn
        }
      }
    }

    if (definitions.isNotEmpty()) {
      try {
        inst.redefineClasses(*definitions.toTypedArray())
      } catch (e: UnsupportedOperationException) {
        return Outcome.Failed("JVM rejected redefinition: ${e.message}")
      } catch (e: UnmodifiableClassException) {
        return Outcome.Failed("JVM rejected redefinition: ${e.message}")
      } catch (e: LinkageError) {
        // redefineClasses documents ClassFormatError, NoClassDefFoundError,
        // UnsupportedClassVersionError and ClassCircularityError, and throws VerifyError in
        // practice — every one an Error, so the Exception catch below never sees them. A verifier
        // rejection is the single most likely redefine failure; without this it escapes the whole
        // handler and the CLI waits out its timeout with nothing to report.
        return Outcome.Failed("JVM rejected redefinition: ${e.javaClass.simpleName}: ${e.message}")
      } catch (
          @Suppress("TooGenericExceptionCaught") // Any escape here would leave the CLI's await
          // hanging until timeout with no reason; report it as the failed outcome instead.
          e: Exception) {
        return Outcome.Failed("redefinition failed: ${e.javaClass.simpleName}: ${e.message}")
      }
      // The redefinition landed — the registry must now say the new bytes are what's running.
      // (The load hook records only initial definitions, so this is the one write path for
      // patches.)
      for (definition in definitions) {
        crcUpdater(
            definition.definitionClass.classLoader,
            definition.definitionClass.name,
            crc32(definition.definitionClassFile),
        )
      }
    }
    return Outcome.Applied(
        patched = definitions.size + alreadyCurrent,
        appliedClasses = applied,
    )
  }

  /**
   * CRC32 of the class-file bytes [loader]'s own source (jar or directory) serves for [fqcn], or
   * [AgentAccess.UNKNOWN_CRC] when it has none. Resource reads bypass define-time transforms, and a
   * `URLClassLoader`'s open jar handle keeps serving the entries the loader actually defines from
   * even if the file on disk was since replaced. `findResource` over `getResource` so parent
   * delegation can't answer for a class this loader owns.
   */
  private fun sourceCrc(loader: ClassLoader, fqcn: String): Long {
    val path = classPath(fqcn)
    val url =
        (if (loader is URLClassLoader) loader.findResource(path) else loader.getResource(path))
            ?: return AgentAccess.UNKNOWN_CRC
    return try {
      jarEntryCrc(url) ?: url.openStream().use { crc32(it.readBytes()) }
    } catch (e: java.io.IOException) {
      logger.fine("Instant verify: cannot read source bytes for $fqcn: ${e.message}")
      AgentAccess.UNKNOWN_CRC
    }
  }

  /**
   * The CRC32 a jar's central directory already records for [url]'s entry, or null when [url] isn't
   * a jar entry (a directory-backed loader) or the jar omits it.
   *
   * ZIP stores the CRC32 of the *uncompressed* entry, which is exactly the value the stream read
   * would compute — so on the branch that matters this skips inflating every patched class. That
   * branch is not the exception: in native modes Commodore re-encodes at define time, so the define
   * CRC never matches and every class in the request takes it, on the main thread inside the tick
   * budget. The JDK caches the open `JarFile` behind `jar:` URLs, so the whole request reads one
   * already-parsed central directory.
   */
  private fun jarEntryCrc(url: URL): Long? {
    if (!url.protocol.equals("jar", ignoreCase = true)) return null
    val connection = url.openConnection() as? JarURLConnection ?: return null
    connection.useCaches = true
    return connection.jarEntry?.crc?.takeIf { it >= 0 }
  }

  /** Decodes base64 payloads; null on any malformed entry. */
  private fun decode(
      entries: List<HostInstantClassEntry>
  ): List<Pair<HostInstantClassEntry, ByteArray>>? =
      try {
        entries.map { it to Base64.getDecoder().decode(it.data) }
      } catch (e: IllegalArgumentException) {
        logger.warning("Instant swap payload not decodable: ${e.message}")
        null
      }

  private fun classPath(fqcn: String): String = fqcn.replace('.', '/') + ".class"

  private fun crc32(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value
}
