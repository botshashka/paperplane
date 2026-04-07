plugins { alias(libs.plugins.shadow) }

repositories { maven("https://repo.papermc.io/repository/maven-public/") }

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

tasks.processResources { expand("version" to project.version) }

tasks.shadowJar {
  archiveBaseName.set("paperplane-companion")
  archiveClassifier.set("")
  archiveVersion.set("")
  // ASM is shaded as-is (no relocation). Paper's internal ASM is already
  // relocated to a different namespace, so no conflict.
}

// Make 'jar' point to shadowJar so downstream tasks get the fat jar
tasks.jar {
  archiveBaseName.set("paperplane-companion")
  enabled = false
}

tasks.named("build") { dependsOn(tasks.shadowJar) }
