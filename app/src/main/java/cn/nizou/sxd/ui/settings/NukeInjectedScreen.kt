package cn.nizou.sxd.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import cn.nizou.sxd.ui.content.nukex.NukeModuleTheme
import cn.nizou.sxd.ui.content.nukex.NukePageScaffold
import cn.nizou.sxd.ui.content.nukex.NukePreferenceRow
import cn.nizou.sxd.ui.content.nukex.NukeSettingGroup
import cn.nizou.sxd.ui.content.nukex.NukeSwitchRow
import cn.nizou.sxd.ui.theme.SettingsUiEngine
import cn.nizou.sxd.ui.theme.ThemeSettings
import cn.nizou.sxd.util.LogOverlayWindow
import cn.nizou.sxd.util.SettingsPrefs
import cn.nizou.sxd.util.SimianV2AutomationPrefs

/** Full Nuke root: every injected element stays in the Nuke component tree. */
@Composable
fun NukeInjectedScreen(onFinish: () -> Unit) {
    var page by remember { mutableStateOf(NukePage.HOME) }
    NukeModuleTheme(dark = ThemeSettings.themeMode.resolve(), accent = Color(ThemeSettings.seedColor)) {
        NukePageScaffold(title = page.title, onBack = { if (page == NukePage.HOME) onFinish() else page = NukePage.HOME }) {
            item {
                Column {
                    when (page) {
                        NukePage.HOME -> NukeHome { page = it }
                        NukePage.AUTOMATION -> NukeAutomation()
                        NukePage.DEBUG -> NukeDebug()
                        NukePage.GENERAL -> NukeGeneral()
                        NukePage.CUSTOM -> NukeCustomFeatures()
                        NukePage.APPEARANCE -> NukeAppearance()
                    }
                }
            }
        }
    }
}

private enum class NukePage(val title: String) { HOME("老挂戏老叟"), GENERAL("通用"), AUTOMATION("SimianV2 自动化"), CUSTOM("自定义功能"), DEBUG("调试"), APPEARANCE("界面") }

private fun frameworkStatus(): String = runCatching {
    val companion = Class.forName("cn.nizou.sxd.XposedInit\$Companion")
    val self = companion.getField("self").get(null) ?: return "未检测到注入框架"
    val type = self.javaClass
    val name = type.methods.first { it.name == "getFrameworkName" && it.parameterCount == 0 }.invoke(self)
    val api = type.methods.first { it.name == "getApiVersion" && it.parameterCount == 0 }.invoke(self)
    "$name · API $api（LSPosed / npatch）"
}.getOrDefault("未检测到注入框架（LSPosed / npatch）")

@Composable private fun NukeHome(open: (NukePage) -> Unit) {
    NukeSettingGroup("模块") {
        NukePreferenceRow("注入环境", frameworkStatus())
        NukePreferenceRow("通用", "正确识别与昵称设置", onClick = { open(NukePage.GENERAL) })
        NukePreferenceRow("SimianV2 自动化", "正确答案、自动笔画、开心收下、继续、继续 PK", onClick = { open(NukePage.AUTOMATION) })
        NukePreferenceRow("自定义功能", "答案、题目、结算时间与分数", onClick = { open(NukePage.CUSTOM) })
        NukePreferenceRow("调试与日志", "实时日志悬浮窗与抓包", onClick = { open(NukePage.DEBUG) })
        NukePreferenceRow("界面", "主题与 UI 引擎", onClick = { open(NukePage.APPEARANCE) })
    }
}

