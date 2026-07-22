package dev.paperplane.companion

import java.io.File
import java.lang.instrument.ClassDefinition
import java.lang.instrument.Instrumentation
import java.lang.instrument.UnmodifiableClassException
import java.lang.reflect.InaccessibleObjectException
import java.net.URL
import java.net.URLClassLoader
import java.util.Base64
import java.util.Collections
import java.util.WeakHashMap
import java.util.logging.Logger
import java.util.zip.CRC32

/**
 * Applies an instant patch: verifies, then redefines the request's classes in the live server and
 * makes its new classes loadable. Replaces the old `HotSwapper`.
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
 * and `redefineClasses` is all-or-nothing, so a failure never leaves half a patch applied. (New
 * classes made loadable before a vetoed redefine remain defined but unreferenced — old code never
 * names them — so that residue is inert.)
 */
class InstantSwapper(
    private val logger: Logger,
    private val overlayDir: File,
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
        val defined: Int,
        val appliedClasses: List<String>,
    ) : Outcome()

    /** A precondition or verification check said no; nothing was touched. */
    data class Refused(val reason: String) : Outcome()

    /** The redefine attempt itself errored (JVM veto included); nothing was applied. */
    data class Failed(val reason: String) : Outcome()
  }

  // Loaders whose URL list already contains the overlay dir. Weak so a torn-down plugin
  // incarnation's loader doesn't pin.
  private val splicedLoaders: MutableSet<ClassLoader> = Collections.newSetFromMap(WeakHashMap())

  fun apply(request: HostInstantSwapRequest, pluginClassLoader: ClassLoader): Outcome {
    val inst =
        instrumentationProvider() ?: return Outcome.Refused("instrumentation agent not loaded")
    if (!inst.isRedefineClassesSupported) {
      return Outcome.Refused("JVM does not support class redefinition")
    }

    val patches = decode(request.classes) ?: return Outcome.Failed("undecodable class payload")
    val additions = decode(request.newClasses) ?: return Outcome.Failed("undecodable class payload")

    val definitions = mutableListOf<ClassDefinition>()
    val applied = mutableListOf<String>()
    var alreadyCurrent = 0
    for ((entry, newBytes) in patches) {
      // Force-load a changed-but-unloaded class (no initialization) so a jar-backed loader's
      // stale bytes are in the redefinition batch too — otherwise a later lazy load would
      // resurrect the old bytes from the jar. The load also populates the agent's CRC registry.
      val cls =
          try {
            Class.forName(entry.fqcn, false, pluginClassLoader)
          } catch (e: ClassNotFoundException) {
            return Outcome.Refused("class ${entry.fqcn} is not loadable in the live server")
          } catch (e: LinkageError) {
            return Outcome.Refused("class ${entry.fqcn} failed to link: ${e.message}")
          }
      // Verify against the loader that actually DEFINED the class (delegation may have resolved
      // it elsewhere — patching a parent-owned class is never what the CLI vouched for).
      val definingLoader =
          cls.classLoader
              ?: return Outcome.Refused("class ${entry.fqcn} resolved from the bootstrap loader")

      when (val loadedCrc = loadedCrcProvider(definingLoader, entry.fqcn)) {
        AgentAccess.UNKNOWN_CRC ->
            return Outcome.Refused("no load record for ${entry.fqcn} — cannot verify")
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
            return Outcome.Refused(
                "baseline drift on ${entry.fqcn} — the server is not running the bytes " +
                    "the CLI diffed against"
            )
          }
          definitions += ClassDefinition(cls, newBytes)
          applied += entry.fqcn
        }
      }
    }

    var defined = 0
    for ((entry, bytes) in additions) {
      makeLoadable(pluginClassLoader, entry.fqcn, bytes)?.let {
        return Outcome.Refused(it)
      }
      defined++
      applied += entry.fqcn
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
        defined = defined,
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
    val url = if (loader is URLClassLoader) loader.findResource(path) else loader.getResource(path)
    return try {
      url?.openStream()?.use { crc32(it.readBytes()) } ?: AgentAccess.UNKNOWN_CRC
    } catch (e: java.io.IOException) {
      logger.fine("Instant verify: cannot read source bytes for $fqcn: ${e.message}")
      AgentAccess.UNKNOWN_CRC
    }
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

  /**
   * Makes a new class loadable through [loader]. Returns null on success, or a user-facing refusal
   * reason. Prefers doing nothing (the loader already sees the class — directory-backed loaders
   * read the CLI's build output), then [DevPluginClassLoader.defineNew] (works with no shared
   * filesystem), then the overlay-dir `addURL` splice for Paper's jar-backed `PluginClassLoader`.
   */
  private fun makeLoadable(loader: ClassLoader, fqcn: String, bytes: ByteArray): String? {
    try {
      // Resolving the name is not enough — it must resolve to a class THIS loader defined.
      // Paper's PluginClassLoader searches the whole plugin-classloader group and
      // DevPluginClassLoader falls back to other plugins' loaders, so a name another plugin
      // exposes (an unrelocated shaded package is the realistic case) would otherwise be read as
      // "already loadable". The plugin's own class would never be defined and its code would bind
      // to the foreign type. Falling through wins either way: defineNew is child-first, and the
      // overlay URL is only consulted for names the jar doesn't carry.
      val existing = Class.forName(fqcn, false, loader)
      if (existing.classLoader === loader) {
        // Already defined here. Only "nothing to do" if it is already running these exact bytes —
        // a directory-backed loader that read the CLI's build output. Otherwise the CLI's premise
        // (that this class is new) is wrong and it is running a generation we can't redefine into
        // place, so refuse rather than report it applied.
        val loadedCrc = loadedCrcProvider(loader, fqcn)
        if (loadedCrc == crc32(bytes)) return null
        return "new class $fqcn is already loaded with different bytes — full swap required"
      }
    } catch (_: ClassNotFoundException) {} catch (e: LinkageError) {
      return "new class $fqcn failed to link: ${e.message}"
    }
    return when (loader) {
      is DevPluginClassLoader ->
          try {
            loader.defineNew(fqcn, bytes)
            null
          } catch (e: LinkageError) {
            "new class $fqcn could not be defined: ${e.message}"
          }
      is URLClassLoader -> spliceOverlay(loader, fqcn, bytes)
      else ->
          "plugin classloader ${loader.javaClass.name} cannot receive new classes — " +
              "full swap required"
    }
  }

  /**
   * Writes [bytes] under [overlayDir] and reflectively `addURL`s the overlay onto [loader] (once
   * per loader). Requires the server JVM to be launched with `--add-opens
   * java.base/java.net=ALL-UNNAMED` — which the CLI's LaunchSpec always adds.
   */
  private fun spliceOverlay(loader: URLClassLoader, fqcn: String, bytes: ByteArray): String? {
    val target = File(overlayDir, classPath(fqcn))
    try {
      target.parentFile.mkdirs()
      target.writeBytes(bytes)
    } catch (e: java.io.IOException) {
      // Unwritable overlay dir, full disk, or a package path past Windows MAX_PATH. Refusing sends
      // the CLI down the full swap path; letting it escape would strand the CLI's await instead.
      return "cannot stage new class $fqcn: ${e.message} — full swap required"
    }
    if (loader in splicedLoaders) return null
    return try {
      val addUrl = URLClassLoader::class.java.getDeclaredMethod("addURL", URL::class.java)
      addUrl.isAccessible = true
      addUrl.invoke(loader, overlayDir.toURI().toURL())
      splicedLoaders.add(loader)
      null
    } catch (e: InaccessibleObjectException) {
      "cannot splice new classes into ${loader.javaClass.simpleName} " +
          "(server JVM launched without --add-opens java.base/java.net) — full swap required"
    } catch (e: ReflectiveOperationException) {
      "cannot splice new classes: ${e.javaClass.simpleName}: ${e.message} — full swap required"
    }
  }

  private fun classPath(fqcn: String): String = fqcn.replace('.', '/') + ".class"

  private fun crc32(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value
}
