package dev.paperplane.cli.devserver

import com.github.ajalt.clikt.core.ProgramResult
import dev.paperplane.cli.config.DevConfig
import dev.paperplane.cli.config.DevMode
import dev.paperplane.cli.config.FallbackPolicy
import dev.paperplane.cli.config.PaperPlaneConfig
import dev.paperplane.cli.config.ServerConfig
import dev.paperplane.cli.gradle.MetadataResult
import dev.paperplane.cli.plugins.PluginDependency
import dev.paperplane.cli.testing.DevSessionFixture
import dev.paperplane.cli.ui.InteractivePrompts
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The session-start consent flow: all three `dev.fallback` paths (ask-accept, ask-decline, auto),
 * the non-interactive failure, the demotion-target choice, and the preflight edge cases (broken
 * build, missing gradle plugin).
 */
class ModeSelectionFlowTest {

  @TempDir lateinit var tempDir: File

  private lateinit var originalStdin: InputStream

  @BeforeEach
  fun saveStdin() {
    originalStdin = System.`in`
  }

  @AfterEach
  fun restoreStdin() {
    System.setIn(originalStdin)
  }

  private fun stubStdin(input: String) = System.setIn(ByteArrayInputStream(input.toByteArray()))

  private fun config(
      mode: DevMode = DevMode.HOT_RELOAD,
      fallback: FallbackPolicy = FallbackPolicy.ASK,
      plugins: List<PluginDependency> = emptyList(),
  ) =
      PaperPlaneConfig(
          server = ServerConfig(plugins = plugins),
          dev = DevConfig(mode = mode, fallback = fallback),
      )

  private fun fixture(config: PaperPlaneConfig = config(), isTty: Boolean = true) =
      DevSessionFixture(tempDir, config = config, isTty = isTty)

  private fun flow(fixture: DevSessionFixture) =
      ModeSelectionFlow(fixture.ui, InteractivePrompts(fixture.terminal))

  private fun resolve(
      fixture: DevSessionFixture,
      requested: DevMode = DevMode.HOT_RELOAD,
      configuredMode: DevMode = requested,
  ) = flow(fixture).resolve(fixture.session, requested, configuredMode)

  private val commandApiPlugin = listOf(PluginDependency.modrinth("commandapi"))

  // ── pass-through paths ──────────────────────────────────────────────

  @Test
  fun `native mode requests pass through without preflight`() {
    for (mode in listOf(DevMode.RESTART, DevMode.BLUE_GREEN)) {
      val fx = fixture(config(mode = mode))
      assertEquals(mode, resolve(fx, requested = mode))
      assertTrue(fx.gradle.calls.isEmpty(), "no Gradle work for a native request")
      assertNull(fx.session.selectionReport)
    }
  }

  @Test
  fun `clean hot-reload request preflights and passes through`() {
    val fx = fixture()
    assertEquals(DevMode.HOT_RELOAD, resolve(fx))
    assertEquals(listOf("metadata"), fx.gradle.calls)
    assertNull(fx.session.selectionReport)
    // The preflight is cached: the mode's own resolveMetadata must not re-invoke Gradle.
    fx.session.resolveMetadata()
    assertEquals(listOf("metadata"), fx.gradle.calls)
  }

  @Test
  fun `missing gradle plugin skips selection entirely`() {
    // The dispatched mode prints the ppl init hint and exits; a demotion prompt first would be
    // noise about a session that cannot start anyway — even with a rejectable dependency in config.
    val fx = fixture(config(plugins = commandApiPlugin))
    fx.gradle.nextMetadata = null // derives MetadataResult.PluginNotApplied
    assertEquals(DevMode.HOT_RELOAD, resolve(fx))
    assertNull(fx.session.selectionReport)
  }

  // ── consent: ask ────────────────────────────────────────────────────

  @Test
  fun `rejection with ask and bare Enter demotes to restart`() {
    stubStdin("\n")
    val fx = fixture(config(plugins = commandApiPlugin))

    assertEquals(DevMode.RESTART, resolve(fx))

    assertEquals(DevMode.RESTART, fx.session.config.dev.mode)
    val report = fx.session.selectionReport!!
    assertEquals(DevMode.HOT_RELOAD, report.requested)
    assertEquals("commandapi", report.rejections.single().ruleId)
    // The rejection banner and the consent prompt both rendered.
    assertTrue(fx.terminal.writes.any { it.contains("Hot-reload is unavailable") })
    assertTrue(fx.terminal.raw.contains("Switch this session to restart? (Y/n)"))
  }