@Composable private fun NukeAutomation() {
    var quick by remember { mutableStateOf(SettingsPrefs.readBoolean(SimianV2AutomationPrefs.QUICK_ANSWER, false)) }
    var stroke by remember { mutableStateOf(SettingsPrefs.readBoolean(SimianV2AutomationPrefs.AUTO_ANSWER, false)) }
    var happy by remember { mutableStateOf(SettingsPrefs.readBoolean(SimianV2AutomationPrefs.HAPPY_ACCEPT, false)) }
    var continueMatch by remember { mutableStateOf(SettingsPrefs.readBoolean(SimianV2AutomationPrefs.CONTINUE, false)) }
    var continuePk by remember { mutableStateOf(SettingsPrefs.readBoolean(SimianV2AutomationPrefs.CONTINUE_PK, false)) }
    NukeSettingGroup("答题") {
        NukeSwitchRow("一切输入视为正确答案", "DexKit 定位 SimianV2 识别方法", quick) { quick = it; SettingsPrefs.writeBoolean(SimianV2AutomationPrefs.QUICK_ANSWER, it) }
        NukeSwitchRow("自动提交笔画", "进入 PK 后按 SimianV2 画板链路提交", stroke) { stroke = it; SettingsPrefs.writeBoolean(SimianV2AutomationPrefs.AUTO_ANSWER, it) }
    }
    NukeSettingGroup("结果页") {
        NukeSwitchRow("自动点击开心收下", null, happy) { happy = it; SettingsPrefs.writeBoolean(SimianV2AutomationPrefs.HAPPY_ACCEPT, it) }
        NukeSwitchRow("自动点击继续", null, continueMatch) { continueMatch = it; SettingsPrefs.writeBoolean(SimianV2AutomationPrefs.CONTINUE, it) }
        NukeSwitchRow("自动点击继续 PK", null, continuePk) { continuePk = it; SettingsPrefs.writeBoolean(SimianV2AutomationPrefs.CONTINUE_PK, it) }
    }
}


@Composable private fun NukeGeneral() {
    var alwaysTrue by remember { mutableStateOf(SettingsPrefs.readBoolean("always_true_answer", true)) }
    var nickname by remember { mutableStateOf(SettingsPrefs.readBoolean("remove_restriction_on_nickname", true)) }
    NukeSettingGroup("通用") {
        NukeSwitchRow("一切输入视为正确答案", "旧识别链路兼容开关", alwaysTrue) { alwaysTrue = it; SettingsPrefs.writeBoolean("always_true_answer", it) }
        NukeSwitchRow("无视名字限制", "放开昵称长度与字符限制", nickname) { nickname = it; SettingsPrefs.writeBoolean("remove_restriction_on_nickname", it) }
    }
}

@Composable private fun NukeCustomFeatures() {
    var answer by remember { mutableStateOf(SettingsPrefs.readBoolean("modify_answer", false)) }
    var title by remember { mutableStateOf(SettingsPrefs.readBoolean("modify_title", false)) }
    var settle by remember { mutableStateOf(SettingsPrefs.readBoolean("pk_settle_enabled", false)) }
    NukeSettingGroup("自定义答案") {
        NukeSwitchRow("改答案", "使用自定义答案配置", answer) { answer = it; SettingsPrefs.writeBoolean("modify_answer", it) }
        NukeSwitchRow("改题目", "使用自定义题目配置", title) { title = it; SettingsPrefs.writeBoolean("modify_title", it) }
    }
    NukeSettingGroup("结算与分数") {
        NukeSwitchRow("自定义结算时间", "对战提交使用自定义 costTime", settle) { settle = it; SettingsPrefs.writeBoolean("pk_settle_enabled", it) }
        NukePreferenceRow("自定义分数", "复用既有分数功能配置")
    }
}

@Composable private fun NukeDebug() {
    val context = LocalContext.current
    var overlay by remember { mutableStateOf(SettingsPrefs.readBoolean("log_overlay_enabled", false)) }
    var debug by remember { mutableStateOf(SettingsPrefs.readBoolean("debug", false)) }
    NukeSettingGroup("运行") {
        NukeSwitchRow("实时日志悬浮窗", "跟随宿主 Activity，返回后自动重挂", overlay) { enabled -> overlay = enabled; LogOverlayWindow.setEnabled(context, enabled) }
        NukeSwitchRow("DEBUG", "输出额外 Hook 运行日志", debug) { debug = it; SettingsPrefs.writeBoolean("debug", it) }
    }
}

@Composable private fun NukeAppearance() {
    NukeSettingGroup("界面") {
        NukePreferenceRow("UI 组件引擎", "Nuke；点击返回 Material 3", onClick = { ThemeSettings.updateUiEngine(SettingsUiEngine.MATERIAL3) })
        NukePreferenceRow("主题模式", ThemeSettings.themeMode.displayName)
        NukePreferenceRow("页面转场", ThemeSettings.pageTransitionAnimation.name)
    }
}
