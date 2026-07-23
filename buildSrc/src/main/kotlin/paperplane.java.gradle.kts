plugins { java }

group = "dev.paperplane"

version = "0.1.0"

repositories { mavenCentral() }

java {
  toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

tasks.withType<Test> {
  useJUnitPlatform()
  // Disable ANSI colors in test output so visual regression assertions read as plain text.
  environment("NO_COLOR", "1")
  // Mirror of LaunchSpec.ADD_OPENS_JAVA_NET: dev servers always launch with this open (the
  // instant tier's new-class splice needs URLClassLoader.addURL), so tests exercising the splice
  // run under the same conditions.
  jvmArgs("--add-opens=java.base/java.net=ALL-UNNAMED")
}
