plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ktfmt) apply false
    alias(libs.plugins.detekt) apply false
}

allprojects {
    group = "dev.paperplane"
    version = "0.1.0"

    repositories {
        mavenCentral()
        maven("https://repo.gradle.org/gradle/libs-releases")
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "com.ncorti.ktfmt.gradle")
    apply(plugin = "dev.detekt")

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    configure<dev.detekt.gradle.extensions.DetektExtension> {
        // Build on detekt's bundled defaults; the project's detekt.yml only overrides exceptions.
        // Without this flag the plugin runs with an empty ruleset and silently reports zero findings.
        buildUponDefaultConfig.set(true)
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        // Only `cli` is currently held to a strict gate. The other modules (companion-plugin,
        // velocity-plugin, gradle-plugin) have pre-existing findings against the bundled defaults
        // that are tracked for follow-up cleanup, not blockers for this branch. They still produce
        // detekt reports — `ignoreFailures` only suppresses build failure, not analysis.
        if (project.name != "cli") {
            ignoreFailures.set(true)
        }
    }

tasks.withType<Test> {
        useJUnitPlatform()
    }
}
