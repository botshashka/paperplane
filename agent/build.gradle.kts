// Pure Java module — no Kotlin, no Shadow, no dependencies
// Produces a minimal JAR with the correct agent manifest

dependencies {
  testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.3")
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
