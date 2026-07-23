package dev.paperplane.companion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The capability matrix: ADDITIVE must require agent AND JBR AND the actual launch flag. Vendor
 * alone over-reporting was the shipped behavior this replaces — a JBR without the flag behaves like
 * a stock JVM.
 */
class RedefineCapabilitiesTest {

  private val inst = FakeInstrumentation()

  private fun detect(
      agent: Boolean,
      vendor: String = "Eclipse Adoptium",
      vmName: String = "OpenJDK 64-Bit Server VM",
      args: List<String> = emptyList(),
  ) =
      RedefineCapabilities.detect(
          instrumentation = if (agent) inst else null,
          vendor = vendor,
          vmName = vmName,
          inputArguments = args,
      )

  @Test
  fun `no agent means no capability at all`() {
    assertEquals(
        HostRedefineCapability.NONE,
        detect(
            agent = false,
            vendor = "JetBrains s.r.o.",
            args = listOf(RedefineCapabilities.ENHANCED_FLAG),
        ),
    )
  }

  @Test
  fun `agent on a stock JVM is body-only`() {
    assertEquals(HostRedefineCapability.BODY_ONLY, detect(agent = true))
  }

  @Test
  fun `JBR vendor without the launch flag is still body-only`() {
    assertEquals(
        HostRedefineCapability.BODY_ONLY,
        detect(agent = true, vendor = "JetBrains s.r.o."),
    )
  }

  @Test
  fun `JBR vendor with the launch flag is additive`() {
    assertEquals(
        HostRedefineCapability.ADDITIVE,
        detect(
            agent = true,
            vendor = "JetBrains s.r.o.",
            args = listOf("-Xmx2G", RedefineCapabilities.ENHANCED_FLAG),
        ),
    )
  }

  @Test
  fun `a JBR vm name qualifies like the vendor does`() {
    assertEquals(
        HostRedefineCapability.ADDITIVE,
        detect(
            agent = true,
            vmName = "OpenJDK 64-Bit Server VM (JBR)",
            args = listOf(RedefineCapabilities.ENHANCED_FLAG),
        ),
    )
  }

  @Test
  fun `the flag on a stock JVM does not enhance anything`() {
    assertEquals(
        HostRedefineCapability.BODY_ONLY,
        detect(agent = true, args = listOf(RedefineCapabilities.ENHANCED_FLAG)),
    )
  }
}
