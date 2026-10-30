---
name: rust-logging-on-android
description: "How nexus-core's Rust panics and tracing events reach logcat, and the build traps that silently disable it"
metadata: 
  node_type: memory
  type: project
  originSessionId: 323d9a20-4dfb-4370-95a6-f6b681a66926
  modified: 2026-10-30T06:36:51.627Z
---

Rust-side observability in this app is not free — three separate things must all
be right, and each fails *silently*:

1. **Panics vanish by default.** `panic = "unwind"` with no `set_hook`, and an
   Android app process's stderr is `/dev/null`. `nexus-core/src/logging.rs`
   installs a panic hook that writes to logcat directly via a hand-declared
   `extern "C" __android_log_write` (no `tracing-android` — unmaintained since
   2021; zero new dependencies).
2. **`tracing` events with no subscriber are discarded.** Same file installs a
   `MakeWriter` subscriber. `init_logging()` must be the **first** thing in
   `Application.onCreate`, before `initStore`.
3. **Backtraces need the symbol table.** `Cargo.toml` must say
   `strip = "debuginfo"`, not `strip = true`, and `app/build.gradle.kts` needs
   `packaging { jniLibs { keepDebugSymbols += "**/libnexus_core.so" } }` or
   AGP's `stripDebugDebugSymbols` strips it back out. Costs ~3 MB in the APK.

Everything lands under logcat tag `NexusRust`: `adb logcat -s NexusRust:V`.
Default filter `info,nexus_core=debug`. `tracing-subscriber` keeps its default
features, so the `tracing-log` bridge is active — `log::warn!` from dependency
crates (e.g. the vendored `russh-cryptovec`) shows up under the same tag.

**Build trap:** an incremental `gradle assembleDebug` can leave the Rust `.so`
untouched (`mergeDebugNativeLibs UP-TO-DATE`) while regenerating the Kotlin
bindings. The app then dies at first FFI call with
`RuntimeException: UniFFI API checksum mismatch`. **After any Rust change the
last build must be `gradle clean assembleDebug`.** There is no `./gradlew` in
this repo — gradle is at
`/opt/compose_service/nexusterm-android/gradle-8.7/bin/gradle`.

**Why:** before this existed, "why did the russh task die" was unanswerable —
the read loop had been logging a `reason` the whole time with nobody listening.
With it, [[android-mlock-rlimit]] was found in a single run.

**How to apply:** when a Rust-side failure is invisible, check these three
first; don't conclude "no panic happened" from a quiet logcat unless
`init_logging` is confirmed to have run (it logs a banner on success).
