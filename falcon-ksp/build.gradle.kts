plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":falcon-annotations"))
    implementation("com.google.devtools.ksp:symbol-processing-api:1.9.22-1.0.17")
}
