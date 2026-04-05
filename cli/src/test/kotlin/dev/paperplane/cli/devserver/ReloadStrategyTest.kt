package dev.paperplane.cli.devserver

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReloadStrategyTest {

  @Test
  fun `HOTSWAP key matches protocol string`() {
    assertEquals("hotswap", ReloadStrategy.HOTSWAP.key)
  }

  @Test
  fun `DIRECTORY key matches protocol string`() {
    assertEquals("directory", ReloadStrategy.DIRECTORY.key)
  }

  @Test
  fun `JAR key matches protocol string`() {
    assertEquals("jar", ReloadStrategy.JAR.key)
  }

  @Test
  fun `enum has exactly three values`() {
    assertEquals(3, ReloadStrategy.entries.size)
  }

  @Test
  fun `all keys are unique`() {
    val keys = ReloadStrategy.entries.map { it.key }
    assertEquals(keys.size, keys.distinct().size)
  }
}
