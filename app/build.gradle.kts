import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(keystorePropertiesFile.inputStream())
    }
}

val adsPropertiesFile = rootProject.file("ads.properties")
val adsProperties = Properties().apply {
    if (adsPropertiesFile.exists()) {
        load(adsPropertiesFile.inputStream())
    }
}

fun adsField(key: String, fallback: String = ""): String =
    adsProperties.getProperty(key, fallback).ifBlank { fallback }

val storeFilePath = keystoreProperties.getProperty("storeFile")
val storePasswordValue = keystoreProperties.getProperty("storePassword")
val keyAliasValue = keystoreProperties.getProperty("keyAlias")
val keyPasswordValue = keystoreProperties.getProperty("keyPassword")

val hasReleaseSigning =
    !storeFilePath.isNullOrBlank() &&
    !storePasswordValue.isNullOrBlank() &&
    !keyAliasValue.isNullOrBlank() &&
    !keyPasswordValue.isNullOrBlank()

android {
    namespace = "com.example.youoffline"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.youoff"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(storeFilePath!!)
                storePassword = storePasswordValue
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "ENABLE_YANDEX_ADS", "false")
            buildConfigField("String", "AD_UNIT_AUDIO_BANNER", "\"${adsField("AD_UNIT_AUDIO_BANNER")}\"")
            buildConfigField("String", "AD_UNIT_VIDEO_BANNER", "\"${adsField("AD_UNIT_VIDEO_BANNER")}\"")
            buildConfigField("String", "AD_UNIT_INTERSTITIAL", "\"${adsField("AD_UNIT_INTERSTITIAL")}\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "ENABLE_YANDEX_ADS", "true")
            buildConfigField("String", "AD_UNIT_AUDIO_BANNER", "\"${adsField("AD_UNIT_AUDIO_BANNER")}\"")
            buildConfigField("String", "AD_UNIT_VIDEO_BANNER", "\"${adsField("AD_UNIT_VIDEO_BANNER")}\"")
            buildConfigField("String", "AD_UNIT_INTERSTITIAL", "\"${adsField("AD_UNIT_INTERSTITIAL")}\"")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.youtubedl.android.library)
    implementation(libs.youtubedl.android.ffmpeg)
    implementation(libs.yandex.mobile.ads)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}