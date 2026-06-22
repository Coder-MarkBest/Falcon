plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.falcon.cross.server"
    compileSdk = 34

    // ── Demo signing: independent keystore simulating a real vendor ──────
    // In production, each APK is signed by its owning organization.
    // The SHA-256 fingerprint of this certificate is whitelisted by the
    // client's FalconConfig.security.trustedSignatures.
    signingConfigs {
        create("cross") {
            storeFile = file("cross-server.keystore")
            storePassword = "falcondemo"
            keyAlias = "cross-server"
            keyPassword = "falcondemo"
        }
    }

    defaultConfig {
        applicationId = "com.falcon.cross.server"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures { aidl = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

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

    // Device-free round-trip coverage of the generated Proxy/Dispatcher for the
    // cross-app ICrossService contract (Robolectric supplies a JVM Android context).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
