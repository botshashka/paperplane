package dev.paperplane.cli.testing

import dev.paperplane.cli.server.VelocityDownloader
import java.io.File

/**
 * Test fake [VelocityDownloader] that returns a stub jar file without touching the network. The
 * stub file is created on demand inside the configured cache dir so anything that calls `.exists()`
 * on the result returns true.
 */
class FakeVelocityDownloader(private val cacheDir: File) : VelocityDownloader(cacheDir) {
  override fun download(version: String?): File {
    cacheDir.mkdirs()
    return File(cacheDir, "velocity-fake.jar").apply {
      if (!exists()) writeText("fake velocity jar")
    }
  }
}
