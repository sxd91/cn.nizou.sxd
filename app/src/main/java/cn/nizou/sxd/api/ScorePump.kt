package cn.nizou.sxd.api

import cn.nizou.sxd.util.SettingsPrefs
import cn.nizou.sxd.util.ScoreLog
import cn.nizou.sxd.util.XposedHelpers
import cn.nizou.sxd.util.kotlinStrokesAt
import cn.nizou.sxd.util.logI
import cn.nizou.sxd.util.toJsonString
import kotlin.coroutines.cancellation.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * 练习批量上传刷分（真自定义分数）。
 *
 * 逆向结论（reverser_ws/new_apktool_out 3.140.1）：
 * - `postSavedExp` 实际走 `POST /leo-star/android/exercise/rank/login/attend`（排行榜登录参与），
 *   服务端限次（用户实测每天约 3 次）——旧「刷分/拆条」方案撞上该限制。
 * - `uploadExamResult` 实际走 `PUT /leo-math/android/exams/v2/{examId}`（练习成绩上传主接口），
 *   PracticeHook「自动上分」无限刷走的正是它，**无 attend 的日限语义**。
 *
 * ## ★★ 2026-10-01 对齐 pk-node
 *
 * 用户反馈「日志输出都没有做好，应该和 pk-node 保持一致」。pk-node 的每一轮都有明确日志：
 *
 * ```
 * 出题 keypointId=235001 limit=100
 * 出题成功 examId=xxx <知识点名> 共 100 题（预计 +200 经验）
 * 提交（全对 100/100，含笔迹）
 * 提交成功：服务端判对 100/100，经验 +200
 * ```
 *
 * 本类现在按同样粒度打点：取题 / 对局 id / 提交 / 服务端判卷 / 分数变化，
 * 每一步都写日志，真机上可直接照 pk-node 的语义核对。
 *
 * ## 知识点选项（新增）
 *
 * `GET /leo-math/android/exams/exercises/type/{type}`（必带 `book`/`grade`/`semester`）
 * 拉知识点树，供 UI 下拉选择，不再需要用户手填 id 或盲扫 1~32768。
 * 旧版「1~32768 盲扫」保留为最后兜底。
 */
object ScorePump {

    /** 安全上限：最多刷这么多局（防止异常时死循环） */
    private const val MAX_ROUNDS = 1000

    /** 知识点 ID 遍历上限：1..2^15（32768），取题失败时自动逐个尝试 */
    private const val MAX_KEYPOINT_ID = 1 shl 15

    /** 遍历时每个 ID 的取题超时（短超时快速跳过无效 ID） */
    private const val SCAN_TIMEOUT_MS = 4000L

    /** 知识点扫描时每个 id 之间的间隔（防止在频控期打机关枪）。 */
    private const val SCAN_STEP_DELAY_MS = 600L

    /** 知识点扫描连续失败多少个就整体放弃（判定为频控）。 */
    private const val SCAN_ABORT_STREAK = 5

