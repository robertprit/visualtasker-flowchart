plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(17); explicitApi() }
dependencies {
    api(project(":flowchart-domain"))
    api(project(":flowchart-layout"))
    api(project(":flowchart-serialization"))
    testImplementation(libs.junit)
}
