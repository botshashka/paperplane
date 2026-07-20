package dev.paperplane.cli.testing

import com.sun.net.httpserver.HttpServer
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
  private val server: HttpServer = HttpServer.create(InetSocketAddress(0), 0)

  /** Base URL (scheme + host + ephemeral port) the test's HTTP client should target. */
  val baseUrl: String

  init {
    server.start()
    baseUrl = "http://localhost:${server.address.port}"
  }

  /** Serves [body] as UTF-8 text (HTTP 200) at [path]. */
  fun serveText(path: String, body: String) {
    val bytes = body.toByteArray()
    server.createContext(path) { exchange ->
      exchange.sendResponseHeaders(200, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
  }

  /** Serves raw [bytes] (HTTP 200) at [path]. */
  fun serveBytes(path: String, bytes: ByteArray) {
    server.createContext(path) { exchange ->
      exchange.sendResponseHeaders(200, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
  }

  /** Serves [status] with an empty body at [path] (for error-path tests). */
  fun serveStatus(path: String, status: Int) {
    server.createContext(path) { exchange -> exchange.sendResponseHeaders(status, -1) }
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
