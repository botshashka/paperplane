pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "paperplane"

include("cli", "gradle-plugin", "companion-plugin", "velocity-plugin", "agent")
