package dev.paperplane.cli.util

import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipInputStream

object Platform {
  private const val BYTES_PER_GB = 1_073_741_824.0
  private const val BYTES_PER_MB = 1_048_576.0
  private const val BYTES_PER_KB = 1024.0

  enum class Os {
    WINDOWS,
    MACOS,
    LINUX,
  }

  val os: Os by lazy {
    val name = System.getProperty("os.name", "").lowercase()
    when {
      name.contains("win") -> Os.WINDOWS
      name.contains("mac") || name.contains("darwin") -> Os.MACOS
      else -> Os.LINUX
    }
  }

  val isWindows
    get() = os == Os.WINDOWS

  val userHome: File
    get() = File(System.getProperty("user.home"))

  val paperplaneHome: File
    get() = File(userHome, ".paperplane")

  fun dirSize(dir: File): Long {
    if (!dir.exists()) return 0
    return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
  }

  fun formatSize(bytes: Long): String {
    return when {
      bytes >= BYTES_PER_GB -> "%.1f GB".format(Locale.US, bytes / BYTES_PER_GB)
      bytes >= BYTES_PER_MB -> "%.1f MB".format(Locale.US, bytes / BYTES_PER_MB)
      bytes >= BYTES_PER_KB -> "%.1f KB".format(Locale.US, bytes / BYTES_PER_KB)
      else -> "$bytes B"
    }
  }

  /**
   * Extracts a .zip file to [targetDir] with zip-slip protection.
   *
   * @param stripTopLevel If true, removes the first path component from each entry (e.g.
   *   "ppl-0.2.0/bin/ppl" → "bin/ppl"). Entries that have no path after stripping are skipped.
   * @param markExecutable Called with the (possibly stripped) entry name. Return true to set the
   *   executable bit on the extracted file.
   */
  fun extractZip(
      zipFile: File,
      targetDir: File,
      stripTopLevel: Boolean = false,
      markExecutable: (String) -> Boolean = { false },
  ) {
    ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
      var entry = zis.nextEntry
      while (entry != null) {
        val name = if (stripTopLevel) entry.name.substringAfter("/", "") else entry.name
        if (name.isNotEmpty()) {
          val outFile = File(targetDir, name)
          if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath + File.separator)) {
            throw IOException("Zip entry outside target directory: ${entry.name}")
          }
          if (entry.isDirectory) {
            outFile.mkdirs()
          } else {
            outFile.parentFile.mkdirs()
            outFile.outputStream().use { out -> zis.copyTo(out) }
            if (markExecutable(name)) {
              outFile.setExecutable(true)
            }
          }
        }
        zis.closeEntry()
        entry = zis.nextEntry
      }
    }
  }
}
