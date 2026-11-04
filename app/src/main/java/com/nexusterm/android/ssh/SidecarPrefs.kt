package com.nexusterm.android.ssh

import android.content.Context

/**
 * 记住「哪些已保存配置的服务器不支持并行通道」（堡垒机只允许一条通道）。
 *
 * 探测（见 [SshClient.probe]）要在连接上另开一条 exec 通道；只允许一条通道的堡垒机会拒绝它，
 * 甚至掐断整条连接。一旦某个配置被判定为不支持，之后对它的连接都传 `sidecar = false`、
 * 不再探测——只存一个配置 id 的字符串集合，几十字节，删除配置时不清也无害（id 是 UUID，不会复用）。
 */
object SidecarPrefs {
    private const val FILE = "nexusterm_sidecar"
    private const val KEY_DENIED = "denied_config_ids"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isDenied(context: Context, configId: String): Boolean =
        prefs(context).getStringSet(KEY_DENIED, emptySet())?.contains(configId) == true

    fun markDenied(context: Context, configId: String) {
        val p = prefs(context)
        // getStringSet 返回的集合不能原地修改（官方文档明确禁止），复制一份再写回。
        val next = (p.getStringSet(KEY_DENIED, emptySet()) ?: emptySet()) + configId
        p.edit().putStringSet(KEY_DENIED, next).apply()
    }
}
