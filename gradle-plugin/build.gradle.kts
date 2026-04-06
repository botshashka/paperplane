plugins {
  `java-gradle-plugin`
  `maven-publish`
}

dependencies {
  implementation(libs.gson)

  testImplementation(gradleTestKit())
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
}

gradlePlugin {
  plugins {
    create("paperplane") {
      id = "dev.paperplane"
      implementationClass = "dev.paperplane.gradle.PaperPlanePlugin"
      displayName = "PaperPlane"
      description = "Gradle plugin for PaperPlane dev tooling"
    }
  }
}

publishing { repositories { mavenLocal() } }
