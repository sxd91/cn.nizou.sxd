package cn.nizou.sxd.util

import android.content.res.Resources
import cn.nizou.sxd.BuildConfig
import cn.nizou.sxd.R
import cn.nizou.sxd.XposedInit

class StringRes(private val resources: Resources) {
    val KEY_ALWAYS_TRUE_ANSWER = resString(R.string.key_always_true_answer)
    // 2026-08-29 合并：原「双倍昵称长度」+「解除昵称字符限制」合并为单一「无视名字限制」开关
    val KEY_IGNORE_NICKNAME_RESTRICTION = resString(R.string.key_remove_restriction_on_nickname)
    val KEY_AUTO_HONOR = resString(R.string.key_auto_honor)
    val KEY_AUTO_PRACTICE = resString(R.string.key_auto_practice)
    val KEY_AUTO_PRACTICE_QUICK = resString(R.string.key_auto_practice_quick)
    val KEY_AUTO_PRACTICE_CYCLIC = resString(R.string.key_auto_practice_cyclic)
    val KEY_AUTO_PRACTICE_CYCLIC_INTERVAL = resString(R.string.key_auto_practice_cyclic_interval)
    val KEY_AUTO_ANSWER_CONFIG = resString(R.string.key_auto_answer_config)
    val KEY_CUSTOM_ANSWER_CONFIG = resString(R.string.key_custom_answer_config)
    val KEY_QUICK_MODE_MUST_WIN = resString(R.string.key_quick_mode_must_win)
    val KEY_QUICK_MODE_INTERVAL = resString(R.string.key_quick_mode_interval)
    val KEY_PK_CYCLIC = resString(R.string.key_pk_cyclic)
    val KEY_PK_CYCLIC_INTERVAL = resString(R.string.key_pk_cyclic_interval)
    val KEY_PK_FAST_SETTLE = resString(R.string.key_pk_fast_settle)
    val KEY_PK_SKIP_RANKING = resString(R.string.key_pk_skip_ranking)
    val KEY_PK_SETTLE_ENABLED = resString(R.string.key_pk_settle_enabled)
    val KEY_H5_DEBUG_CONSOLE = resString(R.string.key_h5_debug_console)
    val KEY_CUSTOM_QUESTION_COUNT = resString(R.string.key_custom_question_count)
    val KEY_GITHUB = resString(R.string.key_github)
    val KEY_VERSION = resString(R.string.key_version)
    val KEY_GOTO_SETTINGS = resString(R.string.key_goto_settings)
    val KEY_DEBUG = resString(R.string.key_debug)
    val KEY_MODIFY_ANSWER = resString(R.string.key_modify_answer)
    val KEY_MODIFY_TITLE = resString(R.string.key_modify_title)
    val KEY_SIMIAN_MODE = resString(R.string.key_simian_mode)
    val KEY_CUSTOM_ANSWERS = resString(R.string.key_custom_answers)
    val KEY_CUSTOM_TITLE = resString(R.string.key_custom_title)
    val KEY_PRACTICE_ANSWER = resString(R.string.key_practice_answer)
    val KEY_VIP = resString(R.string.key_vip)
    val KEY_PACKET_CAPTURE = resString(R.string.key_packet_capture)
    val KEY_PACKET_REWRITE = resString(R.string.key_packet_rewrite)
    val KEY_PACKET_WRITE_FILE = resString(R.string.key_packet_write_file)
    val KEY_REWRITE_RULES = resString(R.string.key_rewrite_rules)

    /** 容错读字符串：模块资源不可用（回退到系统资源）时返回资源名，避免崩溃。 */
    private fun resString(id: Int): String =
        try {
            resources.getString(id)
        } catch (_: Throwable) {
            // 资源名不可得时用资源 id 的十六进制表示，保证 prefs 键唯一且稳定
            "res_$id"
        }

    /** 按 key 字符串取同名 R.string 的值（供 SettingsPrefs 通用读写） */
    fun keyValue(name: String): String =
        try {
            resources.getString(
                resources.getIdentifier(name, "string", BuildConfig.APPLICATION_ID)
            )
        } catch (_: Throwable) {
            name
        }
}

val moduleStringRes by lazy {
    StringRes(XposedInit.moduleRes)
}