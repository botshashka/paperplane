plugins {
  alias(libs.plugins.shadow)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  implementation(libs.clikt)
  implementation(libs.kaml)
  implementation(libs.kotlinx.serialization.core)
  implementation(libs.gradle.tooling.api)
  implementation(libs.gson)
  implementation(libs.slf4j.nop)

  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
}

// Copy companion jar as .bin so Shadow doesn't unpack it
val copyCompanion =
    tasks.register<Copy>("copyCompanionJar") {
      from(project(":companion-plugin").tasks.named("shadowJar"))
      into(layout.buildDirectory.dir("companion"))
      rename { "paperplane-companion.bin" }
    }

// Copy velocity plugin jar as .bin so Shadow doesn't unpack it
val copyVelocityPlugin =
    tasks.register<Copy>("copyVelocityPluginJar") {
      from(project(":velocity-plugin").tasks.named("shadowJar"))
      into(layout.buildDirectory.dir("velocity"))
      rename { "paperplane-velocity.bin" }
    }

// Copy agent jar as .bin for HMR instrumentation support
val copyAgent =
    tasks.register<Copy>("copyAgentJar") {
      from(project(":agent").tasks.named("jar"))
      into(layout.buildDirectory.dir("agent"))
      rename { "paperplane-agent.bin" }
    }

tasks.processResources {
  dependsOn(copyCompanion, copyVelocityPlugin, copyAgent)
  from(layout.buildDirectory.dir("companion"))
  from(layout.buildDirectory.dir("velocity"))
  from(layout.buildDirectory.dir("agent"))
}

tasks.shadowJar {
  archiveBaseName.set("paperplane-cli")
  archiveClassifier.set("")
  archiveVersion.set("")
  manifest { attributes("Main-Class" to "dev.paperplane.cli.MainKt") }
}
