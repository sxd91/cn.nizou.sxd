package cn.nizou.sxd.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cn.nizou.sxd.api.LegacyApiService
import cn.nizou.sxd.api.OralApiService
import cn.nizou.sxd.api.ScorePump
import cn.nizou.sxd.util.SettingsPrefs
import cn.nizou.sxd.util.XposedHelpers
import cn.nizou.sxd.util.logI
import cn.nizou.sxd.util.mainHandler
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Arrow_back

/**
 * 当前分数加载状态机。
 *
 * 根因修复：LegacyApiService 只在宿主进程由 SettingHook.hookHomeActivity 的 init()+setup()
 * 初始化；模块独立 App（模块本体）是**独立进程**，不注入宿主，apiService/coroutineContext
 * 从未初始化 → 旧代码卡死在「加载中」。这里在调用前先用 [LegacyApiService.isReady] 探测：
 * 未初始化（模块本体）→ 明确错误提示；已初始化（宿主注入面板）→ 正常拉取。
 */
private sealed interface ScoreLoadState {
    /** 加载中（仅在宿主内、已初始化后短暂出现） */
    data object Loading : ScoreLoadState

    /** 模块本体独立运行：LegacyApiService 未初始化，无法读宿主分数 */
    data object Uninitialized : ScoreLoadState

    data class Success(val score: Int) : ScoreLoadState

    data class Error(val message: String) : ScoreLoadState
}

