package dev.paperplane.cli.server

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

/**
 * Rewrites the embedded companion JAR's `plugin.yml` to inherit the user's `depend` and
 * `softdepend` declarations.
 *
 * Why this exists: with the wrapper-plugin architecture, the user's plugin lives in
 * `.paperplane/staged/` — Paper never auto-loads it, so the user's `depend` declarations no longer
 * constrain Paper's load order. If the user's plugin imports classes from `WorldGuard`,
 * `WorldGuard` must be loaded before the companion (which loads the user's plugin). The CLI
 * achieves this by claiming those depends on the companion's behalf.
 *
 * Idempotent: removes any existing `depend:` / `softdepend:` lines before appending the inherited
 * values. Run on every `ppl dev` boot.
 */
object CompanionJarRewriter {

  /**
   * Reads the companion JAR bytes from [companionJarBytes], rewrites the embedded `plugin.yml` to
   * include [depend] and [softdepend], and writes the result to [outputJar].
   */
  fun rewrite(
      companionJarBytes: () -> InputStream,
      outputJar: File,
      depend: List<String>,
      softdepend: List<String>,
  ) {
    outputJar.parentFile?.mkdirs()
    val tmp = File(outputJar.parentFile, ".${outputJar.name}.tmp")
    JarOutputStream(tmp.outputStream()).use { jos ->
      JarFile(spillToTempJar(companionJarBytes())).use { source ->
        val entries = source.entries()
        while (entries.hasMoreElements()) {
          writeEntry(source, entries.nextElement(), jos, depend, softdepend)
        }
      }
    }
    java.nio.file.Files.move(
        tmp.toPath(),
        outputJar.toPath(),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
    )
  }

  /** Copies [entry] into [jos], rewriting `plugin.yml` to carry the inherited depends. */
  private fun writeEntry(
      source: JarFile,
      entry: JarEntry,
      jos: JarOutputStream,
      depend: List<String>,
      softdepend: List<String>,
  ) {
    if (entry.name == "plugin.yml") {
      val original = source.getInputStream(entry).bufferedReader().readText()
      val rewritten = injectDepends(original, depend, softdepend)
      jos.putNextEntry(JarEntry("plugin.yml"))
      jos.write(rewritten.toByteArray())
      jos.closeEntry()
    } else {
      jos.putNextEntry(JarEntry(entry.name))
      source.getInputStream(entry).use { it.copyTo(jos) }
      jos.closeEntry()
    }
  }

  /** Convenience: reads from a raw byte array. Used by the CLI's resource-extraction path. */
  fun rewriteFromBytes(
      bytes: ByteArray,
      outputJar: File,
      depend: List<String>,
      softdepend: List<String>,
  ) = rewrite({ ByteArrayInputStream(bytes) }, outputJar, depend, softdepend)

  /**
   * Strips any existing `depend:` / `softdepend:` lines from [original] and appends new ones from
   * [depend] / [softdepend] (only when non-empty). Plain text rewrite — keeps the existing
   * companion plugin.yml shape intact (no YAML parser).
   *
   * Exposed for unit testing.
   */
  internal fun injectDepends(
      original: String,
      depend: List<String>,
      softdepend: List<String>,
  ): String {
    val keptLines =
        original.lines().filter {
          val trimmed = it.trimStart()
          !trimmed.startsWith("depend:") && !trimmed.startsWith("softdepend:")
        }

    val sb = StringBuilder()
    sb.appendLine(keptLines.dropLastWhile { it.isBlank() }.joinToString("\n"))
    if (depend.isNotEmpty()) {
      sb.appendLine("depend: [${depend.joinToString(", ")}]")
    }
    if (softdepend.isNotEmpty()) {
      sb.appendLine("softdepend: [${softdepend.joinToString(", ")}]")
    }
    return sb.toString()
  }

  /**
   * `JarFile` requires a seekable file. The CLI extracts the companion as a stream from classpath
   * resources, so we spill to a temp file first. The temp file lives only for the duration of
   * [rewrite]'s `use {}` block.
   */
  private fun spillToTempJar(stream: InputStream): File {
    val tmp = java.nio.file.Files.createTempFile("paperplane-companion-", ".jar").toFile()
    tmp.deleteOnExit()
    stream.use { it.copyTo(tmp.outputStream()) }
    return tmp
  }
}
