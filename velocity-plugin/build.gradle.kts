plugins {
    alias(libs.plugins.shadow)
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly(libs.velocity.api)
    annotationProcessor(libs.velocity.api)
}

tasks.shadowJar {
    archiveBaseName.set("paperplane-velocity")
    archiveClassifier.set("")
    archiveVersion.set("")
}

tasks.jar {
    archiveBaseName.set("paperplane-velocity")
    enabled = false
}

tasks.named("build") {
    dependsOn(tasks.shadowJar)
}
