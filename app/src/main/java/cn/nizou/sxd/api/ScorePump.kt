package cn.nizou.sxd.api

import cn.nizou.sxd.util.SettingsPrefs
import cn.nizou.sxd.util.ScoreLog
import cn.nizou.sxd.util.XposedHelpers
import cn.nizou.sxd.util.logI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 「真自定义分数」—— **经验增量上报**（照老挂 [cn.apixiaoyuan.app] 与 pk-node 重写）。
 *
 * ## 链路（两条，2026-10-01 定案）
 *
 * | 链路 | 接口 | 语义 | 上限 |
 * |---|---|---|---|
 * | **增量上报**（本类，已跑通） | `POST /leo-star/android/exercise/rank/login/attend`<br>（宿主 `postSavedExp`） | `obtainExp` = 本次获得经验，服务端**累加**到周分数 | 单条 clamp 200；每个 `ruleType` 每天记一次；可记账 ruleType 只有 0/1 → **日上限 400** |
 * | 整卷提交（旧实现，已废弃） | `PUT /leo-math/android/exams/v2/{examId}` | 服务端回放笔迹判卷 | — |
 *
 * ## ★★ 2026-10-01：整卷提交为什么废弃
 *
 * 真机每局都是 HTTP 400：
 * ```
 * upload failed: HTTP 400
 * ```
 * 取证结论：
 *  1. `curTrueAnswer / recognizeResult / pathPoints` 是 **pk-node 的 H5 请求体字段** ——
 *     在宿主 8 个 dex 里逐个 `strings` 搜，**0 命中**（安卓 `QuestionVO` 没有这些字段）；
 *  2. 宿主**自己**发 `PUT .../exams/v2/{id}`（gzip+编码、body 完整）**也返回 400**。
 *
 * ⇒ 该链路在服务端已不是「造个 VO 就能过」的形态，模块不该继续在它上面堆反射。
 * 增量上报这条是**服务端明确记账**的合法链路，且**完全复用宿主 ApiService**
 * （签名/设备链/登录态都由宿主自己带，模块不碰加密）。
 *
 * ## 读分数
 *
 * `LegacyApiService.getCurrentUserExp()` → `rank/pre-fetch` 的 **`curWeekScore`**
 * （`curWeekExp` 恒为 0，是历史误判）。上报前后各读一次，**以服务端差值为准**。
 *
 * ## 日志粒度（对齐 pk-node / 老挂的「运行日志框」）
 *
 * ```
 * 开始上报 target=... （增量链路：attend，ruleType 0/1，每次 200，日上限 400）
 * 上报前 curWeekScore=850640
 * 上报 ruleType=0 obtainExp=200（finishTime=now）
 * ruleType=0 入账 +200（curWeekScore=850840）
 * ```
 *
 * 每步都进 [ScoreLog]（UI 的「运行日志」框）与全局日志。
 */
object ScorePump {

    /**
     * 单条 `obtainExp` 的服务端 clamp 上限。
     *
     * 发比它大的值服务端也只按 200 记（老挂与 pk-node 都已实测）。
     */
    private const val PER_ITEM_MAX = 200

    /**
     * **可记账的 `ruleType`** —— 只有 `0` 和 `1`。
     *
     * 老挂与 pk-node 全量枚举 0~43 后实测：只有这两个会让 `curWeekScore` 真正增加，
     * 其余（2..16, 20, 33, 41, 43）服务端返回 200 但**静默不记账**。
     * 同一个 ruleType **每天只记一次** → 增量链路日上限 = `200 × 2 = 400`。
     */
    private val PUMP_RULE_TYPES = listOf(0, 1)

    /** 上报后等服务端记账落库再读分数（pk-node 同款 900ms）。 */
    private const val ATTEND_SETTLE_MS = 900L

    /** 用户停止标志：cancel() 置位，当前局结束/下一局开始前退出 */
    @Volatile
    var stopped = false

