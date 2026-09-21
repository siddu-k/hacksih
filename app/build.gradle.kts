plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.google.gms.google-services")
    kotlin("plugin.serialization") version "1.9.22"
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
        
        // Disable ALL compression in APK
        packaging {
            jniLibs {
                useLegacyPackaging = true
            }
            resources {
                excludes += listOf("/META-INF/{AL2.0,LGPL2.1}")
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
            isZipAlignEnabled = false  // Disable zip alignment
            
            // Disable all optimizations
            isPseudoLocalesEnabled = false
            
            // Disable code optimizations - use no-op proguard
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
    implementation("net.java.dev.jna:jna:5.14.0@aar")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    
    // Ktor HTTP client
    implementation("io.ktor:ktor-client-android:2.3.7")
    implementation("io.ktor:ktor-client-okhttp:2.3.7")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")

    // Supabase dependencies (latest stable versions)
    implementation(platform("io.github.jan-tennert.supabase:bom:2.5.4"))
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:gotrue-kt")
    implementation("io.github.jan-tennert.supabase:realtime-kt")
    implementation("io.github.jan-tennert.supabase:storage-kt")  // For session persistence
    implementation("io.ktor:ktor-client-android:2.3.12")
    
    // Coroutines for async operations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // Lifecycle components
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    
    // Firebase FCM for push notifications
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-messaging-ktx")
    implementation("com.google.firebase:firebase-analytics-ktx")
    
    // Google Play Services for location and maps
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.android.gms:play-services-maps:18.2.0")
    implementation("com.google.android.gms:play-services-base:18.4.0")
    
    // Glide for image loading
    implementation("com.github.bumptech.glide:glide:4.16.0")
    
    // Camera2 API
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    
    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    
    // Security Crypto for encrypted SharedPreferences
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    
    // Navigation Component
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.7")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.7")
    
    // DrawerLayout
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    
    // OkHttp for API calls
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // WorkManager for background tasks
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // QR Code Generation and Scanning
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    
    // ONNX Runtime for local AI (Phi-3)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-extensions-android:0.10.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
