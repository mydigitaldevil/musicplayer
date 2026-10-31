plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Release AdMob IDs come from ~/.gradle/gradle.properties or CI env, never from VCS.
// Debug builds always use Google's official test IDs so development traffic can
// never generate invalid impressions on the production account.
// An empty AdMob ID makes the SDK throw at app start, so a release build without them stops here
// with a clear message instead. Debug builds never read these.
val buildingRelease = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
fun adProp(name: String): String {
    val value = (findProperty(name) as String?) ?: System.getenv(name) ?: ""
    if (buildingRelease && value.isBlank()) {
        throw GradleException("Release build needs $name. Add it to ~/.gradle/gradle.properties (see README, Release configuration).")
    }
    return value
}

object TestAds {
    const val APP_ID = "ca-app-pub-3940256099942544~3347511713"
    const val BANNER_COLLAPSIBLE = "ca-app-pub-3940256099942544/2014213617"
    const val INTERSTITIAL = "ca-app-pub-3940256099942544/1033173712"
    const val REWARDED = "ca-app-pub-3940256099942544/5224354917"
    const val NATIVE = "ca-app-pub-3940256099942544/2247696110"
    const val APP_OPEN = "ca-app-pub-3940256099942544/9257395921"
}

android {
    namespace = "com.mydigitaldevil.musicplayer"
    compileSdk = 36
    // NDK and CMake: AGP's defaults (installed on first sync), so no exact version has to exist locally.

    defaultConfig {
        applicationId = "com.mydigitaldevil.musicplayer"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O3", "-ffast-math")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["admobAppId"] = TestAds.APP_ID
            buildConfigField("String", "AD_BANNER", "\"${TestAds.BANNER_COLLAPSIBLE}\"")
            buildConfigField("String", "AD_INTERSTITIAL", "\"${TestAds.INTERSTITIAL}\"")
            buildConfigField("String", "AD_REWARDED", "\"${TestAds.REWARDED}\"")
            buildConfigField("String", "AD_NATIVE", "\"${TestAds.NATIVE}\"")
            buildConfigField("String", "AD_APP_OPEN", "\"${TestAds.APP_OPEN}\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            manifestPlaceholders["admobAppId"] = adProp("ADMOB_APP_ID")
            buildConfigField("String", "AD_BANNER", "\"${adProp("ADMOB_BANNER_ID")}\"")
            buildConfigField("String", "AD_INTERSTITIAL", "\"${adProp("ADMOB_INTERSTITIAL_ID")}\"")
            buildConfigField("String", "AD_REWARDED", "\"${adProp("ADMOB_REWARDED_ID")}\"")
            buildConfigField("String", "AD_NATIVE", "\"${adProp("ADMOB_NATIVE_ID")}\"")
            buildConfigField("String", "AD_APP_OPEN", "\"${adProp("ADMOB_APP_OPEN_ID")}\"")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        prefab = true // Oboe is consumed as a Prefab package
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.service)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)
    implementation(libs.media3.transformer)
    // Software audio decoders for codecs the device lacks (AC3/E-AC3/DTS/TrueHD/ALAC/…).
    implementation(libs.media3.ffmpeg.decoder)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.play.services.ads)
    implementation(libs.ump)

    implementation(libs.oboe)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
