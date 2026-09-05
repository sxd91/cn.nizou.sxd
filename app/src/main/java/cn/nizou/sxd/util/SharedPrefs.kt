package cn.nizou.sxd.util

import android.content.Context
import cn.nizou.sxd.MODULE_PREFS_NAME
import cn.nizou.sxd.entities.AutoAnswerMode

private val currentContext by lazy { currentApplication() }

private val modulePrefs by lazy {
    currentContext.getSharedPreferences(MODULE_PREFS_NAME, Context.MODE_PRIVATE)
}

/**
 * 供 Compose 设置 UI 持久化写入（新增，读仍走上方各 object 的 getter）。
 *
 * 注意：调用方传入的 `key` 参数是 `res.KEY_XXX`，它本身**已经是** prefs 键字符串
 * （如 "always_true_answer"，来自 `getString(R.string.key_always_true_answer)`）。
 * 不要再套一层 `res.keyValue(key)` —— 那会用该值再去 getIdentifier 查同名资源，
 * 而资源名是 `key_always_true_answer`（值才是 always_true_answer），查不到返回 0，
 * getString(0) 抛 NotFoundException 导致点击子菜单闪退（真机已复现）。
 * 因此这里直接用 `key` 作为 prefs 键。
 */
object SettingsPrefs {
    fun readBoolean(res: StringRes, key: String, def: Boolean): Boolean =
        modulePrefs.getBoolean(key, def)

    fun writeBoolean(res: StringRes, key: String, value: Boolean) {
        modulePrefs.edit().putBoolean(key, value).apply()
    }

    fun readString(res: StringRes, key: String, def: String): String =
        modulePrefs.getString(key, def) ?: def

    fun writeString(res: StringRes, key: String, value: String) {
        modulePrefs.edit().putString(key, value).apply()
    }

    /** 无资源键版本（裸字符串键，如 custom_score_keypoint） */
    fun readString(key: String, def: String): String =
        modulePrefs.getString(key, def) ?: def

    fun readBoolean(key: String, def: Boolean): Boolean = modulePrefs.getBoolean(key, def)

    fun writeBoolean(key: String, value: Boolean) {
        modulePrefs.edit().putBoolean(key, value).apply()
    }

    fun writeString(key: String, value: String) {
        modulePrefs.edit().putString(key, value).apply()
    }

    fun readInt(key: String, def: Int): Int = modulePrefs.getInt(key, def)

    fun writeInt(key: String, value: Int) {
        modulePrefs.edit().putInt(key, value).apply()
    }
}

object Common {
    val alwaysTrue get() = modulePrefs.getBoolean(moduleStringRes.KEY_ALWAYS_TRUE_ANSWER, true)
    // 2026-08-29 合并：单一「无视名字限制」开关（原「双倍昵称长度」+「解除昵称字符限制」合并），
    // 默认开：昵称长度（GBK 字节 ≤16）与字符/格式限制全部放开。
    val ignoreNicknameRestriction
        get() = modulePrefs.getBoolean(moduleStringRes.KEY_IGNORE_NICKNAME_RESTRICTION, true)
}

object Practice {
    val autoHonor get() = modulePrefs.getBoolean(moduleStringRes.KEY_AUTO_HONOR, false)
    val autoPractice
        get() = !autoHonor && modulePrefs.getBoolean(
            moduleStringRes.KEY_AUTO_PRACTICE,
            true
        )
    val autoPracticeQuick
        get() = autoPractice && modulePrefs.getBoolean(
            moduleStringRes.KEY_AUTO_PRACTICE_QUICK,
            false
        )
    val autoPracticeCyclic
        get() = autoPractice && modulePrefs.getBoolean(
            moduleStringRes.KEY_AUTO_PRACTICE_CYCLIC,
            false
        )
    val autoPracticeCyclicInterval: Int
        get() {
            return kotlin.runCatching {
                Integer.parseInt(
                    modulePrefs.getString(
                        moduleStringRes.KEY_AUTO_PRACTICE_CYCLIC_INTERVAL,
                        ""
                    )!!
                )
            }.getOrElse {
                1500
            }
        }
}

