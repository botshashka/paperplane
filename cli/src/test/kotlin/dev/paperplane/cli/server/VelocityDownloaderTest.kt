package dev.paperplane.cli.server

import com.google.gson.Gson
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.http.HttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class VelocityDownloaderTest {

  private lateinit var server: HttpServer
  private lateinit var client: HttpClient
  private var baseUrl = ""
  private val gson = Gson()

  @TempDir lateinit var cacheDir: File

  @BeforeEach
  fun setUp() {
    server = HttpServer.create(InetSocketAddress(0), 0)
    server.start()
    baseUrl = "http://localhost:${server.address.port}"
    client = HttpClient.newHttpClient()
  }

  @AfterEach
  fun tearDown() {
    server.stop(0)
  }

  private fun downloader() = VelocityDownloader(cacheDir, client, baseUrl)

  private fun resource(path: String): String =
      checkNotNull(javaClass.getResourceAsStream(path)) { "missing test resource $path" }
          .use { it.readBytes().decodeToString() }

  private fun serveText(path: String, body: String) {
    server.createContext(path) { exchange ->
      exchange.sendResponseHeaders(200, body.length.toLong())
      exchange.responseBody.use { it.write(body.toByteArray()) }
    }
  }

  private fun serveBytes(path: String, bytes: ByteArray) {
    server.createContext(path) { exchange ->
      exchange.sendResponseHeaders(200, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
  }

  /** Builds a Fill v3-shaped project response (versions grouped by major line) from a flat list. */
  private fun serveVersions(vararg versions: String) {
    val grouped = versions.groupBy { it.split(".").first() }
    serveText("/", gson.toJson(mapOf("versions" to grouped)))
  }

  // ── golden: real captured Fill v3 responses ───────────────────────

  @Test
  fun `parses real Fill v3 velocity builds-latest response shape`() {
    val serverJar =
        downloader().parseLatestServerJar(resource("/fill/velocity-3.4.0-builds-latest.json"))

    assertEquals("velocity-3.4.0-566.jar", serverJar.name)
    assertEquals(
        "https://fill-data.papermc.io/v1/objects/" +
            "fb599cbda6a6d01decce5e281f71f51cae7cacffcfafca32a09601f407b0583e/velocity-3.4.0-566.jar",
        serverJar.url,
    )
  }

  @Test
  fun `latestVersion parses real Fill v3 velocity project response shape`() {
    serveText("/", resource("/fill/velocity-project.json"))

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
    serveBytes("/jar", "VELOCITY-JAR".toByteArray())
    serveText(
        "/versions/3.4.0/builds/latest",
        """{"downloads":{"server:default":{"name":"velocity-3.4.0-566.jar","url":"$baseUrl/jar"}}}""",
    )

    val jar = downloader().download()

    assertEquals(File(cacheDir, "velocity-3.4.0.jar"), jar)
    assertEquals("VELOCITY-JAR", jar.readText())
  }

  @Test
  fun `download honors an explicit version and skips resolution`() {
    // No project-list context: if download() tried to resolve a version it would 404.
    serveBytes("/jar", "PINNED".toByteArray())
    serveText(
        "/versions/3.1.1/builds/latest",
        """{"downloads":{"server:default":{"name":"velocity-3.1.1-1.jar","url":"$baseUrl/jar"}}}""",
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
    server.createContext("/jar") { exchange -> exchange.sendResponseHeaders(404, -1) }
    serveText(
        "/versions/3.4.0/builds/latest",
        """{"downloads":{"server:default":{"name":"velocity-3.4.0-566.jar","url":"$baseUrl/jar"}}}""",
    )

    assertThrows(IOException::class.java) { downloader().download("3.4.0") }
    assertFalse(File(cacheDir, "velocity-3.4.0.jar").exists())
  }

  @Test
  fun `download throws when builds request fails`() {
    server.createContext("/versions/3.4.0/builds/latest") { exchange ->
      exchange.sendResponseHeaders(500, -1)
    }

    assertThrows(IOException::class.java) { downloader().download("3.4.0") }
    assertFalse(File(cacheDir, "velocity-3.4.0.jar").exists())
  }
}
