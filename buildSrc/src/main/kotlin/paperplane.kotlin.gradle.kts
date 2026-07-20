import dev.detekt.gradle.extensions.DetektExtension

plugins {
  id("paperplane.java")
  id("org.jetbrains.kotlin.jvm")
  id("com.ncorti.ktfmt.gradle")
  id("dev.detekt")
}

configure<DetektExtension> {
  // Build on detekt's bundled defaults; the project's detekt.yml only overrides exceptions.
  // Without this flag the plugin runs with an empty ruleset and silently reports zero findings.
  buildUponDefaultConfig = true
  config.setFrom(rootProject.file("config/detekt/detekt.yml"))
  // Only `cli` is currently held to a strict gate. The other modules have pre-existing findings
  // against the bundled defaults that are tracked for follow-up cleanup, not blockers.
  // They still produce detekt reports — `ignoreFailures` only suppresses build failure.
  if (project.name != "cli") {
    ignoreFailures = true
  }
}
