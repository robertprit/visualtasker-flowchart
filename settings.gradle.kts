pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "visualtasker-flowchart"

include(
    ":flowchart-domain",
    ":flowchart-validation",
    ":flowchart-layout",
    ":flowchart-interaction",
    ":flowchart-serialization",
    ":flowchart-compose",
    ":flowchart-test-support",
    ":demo-app",
)
