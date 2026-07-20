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
}

tasks.shadowJar {
  archiveBaseName = "paperplane-companion"
  archiveClassifier = ""
  archiveVersion = ""
  // ASM is shaded as-is (no relocation) for ClassChangeDetector. Paper's internal ASM is already
  // relocated to a different namespace, so no conflict.
}
