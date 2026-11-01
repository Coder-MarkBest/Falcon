plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.falcon.comfort.provider"
    compileSdk = 34
    defaultConfig { applicationId = "com.falcon.comfort.provider"; minSdk = 24; targetSdk = 34; versionCode = 1; versionName = "1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs {
        create("vendor") {
            storeFile = rootProject.file("falcon-cross-server/cross-server.keystore")
            storePassword = "falcondemo"; keyAlias = "cross-server"; keyPassword = "falcondemo"
        }
    }
    buildTypes { getByName("debug") { signingConfig = signingConfigs.getByName("vendor") } }
}
dependencies { implementation(project(":falcon-mcp-provider")); implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3") }
