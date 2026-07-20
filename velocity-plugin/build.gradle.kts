plugins { id("paperplane.minecraft-plugin") }

dependencies {
  compileOnly(libs.velocity.api)
  implementation(libs.gson)
}

tasks.shadowJar {
  archiveBaseName = "paperplane-velocity"
  archiveClassifier = ""
  archiveVersion = ""
}
