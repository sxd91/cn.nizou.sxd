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
import cn.nizou.sxd.ui.components.M3BackButton
import cn.nizou.sxd.ui.components.M3ListScaffold
import cn.nizou.sxd.ui.components.SegmentedColumn
import cn.nizou.sxd.ui.components.SwitchWidget
import cn.nizou.sxd.ui.components.TextFieldDialogWidget
import cn.nizou.sxd.util.SettingsPrefs
import cn.nizou.sxd.util.SimianV2AutomationPrefs

/** Full SimianV2 automation surface: quick answer, stroke submission and three independent page actions. */
@Composable
fun SimianV2AutomationScreen(onBack: () -> Unit) {
    var quickAnswer by remember { mutableStateOf(SettingsPrefs.readBoolean(SimianV2AutomationPrefs.QUICK_ANSWER, false)) }
    var autoAnswer by remember { mutableStateOf(SettingsPrefs.readBoolean(SimianV2AutomationPrefs.AUTO_ANSWER, false)) }
    var delay by remember { mutableStateOf(SettingsPrefs.readString(SimianV2AutomationPrefs.AUTO_ANSWER_DELAY, "8500")) }
    var happyAccept by remember { mutableStateOf(SettingsPrefs.readBoolean(SimianV2AutomationPrefs.HAPPY_ACCEPT, false)) }
    var continueMatch by remember { mutableStateOf(SettingsPrefs.readBoolean(SimianV2AutomationPrefs.CONTINUE, false)) }
    var continuePk by remember { mutableStateOf(SettingsPrefs.readBoolean(SimianV2AutomationPrefs.CONTINUE_PK, false)) }

    M3ListScaffold(title = "SimianV2 自动化", navigationIcon = { M3BackButton(onClick = onBack) }) {
        item {
            SegmentedColumn(title = "答题") {
                SwitchWidget(
                    title = "一切输入视为正确答案",
                    description = "DexKit 定位识别方法，返回宿主题目提供的正确答案",
                    checked = quickAnswer,
                    onCheckedChange = { quickAnswer = it; SettingsPrefs.writeBoolean(SimianV2AutomationPrefs.QUICK_ANSWER, it) },
                )
                SwitchWidget(
                    title = "自动提交笔画",
                    description = "进入 PK 后按 SimianV2 画板链路提交固定笔画",
                    checked = autoAnswer,
                    onCheckedChange = { autoAnswer = it; SettingsPrefs.writeBoolean(SimianV2AutomationPrefs.AUTO_ANSWER, it) },
                )
                TextFieldDialogWidget(
                    title = "自动答题等待", value = delay, placeholder = "单位毫秒，默认 8500", enabled = autoAnswer,
                    keyboardType = KeyboardType.Number, filter = { value -> value.filter(Char::isDigit) },
                    onValueChange = { delay = it; SettingsPrefs.writeString(SimianV2AutomationPrefs.AUTO_ANSWER_DELAY, it) },
                )
            }
        }
        item {
            SegmentedColumn(title = "结果页自动化") {
                SwitchWidget(title = "自动点击开心收下", description = "结算奖励页显示后自动点击", checked = happyAccept, onCheckedChange = { happyAccept = it; SettingsPrefs.writeBoolean(SimianV2AutomationPrefs.HAPPY_ACCEPT, it) })
                SwitchWidget(title = "自动点击继续", description = "奖励页显示继续按钮后自动点击", checked = continueMatch, onCheckedChange = { continueMatch = it; SettingsPrefs.writeBoolean(SimianV2AutomationPrefs.CONTINUE, it) })
                SwitchWidget(title = "自动点击继续 PK", description = "PK 结果页显示继续 PK 后自动点击", checked = continuePk, onCheckedChange = { continuePk = it; SettingsPrefs.writeBoolean(SimianV2AutomationPrefs.CONTINUE_PK, it) })
            }
        }
        item { Box(Modifier.padding(24.dp)) }
    }
}
