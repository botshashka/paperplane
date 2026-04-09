plugins {
  id("paperplane.kotlin")
  application
  id("com.gradleup.shadow")
  alias(libs.plugins.kotlin.serialization)
}

repositories { maven("https://repo.gradle.org/gradle/libs-releases") }

application {
  applicationName = "ppl"
  mainClass = "dev.paperplane.cli.PaperPlaneKt"
  applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

dependencies {
  implementation(libs.clikt)
  implementation(libs.kaml)
  implementation(libs.kotlinx.serialization.core)
  implementation(libs.gradle.tooling.api)
  implementation(libs.gson)
  implementation(libs.slf4j.nop)
  implementation(libs.jline.terminal)
  runtimeOnly(libs.jline.terminal.jni)

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
  archiveBaseName = "paperplane-cli"
  archiveClassifier = ""
  archiveVersion = ""
  manifest {
    attributes(
        "Main-Class" to "dev.paperplane.cli.PaperPlaneKt",
        "Implementation-Version" to project.version,
    )
  }
}

// The distribution ships the shadow fat jar only (not individual dependency jars).
// Override the startScripts classpath so the launcher only references the shadow jar.
tasks.startScripts { classpath = files(tasks.shadowJar.flatMap { it.archiveFile }) }

// Replace the default lib contents (all dependency jars) with just the shadow fat jar.
val distLib = copySpec {
  from(tasks.shadowJar)
  into("lib")
}

distributions {
  main {
    contents {
      // Remove the default CopySpec that includes runtime classpath jars
      with(distLib)
    }
  }
}

// After Gradle resolves the dist contents, strip out the default per-dependency jars.
// Only keep the shadow jar + bin scripts.
tasks.distZip {
  archiveBaseName = "ppl"
  // Exclude all lib/ entries that are NOT the shadow jar
  eachFile {
    if (relativePath.pathString.contains("/lib/") && !name.contains("paperplane-cli")) {
      exclude()
    }
  }
}

tasks.distTar {
  archiveBaseName = "ppl"
  eachFile {
    if (relativePath.pathString.contains("/lib/") && !name.contains("paperplane-cli")) {
      exclude()
    }
  }
}

// Disable shadowDist tasks — we use the standard distribution with shadow jar
tasks.named("shadowDistZip") { enabled = false }

tasks.named("shadowDistTar") { enabled = false }
