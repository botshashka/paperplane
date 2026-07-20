plugins { `kotlin-dsl` }

repositories {
  mavenCentral()
  gradlePluginPortal()
}

dependencies {
  // Versions must match gradle/libs.versions.toml.
  // buildSrc cannot use the version catalog accessor, so these are duplicated.
  implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.20")
  implementation("com.ncorti.ktfmt.gradle:plugin:0.26.0")
  implementation("dev.detekt:detekt-gradle-plugin:2.0.0-alpha.2")
  implementation("com.gradleup.shadow:shadow-gradle-plugin:9.4.1")
}
