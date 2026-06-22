plugins {
    id("com.android.library") version "8.2.0" apply false
    id("com.android.application") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("com.google.devtools.ksp") version "1.9.22-1.0.17" apply false
}

// Coordinates for publishing. On JitPack the group is rewritten to
// com.github.<user>.<repo>; this default keeps `publishToMavenLocal` usable too.
allprojects {
    group = "com.github.Coder-MarkBest.Falcon"
    version = "1.0.0"
}
