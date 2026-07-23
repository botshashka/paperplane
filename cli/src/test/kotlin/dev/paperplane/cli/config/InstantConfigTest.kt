package dev.paperplane.cli.config

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Parsing coverage for `dev.instant` — the fast lane's on/off switch. Mirrors
 * [LeakDiagnosticsConfigTest]'s structure: the default when the key is absent, and both explicit
 * values through real kaml decoding.
 */
class InstantConfigTest {

  @Test
  fun `the instant lane is on by default`() {
    assertTrue(DevConfig().instant)
    assertTrue(
        Yaml.default.decodeFromString<DevConfig>("""mode: restart""").instant,
        "an absent key must decode to the default-on lane",
    )
  }

  @Test
  fun `dev instant false parses and switches the lane off`() {
    assertFalse(Yaml.default.decodeFromString<DevConfig>("""instant: false""").instant)
  }

  @Test
  fun `dev instant parses from a full paperplane yml`() {
    val config =
        Yaml.default.decodeFromString<PaperPlaneConfig>(
            """
            dev:
              mode: restart
              instant: false
            """
                .trimIndent()
        )
    assertFalse(config.dev.instant)
  }
}
