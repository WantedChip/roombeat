plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.roombeat.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.roombeat.app"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86_64"))
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20", "-fexceptions", "-frtti", "-Wall", "-Wextra")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
                )
            }
        }
    }

    ndkVersion = "30.0.15729638"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    splits {
        abi {
            // ABI splits are used for APK generation; AAB handles splitting dynamically in Google Play.
            // In AGP 8.9.0, enabling splits during bundle tasks causes PerModuleBundleTask to fail.
            val isBuildingBundle = gradle.startParameter.taskNames.any {
                it.contains("bundle", ignoreCase = true)
            }
            isEnable = !isBuildingBundle
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("ROOMBEAT_RELEASE_KEYSTORE_PATH")
                ?: (project.findProperty("ROOMBEAT_RELEASE_KEYSTORE_PATH") as? String)
            if (keystorePath != null && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ROOMBEAT_RELEASE_KEYSTORE_PASSWORD")
                    ?: (project.findProperty("ROOMBEAT_RELEASE_KEYSTORE_PASSWORD") as? String)
                keyAlias = System.getenv("ROOMBEAT_RELEASE_KEY_ALIAS")
                    ?: (project.findProperty("ROOMBEAT_RELEASE_KEY_ALIAS") as? String)
                keyPassword = System.getenv("ROOMBEAT_RELEASE_KEY_PASSWORD")
                    ?: (project.findProperty("ROOMBEAT_RELEASE_KEY_PASSWORD") as? String)
            } else {
                // Fallback to debug keystore for local developer release builds and CI verification
                initWith(getByName("debug"))
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        prefab = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)

    implementation(libs.oboe)

    // Spotify App Remote SDK & JSON Deserializer
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))
    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
