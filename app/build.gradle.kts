plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    kotlin("plugin.serialization") version "2.4.20"
}

android {
    namespace = "com.sriox.vasateysec"
    compileSdk = 34
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.sriox.vasateysec"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags("")
            }
        }
        
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        
        // Disable ALL compression in APK
        packaging {
            jniLibs {
                useLegacyPackaging = true
            }
            resources {
                excludes += listOf(
                    "/META-INF/{AL2.0,LGPL2.1}",
                    "META-INF/INDEX.LIST",
                    "META-INF/DEPENDENCIES",
                    "META-INF/LICENSE",
                    "META-INF/NOTICE"
                )
            }
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("../vasateysec-release.jks")
            storePassword = "vasatey123"
            keyAlias = "vasateysec"
            keyPassword = "vasatey123"
        }
    }

    buildTypes {
        release {
            // COMPLETELY DISABLE ProGuard/R8 - No code obfuscation/optimization
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = true  // Keep debug info like debug build
            isJniDebuggable = true  // Keep JNI debug symbols
            
            signingConfig = signingConfigs.getByName("release")
            
            // Disable ALL compression and optimization
            isCrunchPngs = false  // Don't optimize PNGs
            // NOTE: isZipAlignEnabled removed — deprecated no-op (AGP always aligns).
            
            // Disable all optimizations
            isPseudoLocalesEnabled = false
            
            // Disable code optimizations - use no-op proguard
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            
            // Keep native debug symbols (don't strip)
            ndk {
                debugSymbolLevel = "FULL"  // Keep all debug symbols
            }
            
            // Disable ALL compression in APK - COMPLETE NO COMPRESSION
            packaging {
                resources {
                    excludes += "/META-INF/{AL2.0,LGPL2.1}"
                }
                jniLibs {
                    useLegacyPackaging = true  // No compression for native libraries
                    keepDebugSymbols += listOf("**/*.so")  // Keep all .so debug symbols
                }
                dex {
                    useLegacyPackaging = true  // No compression for DEX files
                }
            }
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
    
    lint {
        checkReleaseBuilds = false
        abortOnError = false
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
        viewBinding = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
    implementation("net.java.dev.jna:jna:5.14.0@aar")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    
    // Coroutines for async operations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // Lifecycle components
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    
    // Google Play Services for location and maps
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.android.gms:play-services-maps:18.2.0")
    implementation("com.google.android.gms:play-services-base:18.4.0")
    
    // Glide for image loading
    implementation("com.github.bumptech.glide:glide:4.16.0")
    
    // Camera2 API
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    
    // Kotlinx Serialization for local JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    
    // Security Crypto for encrypted SharedPreferences
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    
    // Navigation Component
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.7")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.7")
    
    // DrawerLayout
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    
    // OkHttp for local model downloading
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // WorkManager for background tasks
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // LocalBroadcastManager
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