    /**
     * 对局 id 的候选字段名（按优先级）。
     *
     * `uploadExamResult(examId, examVO)` 的第 1 参就是它，取自 ExamVO。
     * 宿主历史上用 `idString`；新老版本可能叫别的，这里全列上并逐个尝试，
     * 命中的字段名会写日志 —— 便于真机核对「对局 id 到底从哪来」。
     */
    private val EXAM_ID_FIELDS = listOf(
        "idString", "examIdString", "examId", "id", "uuid", "examUuid",
    )

    /** 请求停止当前 pumpToTarget 循环 */
    fun cancel() {
        stopped = true
        spLog("用户请求停止")
    }

    /**
     * 刷分链路日志：**同时**进「运行日志框」（[ScoreLog]）与全局日志（[logI]→文件）。
     *
     * 运行日志框只看这个链路，避免被宿主全量日志淹没（用户诉求）。
     */
    private fun spLog(msg: String) {
        val line = "[${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}] $msg"
        ScoreLog.add(line)
        logI("ScorePump: $msg")
    }

    /**
     * 知识点候选（供 UI 下拉）。
     *
     * 来源：宿主自身的 `GET /leo-math/android/recommend/keypoint`（真机日志可见
     * `recommend keypoint auto-recorded: id=95`）。该响应由 [RetrofitHook] 解析后
     * 写入这里 + 持久化到 prefs（跨进程给模块设置页读），**不需要新增接口**。
     */
    @Volatile
    var keypointOptions: List<Triple<String, String, Int>> = emptyList()
        private set

    private const val PREF_KEYPOINT_OPTIONS = "custom_score_keypoint_options"

    /** 由 [RetrofitHook] 在解析到 recommend/keypoint 响应时调用（全量，不限第一条）。 */
    fun updateKeypointOptions(list: List<Triple<String, String, Int>>) {
        if (list.isEmpty()) return
        keypointOptions = list
        runCatching {
            val arr = org.json.JSONArray()
            list.forEach { (id, name, cnt) ->
                arr.put(org.json.JSONObject().put("id", id).put("name", name).put("cnt", cnt))
            }
            SettingsPrefs.writeString(PREF_KEYPOINT_OPTIONS, arr.toString())
        }.onFailure { spLog("persist keypoints failed: ${it.message}") }
    }

