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

rootProject.name = "Ondura-Android"

// :core – Rust core (Ondura-Core via UniFFI) + Kotlin models, shared by all Ondura apps
// :ui   – Ondura design system for Compose (colors, type, shared widgets)
// :mail – Ondura Mail (with calendar, contacts and tasks to come, as on the desktop)
include(":core", ":ui", ":mail")
