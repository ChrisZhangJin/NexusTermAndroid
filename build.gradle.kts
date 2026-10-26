// Root build file. Plugins are declared here with `apply false` and
// actually applied in app/build.gradle.kts, per the standard Android Gradle
// Plugin + Kotlin multi-module convention.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    // cargo-ndk/rust-android bridge: cross-compiles nexus-core for each ABI
    // and copies the resulting .so into app/src/main/jniLibs/<abi>/.
    id("org.mozilla.rust-android-gradle.rust-android") version "0.9.6" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