    /** 上传失败后的退避时长（在上报失败前先等这么久，让频控窗口往前走）。 */
    private const val UPLOAD_FAIL_BACKOFF_MS = 8_000L

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
            var kp = keyPointId
            stopped = false
            try {
                spLog("开始刷分 target=$target limit=$limit interval=${intervalMs}ms kp=${kp.ifBlank { "(自动)" }}")
                val initial = fetchCurrentScore()
                if (initial < 0) {
                    onDone(Result.failure(IllegalStateException("无法读取当前分数（宿主 ApiService 未初始化？）")))
                    return@thread
                }
                spLog("起始分数 $initial")
                onProgress(initial, 0)
                if (initial >= target) {
                    onDone(Result.success(initial))
                    return@thread
                }
                while (rounds < MAX_ROUNDS) {
                    if (stopped) {
                        onDone(Result.failure(CancellationException("用户停止，已刷 $rounds 局")))
                        return@thread
                    }
                    // 与 pk-node 一致：出题 keypointId=.. limit=..
                    spLog("出题 keypointId=$kp limit=$limit")
                    var examVO = fetchExam(kp, limit, 15000L)
                    if (examVO == null) {
                        // ★ 不再「取题失败就盲扫 1~32768」：
                        //   真机上那会在频控期打出上万个请求（日志里连续 429），
                        //   把账号彻底送进惩罚窗口。
                        //   只有「没填知识点」时才去扫描，且扫描自带 429 熔断 + 间隔。
                        if (kp.isNotBlank()) {
                            onDone(Result.failure(IllegalStateException(
                                "取题失败（知识点 $kp，可能命中频控），已刷 $rounds 局；请稍后重试或点停止"
                            )))
                            return@thread
                        }
                        onProgress(-1, rounds)
                        spLog("未指定知识点，开始扫描（带 429 熔断，最多 $MAX_KEYPOINT_ID）")
                        val scanned = scanValidKeypoint(limit)
                        if (scanned == null) {
                            onDone(Result.failure(IllegalStateException(
                                "未找到可用知识点（可能是频控/网络），已刷 $rounds 局"
                            )))
                            return@thread
                        }
                        kp = scanned.first
                        examVO = scanned.second
                        spLog("keypoint switched to $kp, continue pumping")
                    }
                    val examId = extractExamId(examVO)
                    if (examId.isNullOrBlank()) {
                        onDone(Result.failure(IllegalStateException(
                            "第 ${rounds + 1} 局取不到对局 id（ExamVO 无 idString/id/examId），已刷 $rounds 局"
                        )))
                        return@thread
                    }
                    val questionCnt = runCatching { XposedHelpers.getIntField(examVO, "questionCnt") }.getOrDefault(0)
                    val kpName = runCatching { XposedHelpers.getObjectField(examVO, "keypoint") as? String ?: "" }
                        .getOrDefault("")
                    // 与 pk-node 一致：出题成功 examId=.. <知识点> 共 N 题（预计 +M 经验）
                    spLog("出题成功 examId=$examId ${kpName.ifBlank { "-" }} 共 $questionCnt 题（预计 +${questionCnt * 2} 经验）")
                    buildFullCorrect(examVO)
                    spLog("提交（全对 $questionCnt/$questionCnt，含笔迹，costTime 300~450ms/题）")
                    if (!upload(examId, examVO)) {
                        // ★ 上传失败（真机上是 HTTP 400 / 429）**不能当作「又完成一轮」继续冲**：
                        //   继续冲只会把频控窗口越踩越深。这里退避后结束本轮，把决定权交回用户。
                        val backoff = UPLOAD_FAIL_BACKOFF_MS
                        spLog("上传失败，退避 ${backoff}ms 后停止本轮（已刷 $rounds 局）")
                        Thread.sleep(backoff)
                        onDone(Result.failure(IllegalStateException(
                            "上传第 ${rounds + 1} 局失败（400/429，疑似频控），已刷 $rounds 局；请稍后再试"
                        )))
                        return@thread
                    }
                    rounds++
                    val cur = fetchCurrentScore()
                    spLog("第 $rounds 局完成，服务端当前分数=${if (cur >= 0) cur else "读取失败"}")
                    onProgress(if (cur >= 0) cur else initial, rounds)
                    if (cur >= target) {
                        spLog("达成目标（$cur >= $target），共 $rounds 局")
                        onDone(Result.success(cur))
                        return@thread
                    }
                    if (intervalMs > 0) Thread.sleep(intervalMs)
                }
                onDone(Result.failure(IllegalStateException("达到 $MAX_ROUNDS 局上限仍未到目标，当前可能已接近，可再刷一次")))
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
     * 从 ExamVO 提取**对局 id**（`uploadExamResult(examId, ...)` 的第 1 个参数）。
     *
     * 旧实现只试 `idString` 一个字段，一旦宿主换字段名就会 `NoSuchField` 抛异常、整轮中断。
     * 现在按优先级逐个尝试，并把**实际命中的字段名**写进日志。
     */
    private fun extractExamId(examVO: Any): String? {
        for (field in EXAM_ID_FIELDS) {
            val v = runCatching { XposedHelpers.getObjectField(examVO, field) }.getOrNull()
            val s = v?.toString()?.trim()
            if (!s.isNullOrBlank() && s != "null" && s != "0") {
                spLog("examId from field `$field` = $s")
                return s
            }
        }
        return null
    }

    /**
     * 全对填充 ExamVO —— **逐字对齐 pk-node 的 `answerAll`**（`src/exercise.js`）。
     *
     * ## ★★ 2026-10-01：原来漏写 `curTrueAnswer`，这才是上传 HTTP 400 的真因
     *
     * pk-node 每题写入的字段（逐字段对照）：
     * ```js
     * userAnswer: answer,
     * status: 1,                       // 1 = 答对
     * costTime: per,
     * script: JSON.stringify(pathPoints),      // 服务端据此判卷
     * curTrueAnswer: {                          // ★★ 之前完全没写 → 400
     *   recognizeResult: answer,
     *   pathPoints: pathPoints,
     *   answer: 1,                              // 1 = 判定为「真实作答」
     *   showReductionFraction: 0,
     * }
     * ```
     * 缺 `curTrueAnswer` 时服务端认为该题没有真实作答记录 → **HTTP 400**。
     *
     * 另外两点也对齐：
     *  - `costTime` 每题**不同**（pk-node 里带 seed 变化），旧实现固定 300~450 也被服务端
     *    判定为「机器作答」；这里改成随题号递增的稳定抖动；
     *  - 每题笔迹用**不同起点**（避免完全雷同，服务端会比对）。
     */
    private fun buildFullCorrect(examVO: Any) {
        val questions = XposedHelpers.getObjectField(examVO, "questions") as? List<*>
        var totalTime = 0L
        var perQuestionCost = 0L
        questions?.forEachIndexed { idx, q ->
            val answers = XposedHelpers.getObjectField(q, "answers") as? List<*>
            val answer = answers?.firstOrNull()?.toString() ?: ""
            // 每题 costTime 不同（900ms 基线 + 按题号抖动），与 pk-node `answerAll` 一致。
            perQuestionCost = 900L + (idx % 5) * 37L
            XposedHelpers.callMethod(q, "setUserAnswer", answer)
            XposedHelpers.callMethod(q, "setCostTime", perQuestionCost)
            // script / pathPoints：每题起点不同，避免笔迹雷同。
            val points = answer.kotlinStrokesAt(seed = idx + 1)
            XposedHelpers.callMethod(q, "setScript", points.toJsonString())
            XposedHelpers.callMethod(q, "setStatus", 1)
            // ★★ curTrueAnswer —— 缺它服务端判「没有真实作答」→ HTTP 400。
            runCatching {
                val bean = Class.forName(
                    "com.fenbi.android.leo.exercise.data.CurTrueAnswerVO", false, examVO.javaClass.classLoader
                ).getDeclaredConstructor().newInstance()
                XposedHelpers.setObjectField(bean, "recognizeResult", answer)
                XposedHelpers.setObjectField(bean, "pathPoints", points.toJsonString())
                XposedHelpers.setIntField(bean, "answer", 1)
                XposedHelpers.setIntField(bean, "showReductionFraction", 0)
                XposedHelpers.setObjectField(q, "curTrueAnswer", bean)
                spLog("curTrueAnswer set for q$idx (recognizeResult=$answer)")
            }.onFailure { e ->
                // 类名随版本可能不同：退化为「直接按 JSON 字符串塞字段」。
                spLog("CurTrueAnswerVO bean unavailable (${e.message}), fallback to map")
                runCatching {
                    val map = HashMap<String, Any>()
                    map["recognizeResult"] = answer
                    map["pathPoints"] = points.toJsonString()
                    map["answer"] = 1
                    map["showReductionFraction"] = 0
                    XposedHelpers.setObjectField(q, "curTrueAnswer", map)
                }.onFailure { spLog("curTrueAnswer fallback failed: ${it.message}") }
            }
            totalTime += perQuestionCost
        }
        val questionCnt = XposedHelpers.getIntField(examVO, "questionCnt")
        XposedHelpers.callMethod(examVO, "setCorrectCnt", questionCnt)
        XposedHelpers.callMethod(examVO, "setCostTime", totalTime)
    }

    private fun fetchExam(keyPointId: String, limit: Int, timeoutMs: Long): Any? {
        val latch = CountDownLatch(1)
        var exam: Any? = null
        var err: Throwable? = null
        OralApiService.getExamInfo(keyPointId, limit) { r ->
            r.onSuccess { exam = it }.onFailure { err = it }
            latch.countDown()
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            spLog("getExamInfo timeout (kp=$keyPointId)")
            return null
        }
        if (err != null) {
            spLog("getExamInfo failed (kp=$keyPointId): ${err.message}")
            return null
        }
        return exam
    }

    /**
     * 从 1 遍历到 [MAX_KEYPOINT_ID]（2^15）找第一个能成功取题的知识点。
     *
     * ## ★ 2026-10-01：加 429 熔断 + 请求间隔
     *
     * 真机上原本会「取题失败就线性扫 3 万多个 id，每个发一个请求」，
     * 在频控期直接打出上万条 `HTTP 429`（日志已见 kp=1..15 连续 429）。
     * 现在：
     *  - 每个 id 之间 sleep [SCAN_STEP_DELAY_MS]，不再打机关枪；
     *  - 连续失败 [SCAN_ABORT_STREAK] 个就**整体放弃**（判定为频控，继续扫只是加深惩罚）。
     */
    private fun scanValidKeypoint(limit: Int): Pair<String, Any>? {
        var streak = 0
        for (id in 1..MAX_KEYPOINT_ID) {
            if (stopped) return null
            val exam = fetchExam(id.toString(), limit, SCAN_TIMEOUT_MS)
            if (exam != null) {
                SettingsPrefs.writeString("custom_score_keypoint", id.toString())
                spLog("valid keypoint found: $id")
                return id.toString() to exam
            }
            streak++
            if (streak >= SCAN_ABORT_STREAK) {
                spLog("连续 $streak 个知识点取题失败，判定为频控，停止扫描（避免加深惩罚）")
                return null
            }
            if (SCAN_STEP_DELAY_MS > 0) Thread.sleep(SCAN_STEP_DELAY_MS)
        }
        return null
    }

    private fun upload(examId: String, examVO: Any): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        var err: Throwable? = null
        var resp: Any? = null
        OralApiService.uploadExamResult(examId, examVO) { r ->
            r.onSuccess { ok = true; resp = it }.onFailure { err = it }
            latch.countDown()
        }
        if (!latch.await(15, TimeUnit.SECONDS)) {
            spLog("upload timeout")
            return false
        }
        if (err != null) {
            spLog("upload failed: ${err.message}")
            return false
        }
        // 与 pk-node 一致：提交成功：服务端判对 X/Y，经验 +Z
        runCatching {
            val correct = XposedHelpers.getIntField(resp!!, "correctCnt")
            val total = XposedHelpers.getIntField(resp!!, "questionCnt")
            spLog("提交成功：服务端判对 $correct/$total，经验 +${correct * 2}")
        }.onFailure { spLog("提交成功（响应无法解析 correctCnt，不影响计分）") }
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