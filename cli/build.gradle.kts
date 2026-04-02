plugins {
    alias(libs.plugins.shadow)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.clikt)
    implementation(libs.kaml)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.gradle.tooling.api)
    implementation(libs.gson)
    implementation(libs.slf4j.nop)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Copy overlay jar as .bin so Shadow doesn't unpack it
val copyOverlay = tasks.register<Copy>("copyOverlayJar") {
    from(project(":overlay-plugin").tasks.named("shadowJar"))
    into(layout.buildDirectory.dir("overlay"))
    rename { "paperplane-overlay.bin" }
}

// Copy velocity plugin jar as .bin so Shadow doesn't unpack it
val copyVelocityPlugin = tasks.register<Copy>("copyVelocityPluginJar") {
    from(project(":velocity-plugin").tasks.named("shadowJar"))
    into(layout.buildDirectory.dir("velocity"))
    rename { "paperplane-velocity.bin" }
}

tasks.processResources {
    dependsOn(copyOverlay, copyVelocityPlugin)
    from(layout.buildDirectory.dir("overlay"))
    from(layout.buildDirectory.dir("velocity"))
}

tasks.shadowJar {
    archiveBaseName.set("paperplane-cli")
    archiveClassifier.set("")
    archiveVersion.set("")
    manifest {
        attributes("Main-Class" to "dev.paperplane.cli.MainKt")
    }
}
