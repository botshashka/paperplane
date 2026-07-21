package dev.paperplane.companion

import java.lang.instrument.ClassDefinition
import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.Instrumentation
import java.util.jar.JarFile

/**
 * Scriptable [Instrumentation] for [InstantSwapper] tests. [retransformClasses] feeds every
 * registered transformer the scripted [loadedBytesByClass] — simulating what a real JVM hands the
 * capture transformer as the class's current definition — and [redefineClasses] records (or
 * throws, when [redefineThrows] is set, simulating the JVM veto).
 */
class FakeInstrumentation(
    val loadedBytesByClass: MutableMap<String, ByteArray> = mutableMapOf(),
    var redefineThrows: Throwable? = null,
    var retransformSupported: Boolean = true,
) : Instrumentation {
  val transformers = mutableListOf<ClassFileTransformer>()
  val redefined = mutableListOf<ClassDefinition>()

  override fun addTransformer(transformer: ClassFileTransformer, canRetransform: Boolean) {
    transformers += transformer
  }

  override fun addTransformer(transformer: ClassFileTransformer) {
    transformers += transformer
  }

  override fun removeTransformer(transformer: ClassFileTransformer): Boolean =
      transformers.remove(transformer)

  override fun isRetransformClassesSupported(): Boolean = retransformSupported

  override fun retransformClasses(vararg classes: Class<*>) {
    for (cls in classes) {
      val bytes = loadedBytesByClass[cls.name] ?: ByteArray(0)
      for (transformer in transformers.toList()) {
        transformer.transform(
            cls.module,
            cls.classLoader,
            cls.name.replace('.', '/'),
            cls,
            null,
            bytes,
        )
      }
    }
  }

  override fun isRedefineClassesSupported(): Boolean = true

  override fun redefineClasses(vararg definitions: ClassDefinition) {
    redefineThrows?.let { throw it }
    redefined += definitions
  }

  override fun isModifiableClass(theClass: Class<*>): Boolean = true

  override fun getAllLoadedClasses(): Array<Class<*>> = emptyArray()

  override fun getInitiatedClasses(loader: ClassLoader?): Array<Class<*>> = emptyArray()

  override fun getObjectSize(objectToSize: Any): Long = 0

  override fun appendToBootstrapClassLoaderSearch(jarfile: JarFile) {}

  override fun appendToSystemClassLoaderSearch(jarfile: JarFile) {}

  override fun isNativeMethodPrefixSupported(): Boolean = false

  override fun setNativeMethodPrefix(transformer: ClassFileTransformer, prefix: String?) {}

  override fun isModifiableModule(module: Module): Boolean = false

  override fun redefineModule(
      module: Module,
      extraReads: Set<Module>,
      extraExports: Map<String, Set<Module>>,
      extraOpens: Map<String, Set<Module>>,
      extraUses: Set<Class<*>>,
      extraProvides: Map<Class<*>, List<Class<*>>>,
  ) {}
}