    /** 从 prefs 载入缓存的知识点列表（模块设置页跨进程读）。 */
    fun loadKeypointOptions(): List<Triple<String, String, Int>> {
        if (keypointOptions.isNotEmpty()) return keypointOptions
        val raw = SettingsPrefs.readString(PREF_KEYPOINT_OPTIONS, "")
        if (raw.isBlank()) return emptyList()
        val parsed = runCatching {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Triple(o.optString("id"), o.optString("name"), o.optInt("cnt"))
            }
        }.getOrDefault(emptyList())
        keypointOptions = parsed
        return parsed
    }

    /**
     * 刷到目标分数。
     *
     * ## ★★ 2026-10-01 修复：并发重复启动 + 429 盲扫风暴
     *
     * 真机日志（`ScorePump: 开始刷分 ...` **1.5 秒内出现 4 次**，互相踩踏）：
     * ```
     * 21:11:51.288 开始刷分 target=100000000 ... kp=93
     * 21:11:51.702 开始刷分 ...      <-- 第二次
     * 21:11:52.203 开始刷分 ...      <-- 第三次
     * 21:11:53.602 开始刷分 ...      <-- 第四次
     * 21:11:51.461 取题失败，开始扫描有效知识点 1~32768
     * 21:11:54.x   getExamInfo failed (kp=1..15): HTTP 429   <-- 每个 id 一个请求
     * ```
     * 两个问题：
     *  1. **没有重入保护** —— 点一次「开始」就起一个 thread，多次点击 = 多个循环并发，
     *     一起打接口 → 服务端 429；
     *  2. **429 后盲扫 1~32768** —— 取题失败本应退避，却去线性扫 3 万多个知识点 ID，
     *     每个发一个请求，把账号彻底打进频控。
     *
     * 修法：
     *  - [running] 原子标志：已有循环在跑时**直接拒绝**新请求（并回明确原因）；
     *  - 取题失败**不再盲扫**（只在「用户没填知识点」时才扫，且遇频控立刻熔断停止）；
     *  - 上传失败先退避再结束本轮，不要把 400/429 当成「正常一轮」继续冲。
     */
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 刷到目标分数 —— **照老挂（cn.apixiaoyuan.app）与 pk-node 的「经验增量上报」链路重写**。
     *
     * ## ★★ 2026-10-01 为什么换链路
     *
     * 旧实现循环「取卷子 → 全对 → `uploadExamResult` 上传」，真机**每局都是 HTTP 400**：
     * ```
     * CurTrueAnswerVO bean unavailable (...)
     * curTrueAnswer fallback failed: curTrueAnswer in class ...QuestionVO
     * upload failed: HTTP 400
     * ```
     * 根因（已取证）：`curTrueAnswer / recognizeResult / pathPoints` 是 **pk-node 那边 H5
     * 请求体里的 JS 字段**；在宿主 8 个 dex 里逐个 `strings` 搜 —— **0 命中**。
     * 也就是说安卓 `QuestionVO` **根本没有这些字段**，反射塞必然失败；
     * 而宿主自己发 `PUT /leo-math/android/exams/v2/{id}`（gzip+编码 + 完整 body）
     * **同样返回 400** —— 该链路在服务端已不是「随便造个 VO 就能过」的形态。
     *
     * 老挂（本项目里**真跑通**的那份）与 pk-node 的刷分都走**另一条**：
     * ```
     * POST /leo-star/android/exercise/rank/login/attend      (postSavedExp)
     * body: {todayExercises:[{finishTime, obtainExp, ruleType}]}
     * ```
     * 语义是**增量上报**（`obtainExp` = 本次获得经验，服务端累加到周分数），
     * 不是「设置总分」。以服务端 `curWeekScore` 的前后差值为准。
     *
     * ## 实测上限（老挂与 pk-node 都已确认）
     *
     * - 单条 `obtainExp` 服务端 clamp 到 [PER_ITEM_MAX]（200）；
     * - **同一个 `ruleType` 每天只记一次**（第二次返回 `{data:true}` 但分数不动）；
     * - 可记账的 ruleType 只有 [PUMP_RULE_TYPES]（`0` / `1`）→ **日上限 400**；
     * - 「拆条」是错的（同一天同 ruleType 多条属伪造，且不加分）。
     *
     * ## 重入保护（保留）
     *
     * 真机曾出现 1.5 秒内 4 次「开始刷分」互相踩踏（每个都起线程打接口 → 429），
     * 因此 [running] 原子标志仍然保留：已有循环在跑时直接拒绝。
     *
     * @param keyPointId 已废弃（增量上报不需要取题）；保留形参以兼容既有 UI 调用。
     * @param limit      已废弃（同上）。
     * @param intervalMs 两次 ruleType 上报之间的间隔（毫秒）。
     */
    fun pumpToTarget(
        keyPointId: String,
        limit: Int,
        intervalMs: Long,
        target: Int,
        onProgress: (currentScore: Int, rounds: Int) -> Unit,
        onDone: (Result<Int>) -> Unit,
    ) {
        // ★ 重入保护：已有循环在跑时直接拒绝（真机曾出现 4 个循环并发互相踩踏）。
        if (!running.compareAndSet(false, true)) {
            spLog("拒绝启动 —— 已有刷分循环在跑（请先停止）")
            onDone(Result.failure(IllegalStateException("已有刷分任务在运行，请先点「停止刷分」")))
            return
        }
        thread {
            var rounds = 0
            stopped = false
            try {
                spLog(
                    "开始上报 target=$target（增量链路：attend，ruleType ${PUMP_RULE_TYPES.joinToString("/")}，" +
                        "每次 $PER_ITEM_MAX，日上限 ${PER_ITEM_MAX * PUMP_RULE_TYPES.size}）"
                )
                val initial = fetchCurrentScore()
                if (initial < 0) {
                    onDone(Result.failure(IllegalStateException("无法读取当前分数（宿主 ApiService 未初始化？）")))
                    return@thread
                }
                spLog("上报前 curWeekScore=$initial")
                onProgress(initial, 0)
                if (initial >= target) {
                    spLog("当前分数已达成目标（$initial >= $target）")
                    onDone(Result.success(initial))
                    return@thread
                }

                var last = initial
                var reported = 0
                for (rt in PUMP_RULE_TYPES) {
                    if (stopped) break
                    spLog("上报 ruleType=$rt obtainExp=$PER_ITEM_MAX（finishTime=now）")
                    if (!attend(PER_ITEM_MAX, rt)) {
                        spLog("ruleType=$rt 上报失败（网络/服务端拒绝）")
                        continue
                    }
                    reported++
                    // 等服务端记账落库再读数（pk-node 同款 900ms）。
                    Thread.sleep(ATTEND_SETTLE_MS)
                    val now = fetchCurrentScore()
                    val gained = if (now >= 0 && last >= 0) now - last else 0
                    rounds++
                    if (gained > 0) {
                        spLog("ruleType=$rt 入账 +$gained（curWeekScore=$now）")
                    } else {
                        spLog("ruleType=$rt 服务端未增加（该类型今日已记过，属正常）")
                    }
                    if (now >= 0) last = now
                    onProgress(last, rounds)
                    if (last >= target) {
                        spLog("达成目标（$last >= $target）")
                        onDone(Result.success(last))
                        return@thread
                    }
                    if (intervalMs > 0) Thread.sleep(intervalMs)
                }

                val cap = PER_ITEM_MAX * PUMP_RULE_TYPES.size
                if (last <= initial && reported > 0) {
                    spLog("本轮无新增：可记账 ruleType 今日均已记过（日上限 $cap）")
                    onDone(Result.failure(IllegalStateException(
                        "服务端未增加分数：可记账的 ruleType（${PUMP_RULE_TYPES.joinToString("/")}）今日都已记过，" +
                            "日上限 $cap。明天再试。"
                    )))
                } else {
                    spLog("本轮结束：$initial → $last（+${last - initial}）")
                    onDone(Result.failure(IllegalStateException(
                        "已上报完成：$initial → $last（+${last - initial}），未达目标 $target。" +
                            "增量链路日上限 $cap，可明天再刷。"
                    )))
                }
            } catch (e: Throwable) {
                logI(e)
                onDone(Result.failure(e))
            } finally {
                // ★ 无论成功/失败/停止，都要释放重入标志（否则用户再也点不动「开始」）。
                running.set(false)
                spLog("本轮结束（rounds=$rounds）")
            }
        }
    }

    /**
     * 通过宿主 `postSavedExp` 上报**一条经验增量**（`POST .../rank/login/attend`）。
     *
     * 走宿主自己的 ApiService（模块被注入宿主进程时由 [cn.nizou.sxd.hook.SettingHook] 注入），
     * 因此**签名/设备链/登录态全部由宿主自己带**，模块不需要碰任何加密。
     *
     * @param delta    本次增量（服务端 clamp 到 200）
     * @param ruleType 规则类型（见 [PUMP_RULE_TYPES]）
     */
    private fun attend(delta: Int, ruleType: Int): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        var err: Throwable? = null
        LegacyApiService.postSavedExp(delta, ruleType) { r ->
            r.onSuccess { ok = true }.onFailure { err = it }
            latch.countDown()
        }
        if (!latch.await(15, TimeUnit.SECONDS)) {
            spLog("attend($ruleType) 超时")
            return false
        }
        if (err != null) {
            spLog("attend($ruleType) 失败：${err.message}")
            return false
        }
        return ok
    }

    private fun fetchCurrentScore(): Int {
        val latch = CountDownLatch(1)
        var score = -1
        var err: Throwable? = null
        LegacyApiService.getCurrentUserExp { r ->
            r.onSuccess { data ->
                score = XposedHelpers.getIntField(data, "curWeekScore")
            }.onFailure { err = it }
            latch.countDown()
        }
        if (!latch.await(15, TimeUnit.SECONDS)) {
            spLog("pre-fetch timeout")
            return -1
        }
        if (err != null) {
            spLog("pre-fetch failed: ${err.message}")
            return -1
        }
        return score
    }
}