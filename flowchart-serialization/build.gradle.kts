plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
kotlin { jvmToolchain(17); explicitApi() }
dependencies {
    api(project(":flowchart-domain"))
    implementation(project(":flowchart-validation"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
