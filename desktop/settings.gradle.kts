// Eigenständiger Build für die Windows-/Desktop-Version (unabhängig vom Android-Build)
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}
rootProject.name = "Weinkeller-Desktop"
