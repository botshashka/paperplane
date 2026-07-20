package dev.paperplane.cli.plugins

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PluginDependencyTest {

  @Test
  fun `modrinth entry without version`() {
    val dep = PluginDependency.modrinth("placeholderapi")
    assertEquals("placeholderapi", dep.slug)
    assertEquals(PluginDependency.Source.MODRINTH, dep.source)
    assertEquals(null, dep.version)
  }

  @Test
  fun `modrinth entry with version`() {
    val dep = PluginDependency.modrinth("vault", "1.7.3")
    assertEquals("vault", dep.slug)
    assertEquals("1.7.3", dep.version)
  }

  @Test
  fun `local entry derives slug from path`() {
    val dep = PluginDependency.local("./libs/my-plugin.jar")
    assertEquals("my-plugin", dep.slug)
    assertEquals(PluginDependency.Source.LOCAL, dep.source)
  }

  @Test
  fun `entry rejects both sources set`() {
    assertThrows(IllegalArgumentException::class.java) {
      PluginDependency(modrinth = "x", local = "./y.jar")
    }
  }

  @Test
  fun `entry rejects no source set`() {
    assertThrows(IllegalArgumentException::class.java) { PluginDependency() }
  }

  @Test
  fun `modrinth factory preserves casing to support case-sensitive project IDs`() {
    // Modrinth project IDs (e.g. "1u6JkXh5") are case-sensitive on the API side, so the
    // factory must NOT lowercase prematurely. Canonicalization to the lowercase slug happens
    // downstream in AddPluginCommand after calling /project/{id}. Display-level comparisons
    // still normalize via the slug getter.
    val dep = PluginDependency.modrinth("1u6JkXh5")
    assertEquals("1u6JkXh5", dep.modrinth)
    assertEquals("1u6jkxh5", dep.slug) // getter normalizes for comparison
  }

  @Test
  fun `slug getter lowercases hand-edited mixed-case modrinth field`() {
    // Simulates a user hand-editing paperplane.yml with `- modrinth: "Vault"` — the getter
    // normalizes so all downstream comparisons match the canonical lowercase form.
    val dep = PluginDependency(modrinth = "Vault")
    assertEquals("vault", dep.slug)
  }

  @Test
  fun `slug getter lowercases local filename-derived slug`() {
    val dep = PluginDependency.local("./libs/MyPlugin.jar")
    assertEquals("myplugin", dep.slug)
  }

  @Test
  fun `local entry rejects version field`() {
    assertThrows(IllegalArgumentException::class.java) {
      PluginDependency(local = "./x.jar", version = "1.0")
    }
  }

  @Test
  fun `yaml round trip preserves modrinth entries`() {
    val yaml = Yaml.default
    val original =
        listOf(
            PluginDependency.modrinth("placeholderapi"),
            PluginDependency.modrinth("vault", "1.7.3"),
        )
    val encoded = yaml.encodeToString(ListSerializer(PluginDependency.serializer()), original)
    val decoded: List<PluginDependency> =
        yaml.decodeFromString(ListSerializer(PluginDependency.serializer()), encoded)
    assertEquals(original, decoded)
  }

  @Test
  fun `yaml decodes natural shape with bare modrinth key`() {
    val text =
        """
        - modrinth: "placeholderapi"
        - modrinth: "vault"
          version: "1.7.3"
        """
            .trimIndent()
    val decoded: List<PluginDependency> =
        Yaml.default.decodeFromString(ListSerializer(PluginDependency.serializer()), text)
    assertEquals(2, decoded.size)
    assertEquals("placeholderapi", decoded[0].slug)
    assertEquals(null, decoded[0].version)
    assertEquals("vault", decoded[1].slug)
    assertEquals("1.7.3", decoded[1].version)
    assertTrue(decoded.all { it.source == PluginDependency.Source.MODRINTH })
    assertFalse(decoded.any { it.local != null })
  }
}
