plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "it.sirjoe.mtbcomputer"
    compileSdk = 35

    defaultConfig {
        applicationId = "it.sirjoe.mtbcomputer"
        minSdk = 23
        targetSdk = 35
        versionCode = 18
        versionName = "2.0.2"
    }

    // Chiave di firma fissa: ogni nuova versione si installa sopra la precedente
    // senza disinstallare (tracce, mappe e impostazioni restano).
    signingConfigs {
        // La chiave di firma NON sta nel codice: arriva dai Secrets di GitHub
        // (MTB_KEYSTORE_B64 / MTB_KEYSTORE_PASSWORD) tramite variabili d'ambiente.
        create("sirjoe") {
            val ks = System.getenv("SIRJOE_KEYSTORE_FILE")
            val pw = System.getenv("SIRJOE_KEYSTORE_PASSWORD")
            if (ks != null && pw != null) {
                storeFile = file(ks)
                storePassword = pw
                keyAlias = "sirjoe"
                keyPassword = pw
            } else {
                // Senza Secrets (prove in locale) si usa la chiave di debug standard.
                initWith(getByName("debug"))
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("sirjoe")
        }
        release {
            signingConfig = signingConfigs.getByName("sirjoe")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        // date e orari (java.time) anche su Android 6 e 7
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        // controlla che non si usino funzioni non disponibili sui telefoni più vecchi
        checkOnly += setOf("NewApi", "InlinedApi")
        abortOnError = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("org.osmdroid:osmdroid-mapsforge:6.1.20")
    // lettore QR per SirJoe Link (versione compatibile con Android 6)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") { isTransitive = false }
    implementation("com.google.zxing:core:3.3.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    testImplementation("junit:junit:4.13.2")
    // org.json vero nei test (nel telefono è già incluso in Android)
    testImplementation("org.json:json:20231013")
}