/**
 * 自定义分数面板。
 *
 * 两种模式（prefs `custom_score_mode`，0=刷分 / 1=真自定义）：
 * - 刷分模式（保留旧逻辑）：输入增量，走 `postSavedExp`（`POST /leo-star/android/exercise/rank/login/attend`，
 *   排行榜登录参与）。**该接口服务端限次（每天约 3 次）**——超出后被拒，属正常现象。
 * - 真自定义分数：走**练习成绩上传主接口**（`PUT /leo-math/android/exams/v2/{examId}`，与「自动上分」
 *   同链路，无 attend 的日限）——循环「取卷子→全对→上传」，每局后 pre-fetch 当前分数，直到 ≥ 目标。
 *   见 [ScorePump]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomScoreScreen(onBack: () -> Unit) {
    var loadState by remember { mutableStateOf<ScoreLoadState>(ScoreLoadState.Loading) }
    var scoreMode by remember {
        mutableIntStateOf(SettingsPrefs.readInt("custom_score_mode", 0).coerceIn(0, 1))
    }
    var target by remember { mutableStateOf("") }
    var directTarget by remember { mutableStateOf("") }
    var keyPointId by remember {
        mutableStateOf(SettingsPrefs.readString("custom_score_keypoint", ""))
    }
    var limit by remember {
        mutableStateOf(SettingsPrefs.readString("custom_score_limit", "10"))
    }
    var intervalMs by remember {
        mutableStateOf(SettingsPrefs.readString("custom_score_interval", "2000"))
    }
    var suppose by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var pumping by remember { mutableStateOf(false) }
    var pumpProgress by remember { mutableStateOf("") }
    var resultMsg by remember { mutableStateOf<String?>(null) }

    val currentScore = (loadState as? ScoreLoadState.Success)?.score

    fun updateCurrentScore() {
        if (!LegacyApiService.isReady()) {
            loadState = ScoreLoadState.Uninitialized
            return
        }
        loadState = ScoreLoadState.Loading
        LegacyApiService.getCurrentUserExp {
            it.onSuccess { data ->
                val curWeekScore = XposedHelpers.getIntField(data, "curWeekScore")
                logI("curWeekScore: $curWeekScore")
                mainHandler.post {
                    loadState = ScoreLoadState.Success(curWeekScore)
                    suppose = null
                    resultMsg = null
                }
            }.onFailure { th ->
                logI(th)
                mainHandler.post {
                    loadState = ScoreLoadState.Error(th.message ?: th.toString())
                }
            }
        }
    }

    LaunchedEffect(Unit) { updateCurrentScore() }

    fun startPump() {
        val cur = currentScore ?: return
        val goal = directTarget.toIntOrNull() ?: return
        // 知识点可为空：ScorePump 会先按记录值取题，失败自动从 1 遍历到 2^15 找有效知识点
        val kp = keyPointId.trim()
        val lim = limit.toIntOrNull()?.coerceIn(1, 200) ?: 10
        val iv = intervalMs.toLongOrNull()?.coerceIn(0, 60_000) ?: 2000L
        if (goal <= cur) {
            resultMsg = "目标分数必须大于当前分数（$cur）"
            return
        }
        pumping = true
        pumpProgress = "开始：当前 $cur → 目标 $goal（知识点 ${kp.ifBlank { "自动扫描 1~32768" }}，每局 $lim 题）"
        resultMsg = null
        ScorePump.pumpToTarget(
            keyPointId = kp,
            limit = lim,
            intervalMs = iv,
            target = goal,
            onProgress = { now, rounds ->
                mainHandler.post {
                    pumpProgress = if (now < 0) "扫描知识点中（1~32768 自动尝试，可随时停止）…"
                    else "第 $rounds 局 · 当前 $now / $goal"
                }
            },
            onDone = { r ->
                mainHandler.post {
                    pumping = false
                    r.onSuccess { finalScore ->
                        pumpProgress = ""
                        resultMsg = "成功：已刷到 $finalScore 分"
                        updateCurrentScore()
                    }.onFailure { th ->
                        pumpProgress = ""
                        resultMsg = "已停止/失败：${th.message ?: th}"
                        updateCurrentScore()
                    }
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("自定义分数") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.Arrow_back,
                            contentDescription = "返回",
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = when (val s = loadState) {
                    ScoreLoadState.Loading -> "当前分数：加载中"
                    ScoreLoadState.Uninitialized -> "当前分数：未初始化"
                    is ScoreLoadState.Error -> "当前分数：读取失败"
                    is ScoreLoadState.Success -> "当前分数：${s.score}"
                },
                style = MaterialTheme.typography.bodyLarge
            )
            when (val s = loadState) {
                ScoreLoadState.Uninitialized -> Text(
                    text = "当前为模块本体独立运行，未接入宿主 ApiService，无法读取分数。\n" +
                        "请在小猿口算（宿主）内打开「老挂戏老叟设置」使用此功能。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
                is ScoreLoadState.Error -> Text(
                    text = "读取分数失败：${s.message}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
                else -> Unit
            }

            Text("模式", style = MaterialTheme.typography.titleMedium)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        scoreMode = 0
                        SettingsPrefs.writeInt("custom_score_mode", 0)
                    }
            ) {
                RadioButton(
                    selected = scoreMode == 0,
                    onClick = {
                        scoreMode = 0
                        SettingsPrefs.writeInt("custom_score_mode", 0)
                    }
                )
                Column {
                    Text("刷分模式（增量）", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "每次 +N 经验（登录参与接口，服务端每天约限 3 次，超出被拒）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        scoreMode = 1
                        SettingsPrefs.writeInt("custom_score_mode", 1)
                    }
            ) {
                RadioButton(
                    selected = scoreMode == 1,
                    onClick = {
                        scoreMode = 1
                        SettingsPrefs.writeInt("custom_score_mode", 1)
                    }
                )
                Column {
                    Text("真自定义分数（练习上传刷到目标）", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "走练习成绩上传主接口（无日限），循环全对上传直到分数 ≥ 目标。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (scoreMode == 0) {
                // ---- 刷分模式（保留旧逻辑，postSavedExp 增量）----
                Text(
                    text = if (suppose != null) "预计目标分数：$suppose" else "预计目标分数：${currentScore?.toString() ?: "加载中"}",
                    style = MaterialTheme.typography.bodyLarge
                )
                OutlinedTextField(
                    value = target,
                    onValueChange = {
                        target = it.filter { c -> c.isDigit() || c == '-' }
                        val cur = currentScore
                        val obtain = target.toLongOrNull()
                            ?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                        suppose = if (cur != null && obtain != null) (cur + obtain.toInt()).toString() else null
                    },
                    label = { Text("请输入刷取分数") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    enabled = currentScore != null && target.toLongOrNull() != null && !submitting,
                    onClick = {
                        val cur = currentScore ?: return@Button
                        val obtain = target.toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                        target = ""
                        submitting = true
                        resultMsg = null
                        LegacyApiService.postSavedExp(obtain.toInt()) {
                            submitting = false
                            it.onSuccess {
                                updateCurrentScore()
                            }.onFailure { th ->
                                logI(th)
                                resultMsg = "提交失败：${th.message ?: th}\n（该接口服务端每天约限 3 次，超限会拒绝）"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (submitting) "提交中…" else "确认刷分")
                }
            } else {
                // ---- 真自定义分数：练习批量上传刷到目标 ----
                val apiReady = LegacyApiService.isReady() && OralApiService.isReady()
                Text(
                    text = if (!apiReady) "宿主 ApiService 未初始化，请在宿主内打开本面板"
                    else "输入目标分数（须大于当前），开始后循环全对上传练习成绩直到达到目标",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (apiReady) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error
                )
                OutlinedTextField(
                    value = directTarget,
                    onValueChange = {
                        directTarget = it.filter { c -> c.isDigit() }
                        resultMsg = null
                    },
                    label = { Text("目标分数") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                val kpOptions = remember { ScorePump.loadKeypointOptions() }
                if (kpOptions.isNotEmpty()) {
                    Text("知识点（来自宿主首页推荐 /leo-math/android/recommend/keypoint）", style = MaterialTheme.typography.titleSmall)
                    Column(modifier = Modifier.fillMaxWidth()) {
                        val current = kpOptions.firstOrNull { it.first == keyPointId.trim() }
                        Text(
                            text = "当前选择：" + (current?.let { "${it.second}（id=${it.first}，${it.third} 题）" }
                                ?: keyPointId.ifBlank { "未选择（将自动使用/扫描）" }),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        kpOptions.take(20).forEach { (id, name, cnt) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        keyPointId = id
                                        SettingsPrefs.writeString("custom_score_keypoint", id)
                                        if (cnt > 0) {
                                            limit = cnt.toString()
                                            SettingsPrefs.writeString("custom_score_limit", cnt.toString())
                                        }
                                    }
                            ) {
                                RadioButton(selected = keyPointId.trim() == id, onClick = {
                                    keyPointId = id
                                    SettingsPrefs.writeString("custom_score_keypoint", id)
                                })
                                Column {
                                    Text(name.ifBlank { "知识点 $id" }, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "id=$id · $cnt 题/局",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        "尚未捕获到知识点列表：请先在小猿口算首页停留几秒（会拉取 /recommend/keypoint），再回来刷新。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedTextField(
                    value = keyPointId,
                    onValueChange = {
                        keyPointId = it.filter { c -> c.isDigit() }
                        SettingsPrefs.writeString("custom_score_keypoint", it)
                    },
                    label = { Text("知识点 ID（可手填覆盖）") },
                    placeholder = { Text("留空 = 自动使用推荐/自动扫描") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = limit,
                    onValueChange = {
                        limit = it.filter { c -> c.isDigit() }
                        SettingsPrefs.writeString("custom_score_limit", it)
                    },
                    label = { Text("每局题目数（默认按推荐 10 题）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = intervalMs,
                    onValueChange = {
                        intervalMs = it.filter { c -> c.isDigit() }
                        SettingsPrefs.writeString("custom_score_interval", it)
                    },
                    label = { Text("每局间隔毫秒（默认 2000）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (pumping) {
                    OutlinedButton(
                        onClick = {
                            ScorePump.cancel()
                            resultMsg = "已请求停止…"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("停止刷分")
                    }
                } else {
                    Button(
                        enabled = currentScore != null && directTarget.toIntOrNull() != null && apiReady,
                        onClick = { startPump() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("开始刷到目标分数")
                    }
                }
                if (pumpProgress.isNotEmpty()) {
                    Text(
                        text = pumpProgress,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    text = "说明：循环「取卷子→全对→上传（/leo-math/android/exams/v2）」刷分，与「自动上分」同链路，" +
                        "无登录参与接口的每天 3 次限制。知识点 ID 可留空——取题失败会自动从 1 遍历到 2^15(32768) " +
                        "找有效知识点并记录；每局经验由服务端按题数计算，可随时停止。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            resultMsg?.let {
                Text(
                    text = it,
                    color = if (it.startsWith("成功")) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
