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

    // 同样是为了 mockable android.jar：`android.util.Log` 的桩会抛
    // "Method not mocked"，而被测代码在走容错分支时会记一条 warn ——
    // 于是「存档损坏」这类用例每一条都挂在日志语句上，测不到真正想测的回退
    // 行为。打开这个开关让桩返回默认值（Log.w 返回 0）而不是抛。
    // 只作用于 JVM 单测；androidTest 跑在真设备上，用的是真 android.jar。
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // AGP auto-generates its debug keystore into the *container's* ~/.android/,
    // which is destroyed by every `docker compose up --force-recreate`. A new
    // key means a new signature, which means `adb install -r` fails with
    // INSTALL_FAILED_UPDATE_INCOMPATIBLE and the app has to be uninstalled —
    // taking the user's saved sessions and stored passwords with it. Pointing
    // debug builds at a keystore on the persistent toolchain mount makes them
    // reinstallable across container rebuilds. No-ops on a normal dev machine,
    // where neither env var is set.
    signingConfigs {
        getByName("debug") {
            val persistent = (
                System.getenv("NEXUSTERM_DEBUG_KEYSTORE")
                    ?: System.getenv("NEXUSTERM_ANDROID_TOOLCHAIN")?.let { "$it/debug.keystore" }
                )?.let(::file)
            if (persistent != null && persistent.exists()) {
                storeFile = persistent
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // nexus-core 的 panic hook（nexus-core/src/logging.rs）会在 panic 时把
    // backtrace 打进 logcat。但 AGP 的 stripDebugDebugSymbols 默认会把
    // jniLibs 下每个 .so 的 .symtab 一起剥掉 —— 实测打包进 APK 的
    // libnexus_core.so 里只剩 .dynsym，而 Rust 的内部函数几乎都不在 .dynsym
    // 里，于是 backtrace 退化成一串裸地址，等于没有。
    //
    // 配合 nexus-core/Cargo.toml 里的 `strip = "debuginfo"`（保符号表、去
    // DWARF），这里让 AGP 对这一个库手下留情，backtrace 才能带上函数名。
    // 代价：arm64 的 .so 从 ~5.1MB 涨到 ~8.0MB。
    //
    // 这是为了定位「russh 任务为什么无声死掉」而开的，等那个问题收敛之后
    // 可以把这段连同 Cargo.toml 的 strip 一起改回去。
    packaging {
        jniLibs {
            keepDebugSymbols += "**/libnexus_core.so"
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
    // HorizontalPager / gesture primitives behind the terminal tab switcher
    // (ui/TerminalTabsPager.kt). Only reaches us transitively via material3
    // otherwise, which would silently break if that dependency ever moved.
    implementation("androidx.compose.foundation:foundation")
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

    // AGP 给 JVM 单测用的是 "mockable" android.jar —— 里面每个方法体都是
    // `throw RuntimeException("Method ... not mocked")`。org.json 属于
    // android.jar，所以 GestureConfig 的序列化/容错逻辑在单测里会当场抛，
    // 测不了。这个 artifact 是同一套 API 的真实实现，放在测试 classpath 上
    // 把那个桩盖掉。只影响 test，不进 APK（APK 里用的还是系统自带的那份）。
    testImplementation("org.json:json:20231013")
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
    // The plugin shells out to a Python linker wrapper and defaults to the
    // command `python`, which doesn't exist on Debian 12+/13 (or in this
    // container) — only `python3` does. Without this the Rust *compiles* and
    // then dies at link time with "python: command not found", and only when
    // nexus-core actually changed: an unchanged cached .so links nothing and
    // hides the problem.
    pythonCommand = "python3"
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
    // `mergeXJniLibFolders` snapshots the jniLibs source dirs — one of which is
    // the directory cargoBuild writes into. The plugin declares no relationship
    // between the two, so without this the merge can snapshot *before* cargo has
    // rebuilt, and the APK ships yesterday's libnexus_core.so next to today's
    // UniFFI bindings. That mismatch doesn't fail the build: it crashes at
    // runtime, either as "UniFFI API checksum mismatch" or — when only a record's
    // fields changed — as a bare java.nio.BufferUnderflowException while
    // decoding a return value. Both look like app bugs rather than stale output,
    // which is exactly what makes it worth pinning down here.
    if (name == "mergeDebugJniLibFolders" || name == "mergeReleaseJniLibFolders") {
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
