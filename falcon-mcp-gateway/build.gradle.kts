plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.falcon.mcp.gateway"
    compileSdk = 34
    defaultConfig {
        minSdk = 24
        applicationId = "com.falcon.mcp.gateway"
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }

    signingConfigs {
        create("demo") {
            storeFile = rootProject.file("falcon-cross-client/cross-client.keystore")
            storePassword = "falcondemo"
            keyAlias = "cross-client"
            keyPassword = "falcondemo"
        }
    }
    buildTypes { getByName("debug") { signingConfig = signingConfigs.getByName("demo") } }
}
dependencies {
    implementation(project(":falcon-mcp-adapters"))
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    testImplementation(project(":falcon-agent-runtime"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
