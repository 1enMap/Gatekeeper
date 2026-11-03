import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.gatekeeper"
    // tor-android (Tor mode) is built against Android 37.1 and requires consumers to compile
    // against at least that. Only the compile SDK changes; targetSdk stays 36.
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "app.gatekeeper"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    // Release key, read from keystore.properties (never committed). Without that file, release
    // builds are signed with the local debug key so they still install for testing.
    val releaseKey = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
        Properties().apply { file.inputStream().use { load(it) } }
    }

    signingConfigs {
        if (releaseKey != null) {
            create("release") {
                storeFile = rootProject.file(releaseKey.getProperty("storeFile"))
                storePassword = releaseKey.getProperty("storePassword")
                keyAlias = releaseKey.getProperty("keyAlias")
                keyPassword = releaseKey.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        // android.util.Log etc. return defaults in JVM tests instead of throwing.
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Screenshot tests only write images when asked: ./gradlew testDebugUnitTest -Pscreenshots
            it.systemProperty("roborazzi.test.record", project.hasProperty("screenshots").toString())
            it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
        }
    }

    // No dependency metadata blob: nothing about this build is sent anywhere.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    // Tor mode (experimental): Tor itself, run inside Gatekeeper as a client only.
    implementation(libs.tor.android)
    implementation(libs.jtorctl)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    // Screenshot tests of the UI on the JVM (test-only; nothing here ships in the app).
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
