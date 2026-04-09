plugins { id("paperplane.minecraft-plugin") }

dependencies {
  compileOnly(libs.paper.api)
  implementation(libs.gson)
  implementation(libs.asm)

  testImplementation(libs.paper.api)
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
  testImplementation(libs.mockbukkit)
  testImplementation(libs.asm.util)
  testImplementation("net.bytebuddy:byte-buddy-agent:1.17.7")
}

tasks.test {
  jvmArgs("-Djdk.attach.allowAttachSelf=true")
  // JavaPluginRetransformTest mutates the live JavaPlugin class via Instrumentation,
  // which would break sibling tests (e.g. MockBukkit-based ones) in the same JVM.
  setForkEvery(1)
}

tasks.shadowJar {
  archiveBaseName = "paperplane-companion"
  archiveClassifier = ""
  archiveVersion = ""
  // ASM is shaded as-is (no relocation). Paper's internal ASM is already
  // relocated to a different namespace, so no conflict.
}
