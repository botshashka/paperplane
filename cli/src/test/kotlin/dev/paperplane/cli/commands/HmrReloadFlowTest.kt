package dev.paperplane.cli.commands

import dev.paperplane.cli.gradle.ClassChanges
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Sanity checks for [ClassChanges.noNewOrRemovedClasses] — `HotReloadMode.triggerReload` reads this
 * flag to decide whether HOTSWAP is eligible. End-to-end coverage of the LoadRequest write path
 * lives in `HotReloadModeReloadRequestTest`.
 */
class HmrReloadFlowTest {

  @Test
  fun `ClassChanges with only modified has noNewOrRemovedClasses true`() {
    val changes =
        ClassChanges(
            modified = listOf("com.example.MyPlugin", "com.example.Helper"),
            added = emptyList(),
            removed = emptyList(),
        )
    assertTrue(changes.noNewOrRemovedClasses)
  }

  @Test
  fun `ClassChanges with added classes has noNewOrRemovedClasses false`() {
    val changes =
        ClassChanges(
            modified = listOf("com.example.MyPlugin"),
            added = listOf("com.example.NewClass"),
            removed = emptyList(),
        )
    assertFalse(changes.noNewOrRemovedClasses)
  }

  @Test
  fun `ClassChanges with removed classes has noNewOrRemovedClasses false`() {
    val changes =
        ClassChanges(
            modified = emptyList(),
            added = emptyList(),
            removed = listOf("com.example.OldClass"),
        )
    assertFalse(changes.noNewOrRemovedClasses)
  }

  @Test
  fun `ClassChanges with all empty lists has noNewOrRemovedClasses true`() {
    val changes = ClassChanges(modified = emptyList(), added = emptyList(), removed = emptyList())
    assertTrue(changes.noNewOrRemovedClasses)
  }
}
