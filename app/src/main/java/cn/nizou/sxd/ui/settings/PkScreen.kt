package cn.nizou.sxd.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cn.nizou.sxd.entities.AutoAnswerMode
import cn.nizou.sxd.ui.components.M3BackButton
import cn.nizou.sxd.ui.components.M3ListScaffold
import cn.nizou.sxd.ui.components.SegmentedColumn
import cn.nizou.sxd.ui.components.SwitchWidget
import cn.nizou.sxd.ui.components.TextFieldDialogWidget
import cn.nizou.sxd.util.SettingsPrefs
import cn.nizou.sxd.util.StringRes

/** PK 自动化入口：对齐 Simian，仅保留快速答题与统一循环 PK 控制。 */
@Composable
fun PkScreen(res: StringRes, onBack: () -> Unit) {
    var quickAnswer by remember {
        mutableStateOf(
            SettingsPrefs.readString(res, res.KEY_AUTO_ANSWER_CONFIG, "0").toIntOrNull()
                ?.let { AutoAnswerMode.entries.getOrElse(it) { AutoAnswerMode.DISABLE } in setOf(AutoAnswerMode.STANDARD, AutoAnswerMode.QUICK) }
                ?: false,
        )
    }
    var loopPk by remember { mutableStateOf(SettingsPrefs.readBoolean(res, res.KEY_PK_CYCLIC, false)) }
    var loopInterval by remember { mutableStateOf(SettingsPrefs.readString(res, res.KEY_PK_CYCLIC_INTERVAL, "1500")) }

    M3ListScaffold(title = "PK 自动化", navigationIcon = { M3BackButton(onClick = onBack) }) {
        item {
            SegmentedColumn(title = "快速答题") {
                SwitchWidget(
                    title = "快速答题",
                    description = "进入 PK 后自动完成答题并提交；关闭后保持手动答题。",
                    checked = quickAnswer,
                    onCheckedChange = { enabled ->
                        quickAnswer = enabled
                        // Simian-style fast answer: retain the general answer flow and leave retired PK fast-settlement patches off.
                        val mode = if (enabled) AutoAnswerMode.STANDARD else AutoAnswerMode.DISABLE
                        SettingsPrefs.writeString(res, res.KEY_AUTO_ANSWER_CONFIG, mode.ordinal.toString())
                    },
                )
            }
        }
        item {
            SegmentedColumn(title = "循环 PK") {
                SwitchWidget(
                    title = "循环 PK",
                    description = "统一处理结果页的「开心收下」「继续」和「继续 PK」，然后进入下一局。",
                    checked = loopPk,
                    onCheckedChange = { loopPk = it; SettingsPrefs.writeBoolean(res, res.KEY_PK_CYCLIC, it) },
                )
                TextFieldDialogWidget(
                    title = "循环间隔", value = loopInterval, placeholder = "单位毫秒，默认值 1500", enabled = loopPk,
                    keyboardType = KeyboardType.Number, filter = { value -> value.filter(Char::isDigit) },
                    onValueChange = { loopInterval = it; SettingsPrefs.writeString(res, res.KEY_PK_CYCLIC_INTERVAL, it) },
                )
            }
        }
        item { Box(Modifier.padding(24.dp)) }
    }
}
