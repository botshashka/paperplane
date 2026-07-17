package dev.paperplane.cli.server

import dev.paperplane.cli.testing.LocalHttpServer
import java.io.File
import java.io.IOException
import java.net.http.HttpClient
import java.nio.file.Files
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Fill v3 response parsing and integrity-checked downloads are covered in [FillClientTest]; this
 * suite covers the Paper-specific orchestration in [PaperDownloader.download].
 */
class PaperDownloaderTest {

  private lateinit var http: LocalHttpServer
  private lateinit var client: HttpClient

  @TempDir lateinit var cacheDir: File

  @BeforeEach
  fun setUp() {
    http = LocalHttpServer()
    client = HttpClient.newHttpClient()
  }

  @AfterEach
  fun tearDown() {
    http.stop()
  }

  private fun downloader() = PaperDownloader(cacheDir, client, http.baseUrl)

  @Test
  fun `download fetches builds-latest and stores jar under cache dir`() {
    http.serveBytes("/jar", "PAPER-JAR-BYTES".toByteArray())
    http.serveText(
        "/versions/1.21.4/builds/latest",
        """{"downloads":{"server:default":{"name":"paper-1.21.4-232.jar","url":"${http.baseUrl}/jar"}}}""",
    )

    val jar = downloader().download("1.21.4")

    assertEquals(File(cacheDir, "paper-1.21.4.jar"), jar)
    assertTrue(jar.exists())
    assertEquals("PAPER-JAR-BYTES", jar.readText())
  }

  @Test
  fun `download returns cached jar without hitting the network`() {
    // No contexts registered: any HTTP call would 404 and fail. The cached file must short-circuit.
    val cached = File(cacheDir, "paper-1.21.4.jar").apply { writeText("cached jar") }

    val jar = downloader().download("1.21.4")

    assertEquals(cached, jar)
    assertEquals("cached jar", jar.readText())
  }

  @Test
  fun `download throws and leaves no partial jar when metadata request fails`() {
    http.serveStatus("/versions/1.21.4/builds/latest", 500)

    assertThrows(IOException::class.java) { downloader().download("1.21.4") }
    assertFalse(File(cacheDir, "paper-1.21.4.jar").exists())
  }

  @Test
  fun `download throws and deletes partial jar when jar download fails`() {
    http.serveStatus("/jar", 404)
    http.serveText(
        "/versions/1.21.4/builds/latest",
        """{"downloads":{"server:default":{"name":"paper-1.21.4-232.jar","url":"${http.baseUrl}/jar"}}}""",
    )

    assertThrows(IOException::class.java) { downloader().download("1.21.4") }
    assertFalse(File(cacheDir, "paper-1.21.4.jar").exists())
  }

  @Test
  fun `download creates cache dir when it does not yet exist`() {
    val nested = File(cacheDir, "nested/cache")
    val nestedDownloader = PaperDownloader(nested, client, http.baseUrl)
    http.serveBytes("/jar", "jar".toByteArray())
    http.serveText(
        "/versions/1.21.4/builds/latest",
        """{"downloads":{"server:default":{"name":"paper-1.21.4-232.jar","url":"${http.baseUrl}/jar"}}}""",
    )

    val jar = nestedDownloader.download("1.21.4")

    assertTrue(jar.exists())
    assertTrue(Files.isDirectory(nested.toPath()))
  }
}
