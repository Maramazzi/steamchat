plugins {
    id("com.android.application")
}

android {
    namespace = "com.steamchat.spike"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.steamchat.spike"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-spike"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // JavaSteam uses java.time internally; desugaring backports it below API 26.
        isCoreLibraryDesugaringEnabled = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")

    // Steam protocol client - JVM port of SteamKit2, runs directly on Android (no backend server needed)
    implementation("in.dragonbra:javasteam:1.8.0")

    // JavaSteam requires a full crypto provider; Android's built-in BC is stripped down
    implementation("org.bouncycastle:bcprov-jdk18on:1.85.2")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
