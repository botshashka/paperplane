package dev.paperplane.cli.server

import com.sun.net.httpserver.HttpServer
import dev.paperplane.cli.Versions
import java.net.InetSocketAddress
import java.net.http.HttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PaperVersionResolverTest {

  private lateinit var server: HttpServer
  private lateinit var client: HttpClient
  private var baseUrl = ""

  @BeforeEach
  fun setUp() {
    server = HttpServer.create(InetSocketAddress(0), 0)
    server.start()
    val port = server.address.port
    baseUrl = "http://localhost:$port"
    client = HttpClient.newHttpClient()
  }

  @AfterEach
  fun tearDown() {
    server.stop(0)
  }

  private fun resolver(): PaperVersionResolver = PaperVersionResolver(client, baseUrl)

  @Test
  fun `resolveLatest returns latest version within supported range`() {
    server.createContext("/") { exchange ->
      val json =
          """{"versions":["1.18","1.18.2","1.19","1.19.4","1.20","1.20.6","1.21","1.21.4","1.21.10"]}"""
      exchange.sendResponseHeaders(200, json.length.toLong())
      exchange.responseBody.use { it.write(json.toByteArray()) }
    }

    assertEquals("1.21.10", resolver().resolveLatest())
  }

  @Test
  fun `resolveLatest filters out unsupported versions`() {
    server.createContext("/") { exchange ->
      val json = """{"versions":["1.18","1.21.4","1.22","1.22.1"]}"""
      exchange.sendResponseHeaders(200, json.length.toLong())
      exchange.responseBody.use { it.write(json.toByteArray()) }
    }

    // 1.22.x is not in SUPPORTED_API_VERSIONS, so latest supported is 1.21.4
    assertEquals("1.21.4", resolver().resolveLatest())
  }

  @Test
  fun `resolveLatest returns fallback when no versions in supported range`() {
    server.createContext("/") { exchange ->
      val json = """{"versions":["1.22","1.23"]}"""
      exchange.sendResponseHeaders(200, json.length.toLong())
      exchange.responseBody.use { it.write(json.toByteArray()) }
    }

    assertEquals(Versions.PAPER_FALLBACK, resolver().resolveLatest())
  }

  @Test
  fun `resolveLatest returns fallback on non-200 response`() {
    server.createContext("/") { exchange ->
      exchange.sendResponseHeaders(500, -1)
    }

    assertEquals(Versions.PAPER_FALLBACK, resolver().resolveLatest())
  }

  @Test
  fun `resolveLatest returns fallback on connection failure`() {
    // Stop the server to simulate connection failure
    server.stop(0)

    assertEquals(Versions.PAPER_FALLBACK, resolver().resolveLatest())
  }

  @Test
  fun `resolveLatest returns fallback on malformed JSON`() {
    server.createContext("/") { exchange ->
      val body = "not json"
      exchange.sendResponseHeaders(200, body.length.toLong())
      exchange.responseBody.use { it.write(body.toByteArray()) }
    }

    assertEquals(Versions.PAPER_FALLBACK, resolver().resolveLatest())
  }
}
