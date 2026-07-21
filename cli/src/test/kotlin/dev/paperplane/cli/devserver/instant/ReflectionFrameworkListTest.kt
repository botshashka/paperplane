package dev.paperplane.cli.devserver.instant

import dev.paperplane.cli.gradle.ProjectMetadata
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReflectionFrameworkListTest {

  private fun metadata(
      depend: List<String> = emptyList(),
      softdepend: List<String> = emptyList(),
      runtimeClasspath: List<String> = emptyList(),
  ) =
      ProjectMetadata(
          jarPath = "build/libs/test.jar",
          paperApiVersion = "1.21.4",
          mainClass = "com.example.Main",
          pluginName = "Test",
          projectDir = "/proj",
          version = "1.0.0",
          depend = depend,
          softdepend = softdepend,
          runtimeClasspath = runtimeClasspath,
      )

  @Test
  fun `matches a listed framework in depend, softdepend, or the classpath, case-insensitively`() {
    assertEquals("Skript", ReflectionFrameworkList.match(metadata(depend = listOf("Skript"))))
    assertEquals("Skript", ReflectionFrameworkList.match(metadata(softdepend = listOf("skript"))))
    assertEquals(
        "Skript",
        ReflectionFrameworkList.match(
            metadata(runtimeClasspath = listOf("/libs/Skript-2.9.0.jar"))
        ),
    )
  }

  @Test
  fun `an ordinary dependency set matches nothing`() {
    assertNull(
        ReflectionFrameworkList.match(
            metadata(
                depend = listOf("Vault", "PlaceholderAPI"),
                runtimeClasspath = listOf("/libs/kotlin-stdlib-2.0.0.jar"),
            )
        )
    )
  }
}
