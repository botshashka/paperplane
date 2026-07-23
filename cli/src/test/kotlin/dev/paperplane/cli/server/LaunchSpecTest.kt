package dev.paperplane.cli.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LaunchSpecTest {

  @Test
  fun `build appends the java-net add-opens on every spec`() {
    val spec = LaunchSpec.build("java", isJbr = false, baseJvmArgs = listOf("-Xmx2G"))
    assertEquals(listOf("-Xmx2G", LaunchSpec.ADD_OPENS_JAVA_NET), spec.jvmArgs)
  }

  @Test
  fun `build appends the enhanced-redefinition flag only on JBR`() {
    val jbr = LaunchSpec.build("/opt/jbr/bin/java", isJbr = true, baseJvmArgs = listOf("-Xmx2G"))
    assertTrue(jbr.jvmArgs.contains(LaunchSpec.ENHANCED_REDEFINITION))
    assertEquals(
        listOf("-Xmx2G", LaunchSpec.ADD_OPENS_JAVA_NET, LaunchSpec.ENHANCED_REDEFINITION),
        jbr.jvmArgs,
    )

    val stock = LaunchSpec.build("java", isJbr = false, baseJvmArgs = listOf("-Xmx2G"))
    assertFalse(stock.jvmArgs.contains(LaunchSpec.ENHANCED_REDEFINITION))
  }

  @Test
  fun `build preserves the configured base args in order, first`() {
    val spec =
        LaunchSpec.build("java", isJbr = false, baseJvmArgs = listOf("-Xmx4G", "-Xms1G", "-ea"))
    assertEquals(listOf("-Xmx4G", "-Xms1G", "-ea"), spec.jvmArgs.take(3))
  }

  @Test
  fun `build attaches the agent and records no package filter by default`() {
    val spec = LaunchSpec.build("java", isJbr = false, baseJvmArgs = emptyList())
    assertTrue(spec.attachAgent, "production launches always attach the agent")
    assertTrue(spec.recordedPackages.isEmpty())
  }

  @Test
  fun `recordedPackagesFor widens a deep main-class package to its parent`() {
    // com.acme.plugin.Main → com.acme, so helpers in sibling packages (com.acme.util.Helper)
    // still get load records.
    assertEquals(listOf("com.acme"), LaunchSpec.recordedPackagesFor("com.acme.plugin.Main"))
  }

  @Test
  fun `recordedPackagesFor keeps a two-segment package as-is`() {
    // Widening com.acme to com would re-admit half the runtime and give back the saving.
    assertEquals(listOf("com.acme"), LaunchSpec.recordedPackagesFor("com.acme.Main"))
  }

  @Test
  fun `recordedPackagesFor keeps a one-segment package as-is`() {
    assertEquals(listOf("acme"), LaunchSpec.recordedPackagesFor("acme.Main"))
  }

  @Test
  fun `recordedPackagesFor on a default-package main records everything`() {
    assertEquals(emptyList<String>(), LaunchSpec.recordedPackagesFor("Main"))
  }
}
