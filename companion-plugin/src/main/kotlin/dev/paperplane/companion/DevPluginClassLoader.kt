package dev.paperplane.companion

import org.bukkit.plugin.PluginManager
import java.net.URL
import java.net.URLClassLoader

/**
 * A classloader for dev-mode plugin loading from build output directories.
 *
 * Unlike Paper's PluginClassLoader (which is internal and version-specific),
 * this extends plain URLClassLoader and replicates cross-plugin class visibility
 * by falling back to other plugins' classloaders. This allows plugins with
 * `depend` or `softdepend` relationships to work correctly in HMR mode.
 *
 * Delegation order:
 * 1. Already loaded classes (JVM cache)
 * 2. Parent classloader (Paper API, JDK, Adventure)
 * 3. Own URLs (plugin classes from build dirs + dependency JARs)
 * 4. Other plugins' classloaders (cross-plugin visibility)
 */
class DevPluginClassLoader(
    urls: Array<URL>,
    parent: ClassLoader,
    private val pluginManager: PluginManager
) : URLClassLoader(urls, parent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        synchronized(getClassLoadingLock(name)) {
            // 1. Check already loaded
            findLoadedClass(name)?.let { return it }

            // 2. Try parent (Paper API, JDK, Adventure)
            try {
                return parent.loadClass(name)
            } catch (_: ClassNotFoundException) {}

            // 3. Try our own URLs (plugin classes + dependency JARs)
            try {
                val c = findClass(name)
                if (resolve) resolveClass(c)
                return c
            } catch (_: ClassNotFoundException) {}

            // 4. Try other plugins' classloaders (cross-plugin visibility)
            for (plugin in pluginManager.plugins) {
                val cl = plugin.javaClass.classLoader
                if (cl === this) continue
                try {
                    return cl.loadClass(name)
                } catch (_: ClassNotFoundException) {}
            }

            throw ClassNotFoundException(name)
        }
    }
}