  @Test
  fun `rejection with ask and explicit no ends the session`() {
    stubStdin("n\n")
    val fx = fixture(config(plugins = commandApiPlugin))

    assertNull(resolve(fx))

    // Mode untouched, no report — the session simply doesn't start.
    assertEquals(DevMode.HOT_RELOAD, fx.session.config.dev.mode)
    assertNull(fx.session.selectionReport)
    assertTrue(fx.terminal.writes.any { it.contains("dev.mode: restart") })
  }

  // ── consent: auto ───────────────────────────────────────────────────

  @Test
  fun `rejection with auto demotes behind a banner without prompting`() {
    // No stdin stub: a prompt would read EOF and decline, so a demotion proves no prompt ran.
    stubStdin("")
    val fx = fixture(config(fallback = FallbackPolicy.AUTO, plugins = commandApiPlugin))

    assertEquals(DevMode.RESTART, resolve(fx))

    assertTrue(fx.terminal.writes.any { it.contains("Hot-reload is unavailable") })
    assertTrue(
        fx.terminal.writes.any {
          it.contains("Falling back to restart") && it.contains("dev.fallback: auto")
        },
        "auto demotion must be announced by a banner; writes were ${fx.terminal.writes}",
    )
  }

  // ── non-interactive ─────────────────────────────────────────────────

  @Test
  fun `rejection with ask and no TTY fails loudly naming the unblocking config keys`() {
    val fx = fixture(config(plugins = commandApiPlugin), isTty = false)

    val ex = assertThrows(ProgramResult::class.java) { resolve(fx) }

    assertEquals(1, ex.statusCode)
    assertTrue(fx.terminal.writes.any { it.contains("no interactive terminal") })
    assertTrue(fx.terminal.writes.any { it.contains("dev.fallback: auto") })
    assertTrue(fx.terminal.writes.any { it.contains("dev.mode: restart") })
  }

  @Test
  fun `no TTY without a rejection passes through silently`() {
    val fx = fixture(isTty = false)
    assertEquals(DevMode.HOT_RELOAD, resolve(fx))
  }

  // ── demotion target ─────────────────────────────────────────────────

  @Test
  fun `configured blue-green steers the demotion target to blue-green`() {
    // `ppl dev --mode hot-reload` in a project whose standing mode is blue-green: its proxy
    // infrastructure already exists and is what the user runs daily — demote to that, not to a
    // third mode.
    stubStdin("\n")
    val fx = fixture(config(plugins = commandApiPlugin))

    assertEquals(DevMode.BLUE_GREEN, resolve(fx, configuredMode = DevMode.BLUE_GREEN))

    assertEquals(DevMode.BLUE_GREEN, fx.session.config.dev.mode)
    assertTrue(fx.terminal.raw.contains("Switch this session to blue-green? (Y/n)"))
  }

  // ── rejection sources at preflight time ─────────────────────────────

  @Test
  fun `metadata-side dependency triggers the flow`() {
    stubStdin("\n")
    val fx = fixture()
    fx.gradle.nextMetadata = fx.gradle.nextMetadata!!.copy(softdepend = listOf("ProtocolLib"))

    assertEquals(DevMode.RESTART, resolve(fx))
    assertEquals("protocollib", fx.session.selectionReport!!.rejections.single().ruleId)
  }

  @Test
  fun `broken build still scans config-side sources`() {
    // No metadata.json when the initial build fails — but a paperplane.yml dependency is already
    // categorical, so the demotion conversation happens now and fix recovery proceeds demoted.
    stubStdin("\n")
    val fx = fixture(config(plugins = commandApiPlugin))
    fx.gradle.nextMetadataResult = MetadataResult.TaskFailed

    assertEquals(DevMode.RESTART, resolve(fx))
    assertEquals(DevMode.RESTART, fx.session.config.dev.mode)
  }

  @Test
  fun `broken build with clean config passes through for in-session enforcement`() {
    val fx = fixture()
    fx.gradle.nextMetadataResult = MetadataResult.TaskFailed
    assertEquals(DevMode.HOT_RELOAD, resolve(fx))
    assertNull(fx.session.selectionReport)
  }
}
