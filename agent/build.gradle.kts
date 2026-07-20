// Pure Java module — no Kotlin, no Shadow.
// Produces a minimal JAR with the correct agent manifest.

plugins { id("paperplane.java") }

dependencies {
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.jar {
  archiveBaseName = "paperplane-agent"
  archiveClassifier = ""
  archiveVersion = ""
  manifest {
    attributes(
        "Premain-Class" to "dev.paperplane.agent.PaperPlaneAgent",
        "Can-Redefine-Classes" to "true",
        "Can-Retransform-Classes" to "true",
    )
  }
}
