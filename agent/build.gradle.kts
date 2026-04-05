// Pure Java module — no Kotlin, no Shadow, no dependencies
// Produces a minimal JAR with the correct agent manifest

dependencies {
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test { useJUnitPlatform() }

tasks.jar {
  archiveBaseName.set("paperplane-agent")
  archiveClassifier.set("")
  archiveVersion.set("")
  manifest {
    attributes(
        "Premain-Class" to "dev.paperplane.agent.PaperPlaneAgent",
        "Can-Redefine-Classes" to "true",
        "Can-Retransform-Classes" to "true",
    )
  }
}
