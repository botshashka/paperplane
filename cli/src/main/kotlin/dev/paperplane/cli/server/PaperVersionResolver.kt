package dev.paperplane.cli.server

import dev.paperplane.cli.Versions
import java.net.http.HttpClient
import java.time.Duration

class PaperVersionResolver(
    private val client: HttpClient = HttpClient.newHttpClient(),
    private val baseUrl: String = "https://fill.papermc.io/v3/projects/paper",
) {
  companion object {
    private const val TIMEOUT_SECONDS = 5L
  }

  private val fill = FillClient(client, "Paper", Duration.ofSeconds(TIMEOUT_SECONDS))

  @Volatile private var cachedVersions: List<String>? = null

  fun resolveLatest(): String = fetchSupportedVersions().lastOrNull() ?: Versions.PAPER_FALLBACK

  /** Returns the last [count] supported stable Paper versions, latest last. */
  fun resolveRecent(count: Int = 3): List<String> {
    val supported = fetchSupportedVersions()
    return if (supported.isEmpty()) listOf(Versions.PAPER_FALLBACK) else supported.takeLast(count)
  }

  /**
   * Fetches and filters the supported stable Paper versions. Successful results are memoized for
   * the lifetime of this resolver instance so a single wizard run can call [resolveLatest] and
   * [resolveRecent] without a second HTTP round-trip. Failures are NOT cached — a transient network
   * blip should not permanently stick the resolver to fallback mode if the instance happens to be
   * reused.
   */
  private fun fetchSupportedVersions(): List<String> {
    cachedVersions?.let {
      return it
    }
    val result =
        try {
          // Fill v3 groups versions in a map keyed by minor line, each list ordered newest-first:
          // {"versions":{"1.21":["1.21.11","1.21.10",...],"1.20":[...]}}. Flatten, filter to
          // supported stable versions, and sort ascending (latest last) since the API order is
          // both grouped and descending.
          fill
              .parseVersionsMap(fill.fetch(baseUrl))
              .filter {
                Versions.apiVersion(it) in Versions.SUPPORTED_API_VERSIONS && !it.contains("-")
              }
              .sortedWith(Versions::compareVersions)
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
          emptyList()
        }
    if (result.isNotEmpty()) cachedVersions = result
    return result
  }
}
