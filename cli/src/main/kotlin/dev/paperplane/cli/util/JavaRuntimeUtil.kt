package dev.paperplane.cli.util

import java.util.concurrent.TimeUnit

object JavaRuntimeUtil {
  private const val JBR_CHECK_TIMEOUT_SECONDS = 5L

  fun checkIsJbr(javaBin: String): Boolean {
    return try {
      val proc = ProcessBuilder(javaBin, "-version").redirectErrorStream(true).start()
      val output = proc.inputStream.bufferedReader().readText()
      proc.waitFor(JBR_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      output.contains("JetBrains", ignoreCase = true) || output.contains("JBR", ignoreCase = true)
    } catch (_: Exception) {
      false
    }
  }
}
