plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// opentakez.version is "X.Y.Z" or "X.Y.Z-rc.N" (gradle.properties, single source of truth).
// versionCode = (major*1_000_000 + minor*10_000 + patch*100 + (rc ? N : 99)) * 10 + abi, so an
// update from rc.2 to the final release always increases it.
val appVersion = providers.gradleProperty("opentakez.version").get()
val versionMatch = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-rc\.(\d+))?$""").matchEntire(appVersion)
    ?: error("opentakez.version must look like 1.2.3 or 1.2.3-rc.1, got '$appVersion'")
val (major, minor, patch) = versionMatch.destructured
val rc = versionMatch.groupValues[4].ifEmpty { null }?.toInt()
val baseVersionCode =
    (major.toInt() * 1_000_000 + minor.toInt() * 10_000 + patch.toInt() * 100 + (rc ?: 99)) * 10

kotlin {
    jvmToolchain(21)
}

android {
    namespace = "io.github.timhofmann27.opentakez"
    // Current AndroidX/Coil/OkHttp releases need compileSdk 37; targetSdk stays at 36 so the
    // newer runtime behaviour changes are adopted deliberately, after testing.
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.timhofmann27.opentakez"
        minSdk = 26
        targetSdk = 36
        versionCode = baseVersionCode
        versionName = appVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Local smoke test of the minified build (-Popentakez.debugSignRelease=true): signed
            // with the debug key so it installs on an emulator. Never used for published APKs.
            if (providers.gradleProperty("opentakez.debugSignRelease").isPresent) {
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // youtubedl-android needs its Python and ffmpeg binaries extracted to disk.
        jniLibs {
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    // One APK per ABI (Python and ffmpeg are bundled, so a universal APK would be ~190 MB).
    // Only for release builds: debug builds stay a single APK so installDebug is simple.
    splits {
        abi {
            isEnable = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }
}

// Distinct, increasing versionCodes per ABI so Obtainium/Android pick the right update.
val abiCodes = mapOf("universal" to 0, "armeabi-v7a" to 1, "arm64-v8a" to 2, "x86_64" to 3)
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters
                .firstOrNull { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }
                ?.identifier ?: "universal"
            output.versionCode.set(baseVersionCode + abiCodes.getValue(abi))
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.youtubedl.library)
    implementation(libs.youtubedl.ffmpeg)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
