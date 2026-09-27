plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "de.robin.wein"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.robin.wein"
        minSdk = 26
        targetSdk = 35
        // In der Pipeline: fortlaufende Build-Nummer, damit Android Updates annimmt
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = "1.0"
        // Gemini-Schlüssel aus dem GitHub-Secret GEMINI_API_KEY; leer = Nutzer trägt eigenen in der App ein
        buildConfigField("String", "GEMINI_API_KEY", "\"${System.getenv("GEMINI_API_KEY").orEmpty()}\"")
    }

    signingConfigs {
        // Fester Schlüssel aus Umgebungsvariablen (GitHub-Secrets); fehlt er, wird mit dem Debug-Schlüssel signiert
        create("release") {
            val store = System.getenv("SIGNING_STORE_FILE")
            if (!store.isNullOrBlank()) {
                storeFile = file(store)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Eigener Schlüssel, wenn vorhanden – sonst Debug-Key, damit das APK trotzdem installierbar ist
            signingConfig = if (System.getenv("SIGNING_STORE_FILE").isNullOrBlank()) signingConfigs.getByName("debug")
            else signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            // BouncyCastle (für SMB) bringt doppelte Metadaten mit
            excludes += listOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/versions/**/module-info.class",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
            )
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    // Barcode-Scan (läuft offline auf dem Gerät)
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("androidx.camera:camera-mlkit-vision:1.4.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    // Etikett lesen (Texterkennung, im APK enthalten – läuft offline)
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // NAS / Samba
    implementation("com.hierynomus:smbj:0.13.0")
}
