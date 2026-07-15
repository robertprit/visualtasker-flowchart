plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.plugin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

group = "de.visualtasker.flowchart"
version = "0.1.0-SNAPSHOT"

tasks.register<Exec>("dependencyBoundaryCheck") {
    commandLine("bash", "scripts/check-boundaries.sh")
}

tasks.register<Exec>("licenseAudit") {
    commandLine("bash", "scripts/check-licenses.sh")
}

tasks.register<Exec>("formatCheck") {
    commandLine("bash", "scripts/check-format.sh")
}

tasks.register("staticAnalysis") {
    dependsOn("dependencyBoundaryCheck", "formatCheck")
}

tasks.register("check") {
    dependsOn(
        "dependencyBoundaryCheck", "licenseAudit", "formatCheck",
        ":flowchart-domain:check", ":flowchart-validation:check", ":flowchart-layout:check",
        ":flowchart-interaction:check", ":flowchart-serialization:check",
        ":flowchart-compose:check", ":flowchart-test-support:check",
    )
}
