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
}
