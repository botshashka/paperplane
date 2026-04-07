package dev.paperplane.companion

import java.util.concurrent.atomic.AtomicInteger
import net.bytebuddy.agent.ByteBuddyAgent
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Behavioral test: after the transformer rewrites JavaPlugin.<init>()V, instantiating a subclass
 * via plain `new` (i.e. through a non-PluginClassLoader) must:
 * 1. Not throw (the early-return branch is taken instead of the original IllegalStateException)
 * 2. Run the subclass's field initializers (the entire reason this patch exists vs.
 *    Unsafe.allocateInstance, which would skip them)
 *
 * Only covers the early-return branch. The instanceof PluginClassLoader → initialize(this) branch
 * requires constructing a real PluginClassLoader, which is the reflection mess we're avoiding; that
 * branch is exercised by integration runs against a real Paper server.
 */
class JavaPluginBehaviorTest {

  class TestSubclass : JavaPlugin() {
    val counter = AtomicInteger(42)
    val map = HashMap<String, Int>().also { it["seeded"] = 1 }
  }

  @Test
  fun `subclass instantiates with field initializers after patch`() {
    val inst = ByteBuddyAgent.install()
    val transformer = JavaPluginTransformer()
    inst.addTransformer(transformer, true)
    try {
      inst.retransformClasses(JavaPlugin::class.java)
    } finally {
      inst.removeTransformer(transformer)
    }

    // The app classloader running this test is not a PluginClassLoader, so this instantiation
    // exercises the early-return branch the patch introduces. Without the patch this would throw
    // IllegalStateException; with Unsafe.allocateInstance the field inits would be skipped and
    // counter/map would be null.
    assertFalse(
        TestSubclass::class.java.classLoader.javaClass.name.contains("PluginClassLoader"),
        "Test precondition: TestSubclass must be loaded by a non-PluginClassLoader",
    )

    val instance = TestSubclass()

    assertNotNull(instance.counter, "AtomicInteger field initializer must have run")
    assertEquals(42, instance.counter.get())
    assertNotNull(instance.map, "HashMap field initializer must have run")
    assertEquals(1, instance.map["seeded"])
  }
}
