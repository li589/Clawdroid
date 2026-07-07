plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.Properties

fun readLocalProperty(file: java.io.File, key: String): String? {
    if (!file.exists()) {
        return null
    }
    val properties = Properties()
    file.inputStream().use(properties::load)
    return properties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
}

fun ensureAndroidPrefsSpoolDir(): java.io.File {
    val prefsRoot = rootDir.resolve(".gradle-user/android-prefs")
    prefsRoot.resolve("metrics/spool").mkdirs()
    return prefsRoot
}

val androidPrefsRoot = ensureAndroidPrefsSpoolDir()
System.setProperty("ANDROID_PREFS_ROOT", androidPrefsRoot.absolutePath)

val clawRuntimeSharedSecret = run {
    val envSecret = providers.environmentVariable("CLAWDROID_RUNTIME_SHARED_SECRET").orNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    val repoLocalSecret = readLocalProperty(rootDir.parentFile.resolve("local.properties"), "clawdroid.runtime.sharedSecret")
    val appLocalSecret = readLocalProperty(rootDir.resolve("local.properties"), "clawdroid.runtime.sharedSecret")
    val gradlePropertySecret = providers.gradleProperty("clawdroid.clawRuntimeSharedSecret").orNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    envSecret
        ?: repoLocalSecret
        ?: appLocalSecret
        ?: gradlePropertySecret
        ?: error(
            "Missing ClawRuntime shared secret. Set CLAWDROID_RUNTIME_SHARED_SECRET or define clawdroid.runtime.sharedSecret in the repo-level local.properties. See README.md and local.properties.example."
        )
}

android {
    namespace = "com.clawdroid.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.clawdroid.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "CLAW_RUNTIME_SHARED_SECRET", "\"$clawRuntimeSharedSecret\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    compileOnly(project(":xposed-stubs"))
}
