package dev.paperplane.companion

import java.io.File
import java.lang.instrument.ClassDefinition
import java.lang.instrument.Instrumentation
import java.util.logging.Logger

/** Result of a hot-swap attempt. */
enum class HotSwapResult {
  SUCCESS, // All classes redefined in-place
  UNAVAILABLE, // Agent not loaded, no Instrumentation
  NEW_CLASS, // A changed class wasn't loaded yet — can't redefine
  STRUCTURAL_CHANGE, // JVM rejected: new method/field/etc.
  READ_FAILED, // Couldn't read new .class bytes
  FAILED, // Other error
}

/**
 * Performs in-place class redefinition via the Java Instrumentation API.
 *
 * Design principle: trust the JVM, not our own bytecode analysis. We attempt redefineClasses() and
 * catch UnsupportedOperationException for structural changes. The optional ClassChangeDetector
 * pre-filter avoids the exception cost but is not required for correctness.
 */
class HotSwapper(private val logger: Logger) {
  private var instrumentation: Instrumentation? = null
  private val detector = ClassChangeDetector()

  /** Whether instrumentation is available (agent was loaded). */
  fun isAvailable(): Boolean {
    if (instrumentation != null) return true
    instrumentation = resolveInstrumentation()
    return instrumentation != null
  }

  /**
   * Attempts to redefine the given classes in-place.
   *
   * @param changedClassNames FQCNs of classes that were modified
   * @param pluginClassLoader The running plugin's classloader (to find loaded classes)
   * @param buildOutputDirs Directories/JARs containing the new .class files
   */
  fun redefine(
      changedClassNames: List<String>,
      pluginClassLoader: ClassLoader,
      buildOutputDirs: List<String>,
  ): HotSwapResult {
    val inst = instrumentation ?: return HotSwapResult.UNAVAILABLE
    if (changedClassNames.isEmpty()) return HotSwapResult.SUCCESS

    val definitions = mutableListOf<ClassDefinition>()

    for (fqcn in changedClassNames) {
      // Find the loaded class
      val loadedClass =
          try {
            pluginClassLoader.loadClass(fqcn)
          } catch (_: ClassNotFoundException) {
            return HotSwapResult.NEW_CLASS
          }

      // Read new bytecode from build output
      val newBytes = readClassBytes(fqcn, buildOutputDirs)
      if (newBytes == null) {
        logger.warning("Could not read new .class bytes for $fqcn")
        return HotSwapResult.READ_FAILED
      }

      // Pre-check: detect structural changes before calling JVM
      // Skip this check on JBR/DCEVM — it handles structural changes natively
      if (!isEnhancedRedefinitionAvailable()) {
        val oldBytes = readOldClassBytes(fqcn, pluginClassLoader)
        if (oldBytes != null && !detector.isMethodBodyOnly(oldBytes, newBytes)) {
          return HotSwapResult.STRUCTURAL_CHANGE
        }
      }

      definitions.add(ClassDefinition(loadedClass, newBytes))
    }

    return try {
      inst.redefineClasses(*definitions.toTypedArray())
      HotSwapResult.SUCCESS
    } catch (_: UnsupportedOperationException) {
      HotSwapResult.STRUCTURAL_CHANGE
    } catch (e: Exception) {
      logger.warning("Hot-swap failed: ${e.message}")
      HotSwapResult.FAILED
    }
  }

  /**
   * Whether JetBrains Runtime enhanced redefinition is available. When true, structural changes
   * (new methods/fields) can be hot-swapped.
   */
  fun isEnhancedRedefinitionAvailable(): Boolean {
    val vendor = System.getProperty("java.vendor", "")
    val vmName = System.getProperty("java.vm.name", "")
    return vendor.contains("JetBrains", ignoreCase = true) ||
        vmName.contains("JBR", ignoreCase = true)
  }

  private fun resolveInstrumentation(): Instrumentation? {
    return try {
      val agentClass =
          ClassLoader.getSystemClassLoader().loadClass("dev.paperplane.agent.PaperPlaneAgent")
      agentClass.getMethod("getInstrumentation").invoke(null) as? Instrumentation
    } catch (e: Exception) {
      logger.warning("Could not resolve Instrumentation: ${e.javaClass.simpleName}: ${e.message}")
      null
    }
  }

  private fun readClassBytes(fqcn: String, buildOutputDirs: List<String>): ByteArray? {
    val classPath = fqcn.replace('.', '/') + ".class"
    for (dir in buildOutputDirs) {
      val file = File(dir, classPath)
      if (file.exists()) return file.readBytes()
    }
    return null
  }

  private fun readOldClassBytes(fqcn: String, classLoader: ClassLoader): ByteArray? {
    val path = fqcn.replace('.', '/') + ".class"
    return try {
      classLoader.getResourceAsStream(path)?.readAllBytes()
    } catch (_: Exception) {
      null // Old bytes unavailable (e.g., directory-based load where files were overwritten)
    }
  }
}
