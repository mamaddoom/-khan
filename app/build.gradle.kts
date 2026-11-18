plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Firebase sync is optional: it activates only when app/google-services.json exists.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

val appVersionName = "1.9"

// installable files are named MozKhan-<version>-debug.apk / MozKhan-<version>-release.apk
base { archivesName.set("MozKhan-$appVersionName") }

android {
    namespace = "com.mozkhan.shop"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mozkhan.shop"
        minSdk = 26
        targetSdk = 35
        // minutes since the epoch: every build (local or CI) is newer than the previous one, so APKs always update in place
        versionCode = (System.currentTimeMillis() / 60000L).toInt()
        versionName = appVersionName
        // Only ship modern ABIs -> smaller APK
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // CI points MOZKHAN_KEYSTORE at the shared signing key (GitHub secret) so every build carries the same certificate
    signingConfigs {
        val ks = System.getenv("MOZKHAN_KEYSTORE")
        if (!ks.isNullOrEmpty() && file(ks).exists()) {
            getByName("debug") {
                storeFile = file(ks); storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
    // exported Room schemas double as assets of the migration test
    // (unit tests read the DEBUG variant's merged assets; the few KB of JSON never reach the release APK)
    sourceSets.getByName("debug").assets.srcDir("$projectDir/schemas")
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    // the model is memory-mapped by MediaPipe: it must stay uncompressed inside the APK
    androidResources { noCompress += "tflite" }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    implementation(libs.guava)
    implementation(libs.mediapipe.vision)
    implementation(libs.mlkit.barcode)
    implementation(libs.mlkit.text)
    implementation(libs.mlkit.labeling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    // same gRPC version Firestore brings along: the in-app DNS plugs into its name resolution (net/InAppDns.kt)
    implementation("io.grpc:grpc-api:1.62.2")
    // offline Persian speech recognition (voice assistant); the model is downloaded at runtime
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play)
    implementation(libs.googleid)
}
