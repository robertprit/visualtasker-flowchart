plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(17); explicitApi() }
dependencies {
    api(project(":flowchart-domain"))
}