object PK {
    val mode: AutoAnswerMode
        get() {
            val index = runCatching {
                Integer.parseInt(modulePrefs.getString(moduleStringRes.KEY_AUTO_ANSWER_CONFIG, "")!!)
            }.getOrElse { 0 }
            // 越界保护：prefs 里残留非法 index 时回退 DISABLE，避免注入/拦截抛异常
            return AutoAnswerMode.entries.getOrElse(index) { AutoAnswerMode.DISABLE }
        }
    val customJs get() = modulePrefs.getString(moduleStringRes.KEY_CUSTOM_ANSWER_CONFIG, "")!!
    val quickModeMustWin
        get() = mode == AutoAnswerMode.QUICK && modulePrefs.getBoolean(
            moduleStringRes.KEY_QUICK_MODE_MUST_WIN, false
        )
    val quickModeInterval: Int
        get() {
            return kotlin.runCatching {
                Integer.parseInt(modulePrefs.getString(moduleStringRes.KEY_QUICK_MODE_INTERVAL, "")!!)
            }.getOrElse { 200 }
        }

    /** 自定义结算时间（**毫秒**整数，如 100）。>0 时 QUICK/STANDARD 提交 costTime 直接用该值（毫秒）；0=按 interval 计算。 */
    val settleTime: Int
        get() = runCatching {
            modulePrefs.getString("pk_settle_time", "")!!.toInt()
        }.getOrDefault(10)

    /** 自定义结算时间**独立开关**：开启后任意 PK 模式（含停用/自定义）提交都用 [settleTime] 作为 costTime，不再要求选秒结算。 */
    val settleEnabled
        get() = modulePrefs.getBoolean(moduleStringRes.KEY_PK_SETTLE_ENABLED, false)
    val pkCyclic
        get() = mode in arrayOf(
            AutoAnswerMode.STANDARD,
            AutoAnswerMode.QUICK
        ) && modulePrefs.getBoolean(moduleStringRes.KEY_PK_CYCLIC, false)
    /** 去除排行榜展示动效（独立开关）：跳过 motivation-honor-roll 展示，不等同于循环PK。 */
    val skipRanking
        get() = modulePrefs.getBoolean(moduleStringRes.KEY_PK_SKIP_RANKING, false)
    val pkCyclicInterval: Int
        get() {
            return kotlin.runCatching {
                Integer.parseInt(modulePrefs.getString(moduleStringRes.KEY_PK_CYCLIC_INTERVAL, "")!!)
            }.getOrElse { 1500 }
        }

    /** 循环 PK 方案：0=发包（hook 改提交包全对，cyclic 只刷新进下一局）；1=模拟点击结果页「继续 PK」。 */
    val pkCyclicMode: Int
        get() = kotlin.runCatching {
            Integer.parseInt(modulePrefs.getString("pk_cyclic_mode", "1")!!)
        }.getOrElse { 1 }
}

/** SimianV2 automation keeps quick answer and each result-page action independently configurable. */
object SimianV2AutomationPrefs {
    const val QUICK_ANSWER = "simianv2_quick_answer"
    const val AUTO_ANSWER = "simianv2_auto_answer"
    const val AUTO_ANSWER_DELAY = "simianv2_auto_answer_delay"
    const val HAPPY_ACCEPT = "simianv2_happy_accept"
    const val CONTINUE = "simianv2_continue"
    const val CONTINUE_PK = "simianv2_continue_pk"
    val quickAnswer get() = modulePrefs.getBoolean(QUICK_ANSWER, false)
    val autoAnswer get() = modulePrefs.getBoolean(AUTO_ANSWER, false)
    val autoAnswerDelay get() = modulePrefs.getString(AUTO_ANSWER_DELAY, "8500")?.toLongOrNull()?.coerceAtLeast(0L) ?: 8500L
    val autoHappyAccept get() = modulePrefs.getBoolean(HAPPY_ACCEPT, false)
    val autoContinue get() = modulePrefs.getBoolean(CONTINUE, false)
    val autoContinuePk get() = modulePrefs.getBoolean(CONTINUE_PK, false)
}

object Debug {
    val debug
        get() = modulePrefs.getBoolean(moduleStringRes.KEY_DEBUG, false)
}

/**
 * 抓包 / 改包开关（RetrofitHook 通用 Interceptor 用）。
 * - [capture]：抓包开关（默认关），开启后把经过该 Retrofit 的请求/响应写入 LogBuffer（可选写文件）。
 * - [rewrite]：改包开关（默认关），按 [rules] JSON 规则改请求 query/body；失败安全回落放行原请求。
 * - [writeFile]：是否把抓包日志追加写 externalCacheDir/packet_capture.log。
 * - [rules]：规则 JSON 数组，示例见 util/PacketTool.kt 头部注释。
 */
