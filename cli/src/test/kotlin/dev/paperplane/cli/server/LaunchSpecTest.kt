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
}
