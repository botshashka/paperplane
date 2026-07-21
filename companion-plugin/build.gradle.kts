plugins { id("paperplane.minecraft-plugin") }

dependencies {
  compileOnly(libs.paper.api)
  implementation(libs.gson)

  testImplementation(libs.paper.api)
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
  testImplementation(libs.mockbukkit)
  // Tests synthesize bytecode fixtures with ASM; the plugin itself no longer ships it — the
  // instant classifier (the only production ASM user) lives CLI-side now.
  testImplementation(libs.asm)
  testImplementation(libs.asm.util)
}

tasks.shadowJar {
  archiveBaseName = "paperplane-companion"
  archiveClassifier = ""
  archiveVersion = ""
}
