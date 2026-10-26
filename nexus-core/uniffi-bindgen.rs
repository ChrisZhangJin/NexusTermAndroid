//! 生成 Kotlin 绑定用的入口：`cargo run --bin uniffi-bindgen -- generate --library <.so> --language kotlin --out-dir <dir>`
//! 现代 UniFFI（proc-macro 导出模式，无 .udl）的标准套路，供 Gradle 的
//! org.mozilla.rust-android-gradle 插件在构建期调用。
fn main() {
    uniffi::uniffi_bindgen_main()
}
