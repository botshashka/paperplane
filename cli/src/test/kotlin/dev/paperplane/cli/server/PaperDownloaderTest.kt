package dev.paperplane.cli.server

import java.io.IOException
import java.net.InetSocketAddress
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

class PaperDownloaderTest {

  private lateinit var server: com.sun.net.httpserver.HttpServer
  private lateinit var client: HttpClient
  private var baseUrl = ""

  @TempDir lateinit var cacheDir: java.io.File

  @BeforeEach
  fun setUp() {
    server = com.sun.net.httpserver.HttpServer.create(InetSocketAddress(0), 0)
    server.start()
    baseUrl = "http://localhost:${server.address.port}"
    client = HttpClient.newHttpClient()
  }

  @AfterEach
  fun tearDown() {
    server.stop(0)
  }

  private fun downloader() = PaperDownloader(cacheDir, client, baseUrl)

  private fun resource(path: String): String =
      checkNotNull(javaClass.getResourceAsStream(path)) { "missing test resource $path" }
          .use { it.readBytes().decodeToString() }

  /** Serves [body] as UTF-8 text at [path]. */
  private fun serveText(path: String, body: String) {
    server.createContext(path) { exchange ->
      exchange.sendResponseHeaders(200, body.length.toLong())
      exchange.responseBody.use { it.write(body.toByteArray()) }
    }
  }

  /** Serves raw [bytes] at [path]. */
  private fun serveBytes(path: String, bytes: ByteArray) {
    server.createContext(path) { exchange ->
      exchange.sendResponseHeaders(200, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
  }

  // ── golden: real Fill v3 builds/latest response ───────────────────

  @Test
  fun `parses real Fill v3 builds-latest response shape`() {
    val serverJar =
        downloader().parseLatestServerJar(resource("/fill/paper-1.21.4-builds-latest.json"))

    assertEquals("paper-1.21.4-232.jar", serverJar.name)
    assertEquals(
        "https://fill-data.papermc.io/v1/objects/" +
            "5ee4f542f628a14c644410b08c94ea42e772ef4d29fe92973636b6813d4eaffc/paper-1.21.4-232.jar",
        serverJar.url,
    )
  }

  // ── download mechanics ────────────────────────────────────────────

  @Test
  fun `download fetches builds-latest and stores jar under cache dir`() {
    val jarBytes = "PAPER-JAR-BYTES".toByteArray()
    serveBytes("/jar", jarBytes)
    serveText(
        "/versions/1.21.4/builds/latest",
        """{"downloads":{"server:default":{"name":"paper-1.21.4-232.jar","url":"$baseUrl/jar"}}}""",
    )

    val jar = downloader().download("1.21.4")

    assertEquals(java.io.File(cacheDir, "paper-1.21.4.jar"), jar)
    assertTrue(jar.exists())
    assertEquals("PAPER-JAR-BYTES", jar.readText())
  }

  @Test
  fun `download returns cached jar without hitting the network`() {
    // No contexts registered: any HTTP call would 404 and fail. The cached file must short-circuit.
    val cached = java.io.File(cacheDir, "paper-1.21.4.jar").apply { writeText("cached jar") }

    val jar = downloader().download("1.21.4")

    assertEquals(cached, jar)
    assertEquals("cached jar", jar.readText())
  }

  @Test
  fun `download throws and leaves no partial jar when metadata request fails`() {
    server.createContext("/versions/1.21.4/builds/latest") { exchange ->
      exchange.sendResponseHeaders(500, -1)
    }

    assertThrows(IOException::class.java) { downloader().download("1.21.4") }
    assertFalse(java.io.File(cacheDir, "paper-1.21.4.jar").exists())
  }

  @Test
  fun `download throws and deletes partial jar when jar download fails`() {
    server.createContext("/jar") { exchange -> exchange.sendResponseHeaders(404, -1) }
    serveText(
        "/versions/1.21.4/builds/latest",
        """{"downloads":{"server:default":{"name":"paper-1.21.4-232.jar","url":"$baseUrl/jar"}}}""",
    )

    assertThrows(IOException::class.java) { downloader().download("1.21.4") }
    assertFalse(java.io.File(cacheDir, "paper-1.21.4.jar").exists())
  }

  @Test
  fun `download creates cache dir when it does not yet exist`() {
    val nested = java.io.File(cacheDir, "nested/cache")
    val nestedDownloader = PaperDownloader(nested, client, baseUrl)
    serveBytes("/jar", "jar".toByteArray())
    serveText(
        "/versions/1.21.4/builds/latest",
        """{"downloads":{"server:default":{"name":"paper-1.21.4-232.jar","url":"$baseUrl/jar"}}}""",
    )

    val jar = nestedDownloader.download("1.21.4")

    assertTrue(jar.exists())
    assertTrue(Files.isDirectory(nested.toPath()))
  }
}
