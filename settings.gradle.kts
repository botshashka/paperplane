pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "paperplane"

include("cli", "gradle-plugin", "companion-plugin", "velocity-plugin", "agent")
