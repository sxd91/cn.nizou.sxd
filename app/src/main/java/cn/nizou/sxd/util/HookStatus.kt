package cn.nizou.sxd.util

import android.content.SharedPreferences
import cn.nizou.sxd.MODULE_PREFS_NAME

/*
 * 激活状态检测（跨进程，基于 libxposed Remote Preferences）。
 */
object HookStatus {
    private const val KEY_HOOK_ACTIVE = "hook_active"
    private const val KEY_MODULE_ENV = "module_env"
    private const val KEY_API_VERSION = "api_version"

    /** module.prop 声明的 minApiVersion；框架 API 低于此值时 hook() 可能抛 HookFailedError。 */
    const val MIN_API = 30

    /** module.prop 声明的 targetApiVersion；框架 API 高于此值时模块行为未经适配。 */
    const val MAX_API = 102

    /** 未写入时的哨兵值：保守放行，避免老版本模块或首次注入被误判未激活。 */
    const val API_UNKNOWN = -1

    fun markActive(prefsRemote: SharedPreferences) {
        prefsRemote.edit().putBoolean(KEY_HOOK_ACTIVE, true).apply()
    }

    fun markEnv(prefsRemote: SharedPreferences, api: Int, framework: String) {
        val line = "libxposed API " + api + " · " + framework
        prefsRemote.edit()
            .putString(KEY_MODULE_ENV, line)
            .putInt(KEY_API_VERSION, api)
            .apply()
        runCatching {
            val ctx = currentApplication()
            ctx.getSharedPreferences(MODULE_PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_MODULE_ENV, line)
                .putInt(KEY_API_VERSION, api)
                .apply()
        }
    }

    fun readEnv(prefsRemote: SharedPreferences?): String? {
        prefsRemote?.let { return it.getString(KEY_MODULE_ENV, null) }
        return runCatching {
            val ctx = currentApplication()
            ctx.getSharedPreferences(MODULE_PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .getString(KEY_MODULE_ENV, null)
        }.getOrNull()
    }

    /** 框架上报的 API 版本；未记录时返回 API_UNKNOWN。 */
    fun readApiVersion(prefsRemote: SharedPreferences?): Int {
        prefsRemote?.let { return it.getInt(KEY_API_VERSION, API_UNKNOWN) }
        return runCatching {
            currentApplication()
                .getSharedPreferences(MODULE_PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .getInt(KEY_API_VERSION, API_UNKNOWN)
        }.getOrDefault(API_UNKNOWN)
    }

    /**
     * 注入菜单激活判定：hook_active 标记为真，且框架 API 版本落在 module.prop 声明的
     * minApiVersion..targetApiVersion 区间内。API_UNKNOWN 表示框架未上报版本
     * （老模块 / npatch 精简实现），保守放行——否则会把能用的框架误判成未激活。
     */
    fun isActivated(prefsRemote: SharedPreferences?): Boolean {
        val active = prefsRemote?.getBoolean(KEY_HOOK_ACTIVE, false) ?: localActive
        if (!active) return false
        val api = readApiVersion(prefsRemote)
        return api == API_UNKNOWN || api in MIN_API..MAX_API
    }

    @Volatile
    var localActive: Boolean = false
        private set

    fun markLocalActive() {
        localActive = true
    }
}