object Packet {
    val capture get() = modulePrefs.getBoolean(moduleStringRes.KEY_PACKET_CAPTURE, false)
    val rewrite get() = modulePrefs.getBoolean(moduleStringRes.KEY_PACKET_REWRITE, false)
    val writeFile get() = modulePrefs.getBoolean(moduleStringRes.KEY_PACKET_WRITE_FILE, false)
    val rules get() = modulePrefs.getString(moduleStringRes.KEY_REWRITE_RULES, "[]")!!
}

/**
 * Simian 改题目/改答案/口算答案/VIP。UI 开关由 ui-copier 用 SettingsPrefs 写入同一批键。
 */
object Simian {
    /** 改答案：EncryptResult 多题模式（所有题 answers[0]）改自定义答案 */
    val modifyAnswer get() = modulePrefs.getBoolean(moduleStringRes.KEY_MODIFY_ANSWER, false)

    /** 改题目：EncryptResult 单题模式（只保留最后一题并改 content） */
    val modifyTitle get() = modulePrefs.getBoolean(moduleStringRes.KEY_MODIFY_TITLE, false)

    /** 模式：0=多题改答案，1=单题改题目+答案 */
    val mode: Int
        get() {
            return runCatching {
                Integer.parseInt(modulePrefs.getString(moduleStringRes.KEY_SIMIAN_MODE, "")!!)
            }.getOrElse { 0 }
        }

    /** 自定义答案（口算答案，EncryptResult 与 recognize 共用），默认 "1" */
    val answers get() = modulePrefs.getString(moduleStringRes.KEY_CUSTOM_ANSWERS, "1")!!

    /** 单题模式的题目内容，默认 "78+13=\\square"（Kotlin 里 \\ = 一个反斜杠，存进去是 78+13=\square） */
    val title get() = modulePrefs.getString(moduleStringRes.KEY_CUSTOM_TITLE, "78+13=\\square")!!

    /** 口算练习 QuestionVO.getAnswers 自定义答案，默认 "1" */
    val practiceAnswer get() = modulePrefs.getString(moduleStringRes.KEY_PRACTICE_ANSWER, "1")!!

    /** 自定义题目数量：>0 时把 EncryptResult 题目裁剪/扩展为 N 题（改题目模式下 N 题都显示自定义题目）；0=不限制。 */
    val customQuestionCount: Int
        get() = runCatching {
            Integer.parseInt(modulePrefs.getString(moduleStringRes.KEY_CUSTOM_QUESTION_COUNT, "")!!)
        }.getOrElse { 0 }

    /**
     * 自定义正确题数（自定义分数新方案，0=全对）。
     * WebViewHook.hookDataEncrypt 按此值写提交包 status/correctCnt：>0 时前 N 题对、其余错。
     */
    val customCorrectCount: Int
        get() = runCatching {
            Integer.parseInt(modulePrefs.getString("custom_correct_count", "")!!)
        }.getOrElse { 0 }

    /** 解锁 VIP */
    val vip get() = modulePrefs.getBoolean(moduleStringRes.KEY_VIP, false)
}

/**
 * 自定义分数（CustomScoreScreen）。
 * - 刷分模式（mode=0）：增量提交获得经验（现状，服务端单次 obtainExp 有上限 ~200）。
 * - 真自定义分数（mode=1）：一次提交多条 todayExercises 把分数直接顶到目标值（拆条绕过单次上限）。
 */
object Score {
    /** 分数模式：0=刷分（增量），1=真自定义分数（一次设置目标） */
    val mode: Int
        get() = runCatching {
            modulePrefs.getString("custom_score_mode", "")!!.toInt()
        }.getOrElse { 0 }

    /** 真自定义分数：目标分数（设置后 curWeekScore 一次到位） */
    val target: Int
        get() = runCatching {
            modulePrefs.getString("custom_score_target", "")!!.toInt()
        }.getOrElse { 0 }

    /** 真自定义分数：单条 todayExercises 的 obtainExp 上限（服务端单条上限，默认 200） */
    val perItem: Int
        get() = runCatching {
            modulePrefs.getString("custom_score_per_item", "")!!.toInt()
        }.getOrElse { 200 }
}