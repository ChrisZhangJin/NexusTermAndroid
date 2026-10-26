plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.mozilla.rust-android-gradle.rust-android")
}

android {
    namespace = "com.nexusterm.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nexusterm.android"
        minSdk = 26 // WebView byte[]/evaluateJavascript needs API 19+; 26 picked for Compose + modern TLS defaults
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-slice"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // The UniFFI-generated Kotlin bindings (uniffi/nexus_core/nexus_core.kt)
    // land under build/generated/uniffi/ — see the `generateUniffiBindings`
    // task below. Registering it as a source dir makes Android Studio index
    // it like hand-written code.
    sourceSets {
        getByName("main") {
            kotlin.srcDir("${layout.buildDirectory.get()}/generated/uniffi")
            // org.mozilla.rust-android-gradle.rust-android's cargoBuild task already
            // registers build/rustJniLibs/android/<abi>/ as a jniLibs source dir itself
            // — adding it again here caused a "duplicate resources" merge failure.
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Milestone 2: session list / add-edit / SFTP routes replace MainActivity's
    // manual two-state screen switch with a real nav graph.
    implementation("androidx.navigation:navigation-compose:2.8.0")

    // Session passwords / private-key passphrases are deliberately never sent
    // to nexus-core / SQLite (see nexus-core/src/store/mod.rs) — they live
    // here, in a per-app encrypted file keyed by session id.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Local side of the SFTP file browser uses Storage Access Framework
    // (ACTION_OPEN_DOCUMENT_TREE / DocumentFile) instead of java.io.File,
    // since API 30+ scoped storage blocks arbitrary filesystem root access.
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Generated bindings call into the JNA-less UniFFI runtime via JNI
    // directly on modern uniffi-kotlin (no extra JNA dependency needed for
    // 0.28+'s default "jna"-free Kotlin target as long as the loader can
    // find libnexus_core.so under jniLibs/<abi>/ — handled by the Android
    // NativeLibraryLoader at bindings-generation time automatically).
    implementation("net.java.dev.jna:jna:5.14.0@aar")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

// ─────────────────────────── nexus-core (Rust) ───────────────────────────
//
// org.mozilla.rust-android-gradle.rust-android drives `cargo ndk build`
// under the hood (via cargo-ndk, which must be installed separately:
// `cargo install cargo-ndk`). It cross-compiles nexus-core for each target
// in `targets` and copies the resulting shared library into
// app/src/main/jniLibs/<abi>/libnexus_core.so.
cargo {
    module = "../nexus-core"
    libname = "nexus_core"
    // Matches gradle.properties' nexusterm.rustTargets, kept as literal ABI
    // names here because the rust-android-gradle plugin's `targets` DSL
    // expects its own ABI spelling (arm64/x86_64), not Rust triples.
    targets = listOf("arm64", "x86_64")
    profile = "release"
    // Picked up by cargo-ndk so that `uniffi::setup_scaffolding!()` and the
    // callback-interface machinery link against the right NDK libc version.
    apiLevel = 26
}

// `cargoBuild` (registered by the plugin above) must run before Kotlin/Java
// compilation picks up jniLibs, and before the bindings are generated (the
// generator inspects the compiled .so's UniFFI metadata section).
tasks.whenTaskAdded {
    if (name == "javaPreCompileDebug" || name == "javaPreCompileRelease") {
        dependsOn("cargoBuild")
    }
}

// ─────────────────────── UniFFI Kotlin bindings ───────────────────────
//
// Modern UniFFI (0.28+, proc-macro export style used in nexus-core/src/lib.rs,
// no .udl file) generates bindings by inspecting the compiled cdylib's
// embedded metadata, via nexus-core's own `uniffi-bindgen` binary target
// (see nexus-core/uniffi-bindgen.rs):
//
//   cargo run --manifest-path ../nexus-core/Cargo.toml --bin uniffi-bindgen -- \
//       generate --library <path-to-host-or-target-.so> \
//       --language kotlin --out-dir <build/generated/uniffi>
//
// We generate once from the arm64 .so (any ABI's metadata is identical;
// only the machine code differs), after cargoBuild has produced it.
val generateUniffiBindings by tasks.registering(Exec::class) {
    dependsOn("cargoBuild")
    val outDir = layout.buildDirectory.dir("generated/uniffi").get().asFile
    // uniffi-bindgen's `--library` mode dlopens the given shared library in-process
    // to read its embedded metadata, so it MUST be a host-architecture build (glibc
    // x86_64), not one of the cross-compiled Android .so's cargo-ndk produced — those
    // can't be dlopen'd on this host. We build a throwaway host-target `cargo build`
    // (debug, fast) purely so uniffi-bindgen has something it can load; the metadata
    // is identical across all target triples, only the machine code differs.
    doFirst {
        outDir.mkdirs()
        exec {
            workingDir = file("../nexus-core")
            commandLine("cargo", "build", "--lib")
        }
    }
    val hostSoPath = file("../nexus-core/target/debug/libnexus_core.so")
    workingDir = file("../nexus-core")
    commandLine(
        "cargo", "run",
        "--bin", "uniffi-bindgen",
        "--",
        "generate",
        "--library",
        "--language", "kotlin",
        "--out-dir", outDir.absolutePath,
        hostSoPath.absolutePath
    )
}

tasks.whenTaskAdded {
    if (name.startsWith("compile") && name.contains("Kotlin")) {
        dependsOn(generateUniffiBindings)
    }
}
