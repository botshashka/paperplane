plugins { id("paperplane.minecraft-plugin") }

dependencies {
  compileOnly(libs.paper.api)
  implementation(libs.gson)

  testImplementation(libs.paper.api)
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
  testImplementation(libs.mockbukkit)
  // Tests synthesize bytecode fixtures with ASM; production ships none (the classifier that
  // consumes bytecode lives CLI-side).
  testImplementation(libs.asm)
  testImplementation(libs.asm.util)
}

tasks.shadowJar {
  archiveBaseName = "paperplane-companion"
  archiveClassifier = ""
  archiveVersion = ""
}
