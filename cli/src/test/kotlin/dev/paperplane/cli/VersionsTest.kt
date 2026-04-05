package dev.paperplane.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VersionsTest {

  @Test
  fun `apiVersion extracts major minor from three-part version`() {
    assertEquals("1.21", Versions.apiVersion("1.21.10"))
  }

  @Test
  fun `apiVersion returns two-part version unchanged`() {
    assertEquals("1.18", Versions.apiVersion("1.18"))
  }

  @Test
  fun `apiVersion handles four-part version`() {
    assertEquals("1.21", Versions.apiVersion("1.21.10.1"))
  }

  @Test
  fun `mockbukkitArtifact derives artifact name from mc version`() {
    assertEquals("mockbukkit-v1.21", Versions.mockbukkitArtifact("1.21.10"))
  }

  @Test
  fun `mockbukkitArtifact works with two-part version`() {
    assertEquals("mockbukkit-v1.20", Versions.mockbukkitArtifact("1.20"))
  }

  @Test
  fun `PAPER_FALLBACK is within supported range`() {
    val api = Versions.apiVersion(Versions.PAPER_FALLBACK)
    assertTrue(api in Versions.SUPPORTED_API_VERSIONS, "PAPER_FALLBACK api-version $api not in SUPPORTED_API_VERSIONS")
  }

  @Test
  fun `paperplaneVersion returns non-empty string`() {
    // In test context there's no JAR manifest, so this should return the fallback
    assertTrue(Versions.paperplaneVersion().isNotEmpty())
  }
}
