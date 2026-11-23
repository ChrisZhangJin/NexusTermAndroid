package com.nexusterm.android.ssh

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Where session passwords and private-key passphrases actually live.
 *
 * Deliberate deviation from an earlier draft of the milestone plan, which
 * had Rust own `set_password`/`get_password` (mirroring the desktop app's
 * plaintext SQLite columns). We dropped that entirely: nexus-core's
 * `SessionRecord`/`NewSession` (nexus-core/src/store/mod.rs) carry no secret
 * fields, and there is no UniFFI export that can read or write one. Secrets
 * are written straight to Android's `EncryptedSharedPreferences` (backed by
 * the Android Keystore via [MasterKey]), keyed by the session id that
 * `SessionStore.create()` hands back — never serialized, never sent across
 * the JNI boundary into Rust.
 *
 * One file for the whole app (not per-session files) — `SharedPreferences`
 * is a flat key/value store, so per-session isolation is just a key prefix.
 */
object SecretsStore {
    private const val PREFS_FILE = "nexus_term_secrets"
    private const val PASSWORD_PREFIX = "password:"
    private const val PASSPHRASE_PREFIX = "passphrase:"
    private const val TOTP_PREFIX = "totp:"

    @Volatile
    private var cached: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val created = EncryptedSharedPreferences.create(
                context.applicationContext,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            cached = created
            return created
        }
    }

    fun savePassword(context: Context, sessionId: String, password: String) {
        prefs(context).edit().putString(PASSWORD_PREFIX + sessionId, password).apply()
    }

    fun getPassword(context: Context, sessionId: String): String? =
        prefs(context).getString(PASSWORD_PREFIX + sessionId, null)

    fun savePassphrase(context: Context, sessionId: String, passphrase: String) {
        prefs(context).edit().putString(PASSPHRASE_PREFIX + sessionId, passphrase).apply()
    }

    fun getPassphrase(context: Context, sessionId: String): String? =
        prefs(context).getString(PASSPHRASE_PREFIX + sessionId, null)

    /**
     * 二次验证用的 TOTP 种子（base32 或 otpauth:// 链接）。和登录凭据分开存、分开清：
     * 改认证方式（比如密码 → 无凭据）不该把种子也丢掉。空串 = 删除。
     */
    fun saveTotpSeed(context: Context, sessionId: String, seed: String) {
        val e = prefs(context).edit()
        if (seed.isBlank()) e.remove(TOTP_PREFIX + sessionId) else e.putString(TOTP_PREFIX + sessionId, seed.trim())
        e.apply()
    }

    fun getTotpSeed(context: Context, sessionId: String): String? =
        prefs(context).getString(TOTP_PREFIX + sessionId, null)

    /** 只清登录凭据（密码 / 私钥口令），留着 TOTP 种子：改成「无凭据」认证时用。 */
    fun clearLogin(context: Context, sessionId: String) {
        prefs(context).edit()
            .remove(PASSWORD_PREFIX + sessionId)
            .remove(PASSPHRASE_PREFIX + sessionId)
            .apply()
    }

    /** Called when a saved session is deleted, so its secrets don't outlive it. */
    fun clear(context: Context, sessionId: String) {
        prefs(context).edit()
            .remove(PASSWORD_PREFIX + sessionId)
            .remove(PASSPHRASE_PREFIX + sessionId)
            .remove(TOTP_PREFIX + sessionId)
            .apply()
    }
}
