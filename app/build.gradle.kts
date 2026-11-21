plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kooyu.neonknobs"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.kooyu.neonknobs"
        minSdk = 23 // API MIDI Android
        targetSdk = 34
        // numéro de run CI = versionCode croissant, chaque APK s'installe par-dessus le précédent
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toInt() ?: 1
        versionName = System.getenv("APP_VERSION") ?: "dev"
    }
    signingConfigs {
        // shortcut: clé debug publique versionnée, signature stable pour partager l'APK en direct ; passer à une vraie clé (secrets GitHub) avant le Play Store
        getByName("debug") { storeFile = file("debug.keystore") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
