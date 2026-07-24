package dev.paperplane.cli.devserver

import dev.paperplane.cli.config.DevMode
import dev.paperplane.cli.config.PaperPlaneConfig
import dev.paperplane.cli.gradle.GradleBridge
import dev.paperplane.cli.ui.RecordingTerminal
import dev.paperplane.cli.ui.TerminalUI
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Golden test against a REAL captured `metadata.json` — the source of truth for the shape the
 * scanner runs on end-to-end (bytes on disk → [GradleBridge.parseMetadataFile] → [ModeSelector]).
 *
 * Capture procedure (2026-07-24): `ppl create` a scratch project, add
 * `implementation("dev.jorel:commandapi-bukkit-shade:10.1.2")` to build.gradle.kts, run `ppl dev`
 * once, copy `build/paperplane/metadata.json` verbatim into
 * `fixtures/metadata-commandapi-real.json`. Hand-rolled ProjectMetadata values elsewhere test the
 * scanner's logic; only this fixture proves the real producer (the gradle-plugin's MetadataTask,
 * running in the user's Gradle JVM) and this module's parser haven't drifted apart — e.g. a renamed
 * `runtimeClasspath` key would silently blind the classpath scan while every hand-rolled test
 * stayed green.
 */
class ModeSelectorGoldenTest {

  @TempDir lateinit var tempDir: File

  @Test
  fun `parses real metadata json shape and fires the CommandAPI classpath rule`() {
    val fixtureBytes =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/metadata-commandapi-real.json")) {
              "captured fixture missing from test resources"
            }
            .readBytes()
    val fixture = File(tempDir, "metadata.json").apply { writeBytes(fixtureBytes) }

    val bridge = GradleBridge(tempDir, TerminalUI(RecordingTerminal()))
    val metadata = bridge.parseMetadataFile(fixture)!!

    // The real Gradle-cache path shape: .../dev.jorel/commandapi-bukkit-shade/10.1.2/<hash>/<jar>.
    assertEquals(1, metadata.runtimeClasspath.size)
    assertTrue(metadata.runtimeClasspath.single().endsWith("commandapi-bukkit-shade-10.1.2.jar"))
    assertEquals("1.21", metadata.paperApiVersion)

    val rejections =
        ModeSelector()
            .rejections(DevMode.HOT_RELOAD, PaperPlaneConfig(), metadata, serverPluginsDir = null)

    assertEquals(1, rejections.size)
    assertEquals("commandapi", rejections[0].ruleId)
    assertEquals("runtime classpath 'commandapi-bukkit-shade-10.1.2'", rejections[0].matchedBy)
  }
}
