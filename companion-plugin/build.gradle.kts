plugins {
    alias(libs.plugins.shadow)
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly(libs.paper.api)
    implementation(libs.gson)
}

tasks.shadowJar {
    archiveBaseName.set("paperplane-companion")
    archiveClassifier.set("")
    archiveVersion.set("")
}

// Make 'jar' point to shadowJar so downstream tasks get the fat jar
tasks.jar {
    archiveBaseName.set("paperplane-companion")
    enabled = false
}

tasks.named("build") {
    dependsOn(tasks.shadowJar)
}
