package dev.paperplane.cli.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YamlDeepMergeTest {

  @Test
  fun `override adds new top-level key`() {
    val base = "chunks:\n  auto-save-interval: -1\n"
    val override = yamlMap("anticheat:\n  anti-xray:\n    enabled: false\n")
    val merged = YamlDeepMerge.merge(base, override)
    assertTrue(merged.contains("auto-save-interval"), merged)
    assertTrue(merged.contains("anticheat"), merged)
  }

  @Test
  fun `override wins on leaf scalar conflict`() {
    val base = "timings:\n  enabled: false\n"
    val override = yamlMap("timings:\n  enabled: true\n")
    val merged = YamlDeepMerge.merge(base, override)
    // Round-trip the merged result so assertions aren't coupled to kaml's output quoting style.
    val roundTripped = yamlMap(merged)
    val enabled =
        ((roundTripped.entries.entries.first().value as com.charleskorn.kaml.YamlMap).entries.entries
                .first()
                .value as com.charleskorn.kaml.YamlScalar)
            .content
    assertEquals("true", enabled)
  }

  @Test
  fun `nested maps are recursively merged preserving unrelated keys`() {
    val base =
        """
        chunks:
          auto-save-interval: -1
          delay-chunk-unloads-by: 10s
        spawn:
          keep-spawn-loaded: false
        """
            .trimIndent() + "\n"
    val override =
        yamlMap(
            """
            chunks:
              delay-chunk-unloads-by: 0s
            """
                .trimIndent() + "\n"
        )
    val merged = YamlDeepMerge.merge(base, override)
    // Unrelated keys survived
    assertTrue(merged.contains("auto-save-interval"), merged)
    assertTrue(merged.contains("keep-spawn-loaded"), merged)
    // Overridden leaf took the new value
    assertTrue(merged.contains("0s"), merged)
    assertTrue(!merged.contains("10s"), merged)
  }

  @Test
  fun `null override returns base unchanged`() {
    val base = "foo: bar\n"
    val merged = YamlDeepMerge.merge(base, null)
    assertTrue(merged.contains("foo"))
  }

  @Test
  fun `list values are replaced wholesale not element-merged`() {
    val base = "enabled-packs:\n  - vanilla\n  - bundle\n"
    val override = yamlMap("enabled-packs:\n  - vanilla\n  - minecart_improvements\n")
    val merged = YamlDeepMerge.merge(base, override)
    assertTrue(merged.contains("minecart_improvements"), merged)
    assertTrue(!merged.contains("bundle"), merged)
  }
}
