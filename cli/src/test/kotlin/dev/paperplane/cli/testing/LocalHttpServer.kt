package dev.paperplane.cli.testing

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * A throwaway loopback HTTP server for exercising the Fill v3 clients (and any other HTTP client)
 * against canned responses. Construct one per test (typically in `@BeforeEach`), register handlers
 * with the `serve*` helpers, read [baseUrl] for the client, and call [stop] in `@AfterEach`.
 *
 * Consolidates the setUp/tearDown + response-handler boilerplate previously copy-pasted across the
 * Paper/Velocity/Fill server tests.
 */
class LocalHttpServer {
  // Bind the loopback address explicitly, never the wildcard. A wildcard bind (0.0.0.0:0) can be
  // handed a port that another process already holds on 127.0.0.1 only — BSD/macOS permits the
  // wildcard+specific pair — and the client's connect to localhost then lands on *that* process
  // instead of us. (Observed once as an H2 TCP server answering a test's GET.) Binding loopback
  // makes the port allocator see the conflict and pick a genuinely free port.
  private val server: HttpServer =
      HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

  /** Base URL (scheme + host + ephemeral port) the test's HTTP client should target. */
  val baseUrl: String

  /**
   * Request URI (path plus query string) of the most recent request any handler served — lets a
   * test assert on the query parameters its client built. Empty until the first request.
   */
  @Volatile
  var lastRequestUri: String = ""
    private set

  init {
    server.start()
    baseUrl = "http://localhost:${server.address.port}"
  }

  /** Serves [body] as UTF-8 text (HTTP 200) at [path]. */
  fun serveText(path: String, body: String) {
    val bytes = body.toByteArray()
    server.createContext(path) { exchange ->
      lastRequestUri = exchange.requestURI.toString()
      exchange.sendResponseHeaders(200, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
  }

  /** Serves raw [bytes] (HTTP 200) at [path]. */
  fun serveBytes(path: String, bytes: ByteArray) {
    server.createContext(path) { exchange ->
      lastRequestUri = exchange.requestURI.toString()
      exchange.sendResponseHeaders(200, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
  }

  /** Serves [status] with an empty body at [path] (for error-path tests). */
  fun serveStatus(path: String, status: Int) {
    server.createContext(path) { exchange ->
      lastRequestUri = exchange.requestURI.toString()
      exchange.sendResponseHeaders(status, -1)
    }
  }

  /**
   * Advertises a Content-Length of [declaredLength] but writes only [actual] before closing,
   * forcing a client-side mid-stream read failure. Used to prove a truncated transfer never poisons
   * the download cache.
   */
  fun serveTruncated(path: String, declaredLength: Long, actual: ByteArray) {
    server.createContext(path) { exchange ->
      exchange.sendResponseHeaders(200, declaredLength)
      exchange.responseBody.use { it.write(actual) }
    }
  }

  fun stop() = server.stop(0)
}

/** Reads a classpath resource (absolute path, e.g. `/fill/paper-project.json`) as UTF-8 text. */
fun readTestResource(path: String): String =
    checkNotNull(LocalHttpServer::class.java.getResourceAsStream(path)) {
          "missing test resource $path"
        }
        .use { it.readBytes().decodeToString() }
