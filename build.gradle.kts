plugins {
    alias(libs.plugins.kotlin.jvm) apply false
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

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
