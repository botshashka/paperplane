package dev.paperplane.cli.server

import com.google.gson.Gson
import dev.paperplane.cli.testing.LocalHttpServer
import dev.paperplane.cli.testing.readTestResource
import java.io.File
import java.io.IOException
import java.net.http.HttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Fill v3 response parsing and integrity-checked downloads are covered in [FillClientTest]; this
 * suite covers Velocity-specific version selection and [VelocityDownloader.download] orchestration.
 */
class VelocityDownloaderTest {

  private lateinit var http: LocalHttpServer
  private lateinit var client: HttpClient
  private val gson = Gson()

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

  private fun downloader() = VelocityDownloader(cacheDir, client, http.baseUrl)

  /** Builds a Fill v3-shaped project response (versions grouped by major line) from a flat list. */
  private fun serveVersions(vararg versions: String) {
    val grouped = versions.groupBy { it.split(".").first() }
    http.serveText("/", gson.toJson(mapOf("versions" to grouped)))
  }

  // ── golden: real captured Fill v3 project response ────────────────

  @Test
  fun `latestVersion parses real Fill v3 velocity project response shape`() {
    http.serveText("/", readTestResource("/fill/velocity-project.json"))

    // Latest stable 3.x in the captured payload (3.5.0 is only a SNAPSHOT, so it is skipped).
    assertEquals("3.4.0", downloader().latestVersion())
  }

  // ── latestVersion selection logic ─────────────────────────────────

  @Test
  fun `latestVersion picks highest stable version in the pinned series`() {
    serveVersions("1.1.9", "3.1.0", "3.1.1", "3.4.0", "3.5.0-SNAPSHOT")

    assertEquals("3.4.0", downloader().latestVersion())
  }

  @Test
  fun `latestVersion falls back to latest stable overall when series is absent`() {
    serveVersions("1.0.10", "1.1.9")

    assertEquals("1.1.9", downloader().latestVersion())
  }

  // ── download mechanics ────────────────────────────────────────────

  @Test
  fun `download resolves latest version then stores jar under cache dir`() {
    serveVersions("3.1.0", "3.4.0")
    http.serveBytes("/jar", "VELOCITY-JAR".toByteArray())
    http.serveText(
        "/versions/3.4.0/builds/latest",
        """{"downloads":{"server:default":{"name":"velocity-3.4.0-566.jar","url":"${http.baseUrl}/jar"}}}""",
    )

    val jar = downloader().download()

    assertEquals(File(cacheDir, "velocity-3.4.0.jar"), jar)
    assertEquals("VELOCITY-JAR", jar.readText())
  }

  @Test
  fun `download honors an explicit version and skips resolution`() {
    // No project-list context: if download() tried to resolve a version it would 404.
    http.serveBytes("/jar", "PINNED".toByteArray())
    http.serveText(
        "/versions/3.1.1/builds/latest",
        """{"downloads":{"server:default":{"name":"velocity-3.1.1-1.jar","url":"${http.baseUrl}/jar"}}}""",
    )

    val jar = downloader().download("3.1.1")

    assertEquals(File(cacheDir, "velocity-3.1.1.jar"), jar)
    assertEquals("PINNED", jar.readText())
  }

  @Test
  fun `download returns cached jar without hitting the network`() {
    val cached = File(cacheDir, "velocity-3.4.0.jar").apply { writeText("cached") }

    val jar = downloader().download("3.4.0")

    assertEquals(cached, jar)
    assertEquals("cached", jar.readText())
  }

  @Test
  fun `download throws and deletes partial jar when jar download fails`() {
    http.serveStatus("/jar", 404)
    http.serveText(
        "/versions/3.4.0/builds/latest",
        """{"downloads":{"server:default":{"name":"velocity-3.4.0-566.jar","url":"${http.baseUrl}/jar"}}}""",
    )

    assertThrows(IOException::class.java) { downloader().download("3.4.0") }
    assertFalse(File(cacheDir, "velocity-3.4.0.jar").exists())
  }

  @Test
  fun `download throws when builds request fails`() {
    http.serveStatus("/versions/3.4.0/builds/latest", 500)

    assertThrows(IOException::class.java) { downloader().download("3.4.0") }
    assertFalse(File(cacheDir, "velocity-3.4.0.jar").exists())
  }
}
