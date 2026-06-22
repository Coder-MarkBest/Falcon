plugins {
    id("org.jetbrains.kotlin.jvm")
    id("java-gradle-plugin")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // AGP Variant API (MERGED_MANIFEST transform). compileOnly: provided by the consumer build.
    compileOnly("com.android.tools.build:gradle-api:8.2.0")
    testImplementation("junit:junit:4.13.2")
}

gradlePlugin {
    plugins {
        create("falcon") {
            id = "com.falcon.ipc.falcon-gradle"
            implementationClass = "com.falcon.ipc.gradle.FalconPlugin"
        }
    }
}
