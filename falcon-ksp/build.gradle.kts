plugins {
    id("org.jetbrains.kotlin.jvm")
    `maven-publish`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":falcon-annotations"))
    implementation("com.google.devtools.ksp:symbol-processing-api:1.9.22-1.0.17")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "falcon-ksp"
        }
    }
}
