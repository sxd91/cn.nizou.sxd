package cn.nizou.sxd.api

import cn.nizou.sxd.util.SettingsPrefs
import cn.nizou.sxd.util.XposedHelpers
import cn.nizou.sxd.util.logI
import cn.nizou.sxd.util.strokes
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
        }.onFailure { logI("ScorePump: persist keypoints failed: ${it.message}") }
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
     * @param keyPointId 练习知识点 ID（留空则先试记录值，再自动扫描）
     * @param limit 每局题目数（默认 30）
     * @param intervalMs 每局间隔（防频率风控）
     * @param target 目标分数（curWeekScore）
     * @param onProgress (当前分数, 已刷局数) —— 工作线程回调；currentScore<0 表示知识点扫描中
     * @param onDone 结束（成功返回最终分数；失败返回异常）
     */
    fun pumpToTarget(
        keyPointId: String,
        limit: Int,
        intervalMs: Long,
        target: Int,
        onProgress: (currentScore: Int, rounds: Int) -> Unit,
        onDone: (Result<Int>) -> Unit,
    ) {
        thread {
            var rounds = 0
            var kp = keyPointId
            stopped = false
            try {
                logI("ScorePump: 开始刷分 target=$target limit=$limit interval=${intervalMs}ms kp=${kp.ifBlank { "(自动)" }}")
                val initial = fetchCurrentScore()
                if (initial < 0) {
                    onDone(Result.failure(IllegalStateException("无法读取当前分数（宿主 ApiService 未初始化？）")))
                    return@thread
                }
                logI("ScorePump: 起始分数 $initial")
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
                    logI("ScorePump: 出题 keypointId=$kp limit=$limit")
                    var examVO = fetchExam(kp, limit, 15000L)
                    if (examVO == null) {
                        onProgress(-1, rounds)
                        logI("ScorePump: 取题失败，开始扫描有效知识点 1~$MAX_KEYPOINT_ID")
                        val scanned = scanValidKeypoint(limit)
                        if (scanned == null) {
                            onDone(
                                Result.failure(
                                    IllegalStateException(
                                        "知识点 1~$MAX_KEYPOINT_ID 均无法取题（网络异常或服务端风控），已刷 $rounds 局"
                                    )
                                )
                            )
                            return@thread
                        }
                        kp = scanned.first
                        examVO = scanned.second
                        logI("ScorePump: keypoint switched to $kp, continue pumping")
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
                    logI("ScorePump: 出题成功 examId=$examId ${kpName.ifBlank { "-" }} 共 $questionCnt 题（预计 +${questionCnt * 2} 经验）")
                    buildFullCorrect(examVO)
                    logI("ScorePump: 提交（全对 $questionCnt/$questionCnt，含笔迹，costTime 300~450ms/题）")
                    if (!upload(examId, examVO)) {
                        onDone(
                            Result.failure(
                                IllegalStateException("上传第 ${rounds + 1} 局失败，已刷 $rounds 局")
                            )
                        )
                        return@thread
                    }
                    rounds++
                    val cur = fetchCurrentScore()
                    logI("ScorePump: 第 $rounds 局完成，服务端当前分数=${if (cur >= 0) cur else "读取失败"}")
                    onProgress(if (cur >= 0) cur else initial, rounds)
                    if (cur >= target) {
                        logI("ScorePump: 达成目标（$cur >= $target），共 $rounds 局")
                        onDone(Result.success(cur))
                        return@thread
                    }
                    if (intervalMs > 0) Thread.sleep(intervalMs)
                }
                onDone(Result.failure(IllegalStateException("达到 $MAX_ROUNDS 局上限仍未到目标，当前可能已接近，可再刷一次")))
            } catch (e: Throwable) {
                logI(e)
                onDone(Result.failure(e))
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
                logI("ScorePump: examId from field `$field` = $s")
                return s
            }
        }
        return null
    }

    /**
     * 全对填充 ExamVO（答案/画线/status=1/correctCnt）。
     * 每题 costTime 随机 300~450ms（**练习提交必须 ≥0.3s**，服务端验证下限）。
     * 每题都写真实笔迹（连续线段），防「单点/无手写」风控 —— 与 pk-node `answerAll` 一致。
     */
    private fun buildFullCorrect(examVO: Any) {
        val questions = XposedHelpers.getObjectField(examVO, "questions") as? List<*>
        var totalTime = 0L
        questions?.forEach {
            val answers = XposedHelpers.getObjectField(it, "answers") as? List<*>
            val answer = answers?.firstOrNull()?.toString() ?: ""
            XposedHelpers.callMethod(it, "setUserAnswer", answer)
            val costTime = Random.nextLong(300, 450)
            XposedHelpers.callMethod(it, "setCostTime", costTime)
            XposedHelpers.callMethod(it, "setScript", answer.strokes.toJsonString())
            XposedHelpers.callMethod(it, "setStatus", 1)
            totalTime += costTime
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
            logI("ScorePump: getExamInfo timeout (kp=$keyPointId)")
            return null
        }
        if (err != null) {
            logI("ScorePump: getExamInfo failed (kp=$keyPointId): ${err.message}")
            return null
        }
        return exam
    }

    /**
     * 从 1 遍历到 [MAX_KEYPOINT_ID]（2^15）找第一个能成功取题的知识点。
     * 找到后写入 prefs `custom_score_keypoint` 供后续默认使用。
     */
    private fun scanValidKeypoint(limit: Int): Pair<String, Any>? {
        for (id in 1..MAX_KEYPOINT_ID) {
            if (stopped) return null
            val exam = fetchExam(id.toString(), limit, SCAN_TIMEOUT_MS)
            if (exam != null) {
                SettingsPrefs.writeString("custom_score_keypoint", id.toString())
                logI("ScorePump: valid keypoint found: $id")
                return id.toString() to exam
            }
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
            logI("ScorePump: upload timeout")
            return false
        }
        if (err != null) {
            logI("ScorePump: upload failed: ${err.message}")
            return false
        }
        // 与 pk-node 一致：提交成功：服务端判对 X/Y，经验 +Z
        runCatching {
            val correct = XposedHelpers.getIntField(resp!!, "correctCnt")
            val total = XposedHelpers.getIntField(resp!!, "questionCnt")
            logI("ScorePump: 提交成功：服务端判对 $correct/$total，经验 +${correct * 2}")
        }.onFailure { logI("ScorePump: 提交成功（响应无法解析 correctCnt，不影响计分）") }
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
            logI("ScorePump: pre-fetch timeout")
            return -1
        }
        if (err != null) {
            logI("ScorePump: pre-fetch failed: ${err.message}")
            return -1
        }
        return score
    }
}