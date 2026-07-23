package dev.paperplane.companion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Capability is agent-present/absent, nothing else: body-only is the ceiling by design (ADR 0005),
 * so no property of the JVM — vendor, vm name, launch flags — may raise it.
 */
class RedefineCapabilitiesTest {

  @Test
  fun `no agent means no capability at all`() {
    assertEquals(HostRedefineCapability.NONE, RedefineCapabilities.detect(instrumentation = null))
  }

  @Test
  fun `an agent means body-only - the ceiling by design`() {
    assertEquals(
        HostRedefineCapability.BODY_ONLY,
        RedefineCapabilities.detect(instrumentation = FakeInstrumentation()),
    )
  }
}
