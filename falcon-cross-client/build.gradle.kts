plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.falcon.cross.client"
    compileSdk = 34

    // ── Demo signing: independent keystore simulating a real vendor ────
    // In production, each APK is signed by its owning organization.
    // The SHA-256 fingerprint of this certificate is whitelisted by the
    // server's FalconConfig.security.trustedSignatures.
    signingConfigs {
        create("cross") {
            storeFile = file("cross-client.keystore")
            storePassword = "falcondemo"
            keyAlias = "cross-client"
            keyPassword = "falcondemo"
        }
    }

    defaultConfig {
        applicationId = "com.falcon.cross.client"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures { aidl = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("cross")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("cross")
        }
    }
}

dependencies {
    implementation(project(":falcon-core"))
    ksp(project(":falcon-ksp"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")

    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
