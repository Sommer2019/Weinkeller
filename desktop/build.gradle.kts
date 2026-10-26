import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
}

// In der Pipeline: Versionsnummer wie beim APK (v1.0.<Build-Nr.>); MSI verlangt MAJOR.MINOR.BUILD
val appVersion = (System.getenv("VERSION_NAME")?.removePrefix("v") ?: "1.0.0")
    .split('.').mapNotNull { it.toIntOrNull() }.let { (it + listOf(0, 0, 0)).take(3) }
    .let { (maj, min, build) -> "${maj.coerceIn(1, 255)}.${min.coerceIn(0, 255)}.${build.coerceIn(0, 65535)}" }

// Fachlogik (Excel-Datei, Bestand, Auswertung …) und Teile der Oberfläche kommen unverändert aus der Android-App
val sharedSources = tasks.register<Sync>("sharedSources") {
    from("../app/src/main/java") {
        include("de/robin/wein/core/**", "de/robin/wein/ui/Report.kt", "de/robin/wein/ui/Theme.kt")
    }
    into(layout.buildDirectory.dir("shared"))
}

// Gegenstück zu Androids BuildConfig: Gemini-Schlüssel aus dem GitHub-Secret GEMINI_API_KEY
val buildConfig = tasks.register("buildConfig") {
    val key = System.getenv("GEMINI_API_KEY").orEmpty()
    val out = layout.buildDirectory.dir("buildConfig")
    inputs.property("key", key)
    inputs.property("version", appVersion)
    outputs.dir(out)
    doLast {
        val f = out.get().file("de/robin/wein/BuildConfig.kt").asFile
        f.parentFile.mkdirs()
        val esc = key.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
        f.writeText(
            "package de.robin.wein\n\nobject BuildConfig {\n" +
                "    const val GEMINI_API_KEY = \"$esc\"\n" +
                "    const val VERSION = \"$appVersion\"\n}\n"
        )
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    sourceSets.main {
        kotlin.srcDir(sharedSources)
        kotlin.srcDir(buildConfig)
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    // NAS / Samba – dieselbe Bibliothek wie auf Android
    implementation("com.hierynomus:smbj:0.13.0")
    // Auf Android eingebaut, am PC als Bibliothek
    implementation("org.json:json:20240303")
    // Windows-Datenschutz-API (DPAPI) für gespeicherte Passwörter
    implementation("net.java.dev.jna:jna-platform:5.15.0")
}

compose.desktop {
    application {
        mainClass = "de.robin.wein.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Weinkeller"
            packageVersion = appVersion
            description = "Weinbestand per Barcode pflegen"
            vendor = "Robin"
            // Alle Java-Module mitnehmen: etwas größer, aber SMB/Verschlüsselung finden sicher alles
            includeAllModules = true
            windows {
                iconFile.set(project.file("icons/weinkeller.ico"))
                menuGroup = "Weinkeller"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                // Fest, damit eine neue Version die alte ersetzt statt daneben installiert zu werden
                upgradeUuid = "6f0b4c1e-3a8d-4b52-9c47-2e5d8a1f7b30"
            }
            linux {
                iconFile.set(project.file("icons/weinkeller.png"))
            }
        }
        buildTypes.release.proguard {
            isEnabled.set(false)
        }
    }
}
