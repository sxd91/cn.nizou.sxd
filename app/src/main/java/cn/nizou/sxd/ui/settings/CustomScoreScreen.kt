package cn.nizou.sxd.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cn.nizou.sxd.api.LegacyApiService
import cn.nizou.sxd.api.ScorePump
import cn.nizou.sxd.util.ScoreLog
import cn.nizou.sxd.util.SettingsPrefs
import cn.nizou.sxd.util.XposedHelpers
import cn.nizou.sxd.util.logI
import cn.nizou.sxd.util.mainHandler
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Arrow_back

/**
 * 当前分数加载状态机。
 *
 * [LegacyApiService] 只在宿主进程由 `SettingHook` 的 init()+setup() 初始化；
 * 模块独立 App（模块本体）是**独立进程**，不注入宿主 → 永远未初始化，
 * 这里据此给出明确提示，而不是卡在「加载中」。
 */
private sealed interface ScoreLoadState {
    data object Loading : ScoreLoadState
    data object Uninitialized : ScoreLoadState
    data class Success(val score: Int) : ScoreLoadState
    data class Error(val message: String) : ScoreLoadState
}

/**
 * 「自定义分数」面板 —— **只有一条链路：经验增量上报**（2026-10-01 重写）。
 *
 * ## 为什么砍掉了「模式」选择
 *
 * 旧界面有「刷分模式（增量）」与「真自定义（整卷上传）」两个单选。后者走
 * `PUT /leo-math/android/exams/v2/{examId}`，真机**每局 HTTP 400**（取证结论：
 * `curTrueAnswer` 等字段在安卓 `QuestionVO` 里根本不存在，宿主自己发也 400）。
 * 两个模式并存只会让人误以为「逻辑混乱」，且其中一条是死的 —— 直接删掉。
 *
 * 现在与**老挂（cn.apixiaoyuan.app）和 pk-node 完全同一套语义**：
 *
 * ```
 * POST /leo-star/android/exercise/rank/login/attend      (宿主 postSavedExp)
 * body: {todayExercises:[{finishTime, obtainExp, ruleType}]}
 * ```
 *
 * 逐步上报 `ruleType 0 / 1`、每次 `obtainExp=200`，**以服务端 `curWeekScore` 差值为准**。
 * 日上限 400（每个 ruleType 每天只记一次）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomScoreScreen(onBack: () -> Unit) {
    var loadState by remember { mutableStateOf<ScoreLoadState>(ScoreLoadState.Loading) }
    var target by remember { mutableStateOf("") }
    var intervalMs by remember {
        mutableStateOf(SettingsPrefs.readString("custom_score_interval", "2000"))
    }
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
        val goal = target.toIntOrNull() ?: return
        if (goal <= cur) {
            resultMsg = "目标分数必须大于当前分数（$cur）"
            return
        }
        pumping = true
        pumpProgress = "上报中：当前 $cur → 目标 $goal"
        resultMsg = null
        ScorePump.pumpToTarget(
            keyPointId = "",
            limit = 0,
            intervalMs = intervalMs.toLongOrNull()?.coerceIn(0, 60_000) ?: 2000L,
            target = goal,
            onProgress = { now, reported ->
                mainHandler.post {
                    pumpProgress = if (now < 0) "读取分数中…" else "已上报 $reported 次 · 当前 $now / $goal"
                }
            },
            onDone = { r ->
                mainHandler.post {
                    pumping = false
                    pumpProgress = ""
                    r.onSuccess { finalScore ->
                        resultMsg = "成功：已达到 $finalScore 分"
                    }.onFailure { th ->
                        resultMsg = th.message ?: th.toString()
                    }
                    updateCurrentScore()
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("自定义分数") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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

            Text(
                text = "走宿主的「经验上报」接口（rank/login/attend）—— 与老挂、pk-node 同一条链路，" +
                    "签名/设备链/登录态全由宿主自己带。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = target,
                onValueChange = {
                    target = it.filter { c -> c.isDigit() }
                    resultMsg = null
                },
                label = { Text("目标分数（须大于当前）") },
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
                label = { Text("两次上报间隔毫秒（默认 2000）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            val apiReady = LegacyApiService.isReady()
            if (pumping) {
                OutlinedButton(
                    onClick = {
                        ScorePump.cancel()
                        resultMsg = "已请求停止…"
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("停止上报")
                }
            } else {
                Button(
                    enabled = currentScore != null && target.toIntOrNull() != null && apiReady,
                    onClick = { startPump() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("开始上报")
                }
            }

            if (pumpProgress.isNotEmpty()) {
                Text(
                    text = pumpProgress,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            // ★ 运行日志框：实时显示上报链路（对齐老挂/pk-node 的日志粒度）。
            ScoreRunLogBox(pumping)

            Text(
                text = "说明：服务端按 ruleType 记账，同一个 ruleType **每天只记一次**；" +
                    "实测可记账的只有 0 与 1，单次最多记 200 经验 —— 因此**日上限 400**。" +
                    "达到上限后服务端返回成功但分数不动（不是 bug）。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )

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

/**
 * 「自定义分数」运行日志框（对齐老挂的日志区：自动跟随到底部）。
 *
 * - 每 400ms 从 [ScoreLog] 拉快照（上报在工作线程跑，轮询最简单可靠）；
 * - 新日志到来时自动滚到底；用户上滑查看历史时暂停跟随，滑回底部恢复；
 * - 顶部工具条：标题 + 条数 + 清空。
 */
@Composable
private fun ScoreRunLogBox(running: Boolean) {
    var lines by remember { mutableStateOf(ScoreLog.snapshot()) }
    var autoFollow by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    LaunchedEffect(running) {
        while (true) {
            lines = ScoreLog.snapshot()
            kotlinx.coroutines.delay(400)
        }
    }

    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow { listState.layoutInfo }
            .collect { info ->
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                autoFollow = last >= info.totalItemsCount - 2
            }
    }

    LaunchedEffect(lines.size, autoFollow) {
        if (autoFollow && lines.isNotEmpty()) {
            listState.animateScrollToItem(lines.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("运行日志", style = MaterialTheme.typography.titleSmall)
            Text(
                "  ${lines.size} 条",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box(Modifier.weight(1f))
            OutlinedButton(onClick = { ScoreLog.clear(); lines = emptyList() }) { Text("清空") }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp, max = 320.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    RoundedCornerShape(10.dp)
                )
                .padding(8.dp)
        ) {
            if (lines.isEmpty()) {
                Text(
                    "（暂无日志：点「开始上报」后这里会实时显示每一步）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(state = listState) {
                    itemsIndexed(lines) { _, line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}