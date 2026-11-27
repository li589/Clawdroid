plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.Properties

fun readLocalProperties(file: java.io.File): Map<String, String> {
    if (!file.isFile) {
        return emptyMap()
    }
    val properties = Properties()
    file.inputStream().use(properties::load)
    return properties.stringPropertyNames().associateWith { properties.getProperty(it) }
}

fun readLocalProperty(file: java.io.File, key: String): String? {
    return readLocalProperties(file)[key]?.trim()?.takeIf { it.isNotEmpty() }
}

fun validatedSharedSecret(value: String, source: String): String {
    val secret = value.trim()
    if (secret.isEmpty()) {
        error("$source ClawRuntime shared secret must not be empty.")
    }
    if (secret.startsWith("REPLACE_", ignoreCase = true) || secret.contains('<') || secret.contains('>')) {
        error("$source ClawRuntime shared secret is a placeholder; generate a local secret first.")
    }
    if (secret.length < 32 || secret.length % 2 != 0 || !secret.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
        error("$source ClawRuntime shared secret must contain at least 32 hexadecimal characters (64 recommended).")
    }
    return secret
}

fun ensureAndroidPrefsSpoolDir(): java.io.File {
    val prefsRoot = rootDir.resolve(".gradle-user/android-prefs")
    prefsRoot.resolve("metrics/spool").mkdirs()
    return prefsRoot
}

val androidPrefsRoot = ensureAndroidPrefsSpoolDir()
System.setProperty("ANDROID_PREFS_ROOT", androidPrefsRoot.absolutePath)

val clawRuntimeSharedSecret = run {
    val repoProperties = readLocalProperties(rootDir.parentFile.resolve("local.properties"))
    val appProperties = readLocalProperties(rootDir.resolve("local.properties"))
    // Keep this order aligned with sync-shared-secret.ps1. Conflicting values
    // are errors rather than silent overrides, so App and Runtime cannot drift.
    val candidates = buildList {
        providers.environmentVariable("CLAWDROID_RUNTIME_SHARED_SECRET").orNull?.let {
            add("CLAWDROID_RUNTIME_SHARED_SECRET" to it)
        }
        repoProperties["clawdroid.runtime.sharedSecret"]?.let {
            add("repo local.properties" to it)
        }
        appProperties["clawdroid.runtime.sharedSecret"]?.let {
            add("App local.properties" to it)
        }
        providers.gradleProperty("clawdroid.clawRuntimeSharedSecret").orNull?.let {
            add("Gradle property clawdroid.clawRuntimeSharedSecret" to it)
        }
    }
    if (candidates.isEmpty()) {
        error(
            "Missing ClawRuntime shared secret. Set CLAWDROID_RUNTIME_SHARED_SECRET or define clawdroid.runtime.sharedSecret in the repo-level local.properties. See README.md and local.properties.example."
        )
    }
    val validated = candidates.map { (source, value) -> validatedSharedSecret(value, source) }
    if (validated.distinct().size > 1) {
        error("Conflicting ClawRuntime shared secret values across: ${candidates.joinToString { it.first }}. Keep every configured source identical.")
    }
    validated.first()
}

// Release signing is fail-closed. Reads keystore path/password/alias from the
// repo-level local.properties; key password may explicitly match store password.
// See ClawApp/keystore/README.md for setup instructions.
val releaseKeystorePath = readLocalProperty(rootDir.parentFile.resolve("local.properties"), "clawdroid.keystore.path")
val releaseKeystorePassword = readLocalProperty(rootDir.parentFile.resolve("local.properties"), "clawdroid.keystore.password")
val releaseKeyAlias = readLocalProperty(rootDir.parentFile.resolve("local.properties"), "clawdroid.keystore.alias")
val releaseKeyPassword = readLocalProperty(rootDir.parentFile.resolve("local.properties"), "clawdroid.keystore.key.password")
    ?: releaseKeystorePassword
val hasReleaseSigningConfig = !releaseKeystorePath.isNullOrEmpty() &&
    !releaseKeystorePassword.isNullOrEmpty() &&
    !releaseKeyAlias.isNullOrEmpty()

fun validateReleaseSigning() {
    val missing = buildList {
        if (releaseKeystorePath.isNullOrEmpty()) add("clawdroid.keystore.path")
        if (releaseKeystorePassword.isNullOrEmpty()) add("clawdroid.keystore.password")
        if (releaseKeyAlias.isNullOrEmpty()) add("clawdroid.keystore.alias")
    }
    if (missing.isNotEmpty()) {
        error("Release builds require complete signing configuration in repo-level local.properties. Missing: ${missing.joinToString()}.")
    }
    val keystoreFile = rootDir.resolve(releaseKeystorePath!!).canonicalFile
    if (!keystoreFile.isFile) {
        error("Release signing keystore does not exist: $keystoreFile")
    }
}

android {
    namespace = "com.clawdroid.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.clawdroid.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "CLAW_RUNTIME_SHARED_SECRET", "\"$clawRuntimeSharedSecret\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        // Debug configuration remains usable without release credentials. The
        // release variant is rejected below unless this config is complete.
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = rootDir.resolve(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        aidl = true
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// AGP may create every variant while planning a debug task graph, so variant
// callbacks cannot enforce a debug-safe release gate. Gate only tasks that can
// produce release artifacts; lintRelease/testReleaseUnitTest must stay usable
// without release credentials. Retain a task-level guard for wrapper tasks.
val releaseProducingTaskNames = setOf(
    "assembleRelease",
    "bundleRelease",
    "packageRelease",
    "assemble",
    "build",
)
val requestedTaskNames = gradle.startParameter.taskNames.map { it.substringAfterLast(':') }
if (requestedTaskNames.any { it in releaseProducingTaskNames }) {
    validateReleaseSigning()
}
val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    doFirst { validateReleaseSigning() }
}
tasks.matching { it.name in releaseProducingTaskNames }.configureEach {
    dependsOn(verifyReleaseSigning)
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
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("commons-net:commons-net:3.11.1")
    implementation("com.github.mwiede:jsch:0.2.21")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    implementation("org.commonmark:commonmark:0.22.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.22.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer:0.26.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.26.0")
    implementation("androidx.webkit:webkit:1.11.0")
    // Coil for image/GIF loading
    implementation("io.coil-kt:coil-compose:2.6.0")
    // Media3 for video playback
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("org.json:json:20240303")
    testImplementation(project(":xposed-stubs"))
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    compileOnly(project(":xposed-stubs"))
}
