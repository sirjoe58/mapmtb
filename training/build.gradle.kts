plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// SirJoe Training: app separata da SirJoe MTB (si installa accanto, non la sostituisce)
android {
    namespace = "it.sirjoe.training"
    compileSdk = 35

    defaultConfig {
        applicationId = "it.sirjoe.training"
        minSdk = 23
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
    }

    // stessa chiave di SirJoe MTB: ogni nuova versione si installa sopra la precedente
    signingConfigs {
        create("sirjoe") {
            storeFile = rootProject.file("keystore/sirjoe.jks")
            storePassword = "sirjoe-mtb"
            keyAlias = "sirjoe"
            keyPassword = "sirjoe-mtb"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("sirjoe")
        }
        release {
            signingConfig = signingConfigs.getByName("sirjoe")
            isMinifyEnabled = false
        }
    }

    compileOptions {
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
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // lettore QR per il collegamento col PC (versione compatibile con Android 6)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") { isTransitive = false }
    implementation("com.google.zxing:core:3.3.0")
    // ANT+ (Garmin/Dynastream): fascia cardio e sensore di cadenza ANT+
    implementation(files("libs/antpluginlib_3-9-0.aar"))

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
