package dev.paperplane.cli.testing

import dev.paperplane.cli.server.PaperDownloader
import java.io.File

/**
 * Test fake [PaperDownloader] that returns a stub jar file without touching the network. The stub
 * file is created on demand inside the configured cache dir so anything that calls `.exists()` on
 * the result returns true.
 */
class FakePaperDownloader(private val cacheDir: File) : PaperDownloader(cacheDir) {
  override fun download(mcVersion: String): File {
    cacheDir.mkdirs()
    return File(cacheDir, "paper-$mcVersion-fake.jar").apply {
      if (!exists()) writeText("fake paper jar")
    }
  }
}
