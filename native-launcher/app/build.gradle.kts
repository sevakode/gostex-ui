import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
val releaseVersion = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
android {
    namespace = "tv.orbit.shield"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig {
        applicationId = "tv.orbit.shield"
        minSdk = 28
        targetSdk = 36
        versionCode = releaseVersion.getProperty("code").toInt()
        versionName = releaseVersion.getProperty("name")
    }
    buildFeatures { compose = true }
    signingConfigs {
        // CI supplies the same verified keystore as the existing SHIELD installation.
        // An explicit path avoids a runner's unrelated automatically generated debug key.
        getByName("debug") {
            providers.environmentVariable("GOSTEX_ANDROID_KEYSTORE").orNull?.let { storeFile = file(it) }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Keep this personal installation's existing signing identity and data.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation(project(":release-runtime"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui:1.12.1")
    implementation("androidx.compose.foundation:foundation:1.12.1")
    implementation("androidx.compose.animation:animation:1.12.1")
    implementation("androidx.tv:tv-material:1.1.0")
}
