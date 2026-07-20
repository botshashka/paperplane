plugins {
  id("paperplane.kotlin")
  id("com.gradleup.shadow")
}

repositories { maven("https://repo.papermc.io/repository/maven-public/") }

tasks.processResources { expand("version" to project.version) }

// Make shadowJar the primary artifact
tasks.jar { enabled = false }

tasks.named("build") { dependsOn(tasks.named("shadowJar")) }
