import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release key lives outside the repo (~/.android-keys/bb8-revived.properties). Without it, release builds are unsigned.
val releaseKey = Properties().apply {
    val f = File(System.getProperty("user.home"), ".android-keys/bb8-revived.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.doktorigi.bb8"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.doktorigi.bb8"
        minSdk = 33
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1"
    }
    signingConfigs {
        if (!releaseKey.isEmpty) create("release") {
            storeFile = file(releaseKey.getProperty("storeFile"))
            storePassword = releaseKey.getProperty("storePassword")
            keyAlias = releaseKey.getProperty("keyAlias")
            keyPassword = releaseKey.getProperty("keyPassword")
        }
    }
    buildTypes {
        release { signingConfig = signingConfigs.findByName("release") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
