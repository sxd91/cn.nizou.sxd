package cn.nizou.sxd.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import cn.nizou.sxd.BuildConfig
import cn.nizou.sxd.ui.content.nukex.NukeModuleTheme
import cn.nizou.sxd.ui.content.nukex.NukePageScaffold
import cn.nizou.sxd.ui.content.nukex.NukePreferenceRow
import cn.nizou.sxd.ui.content.nukex.NukeSettingGroup
import cn.nizou.sxd.ui.content.nukex.NukeSwitchRow
import cn.nizou.sxd.ui.theme.AppColorSpec
import cn.nizou.sxd.ui.theme.AppPaletteStyle
import cn.nizou.sxd.ui.theme.AppThemeMode
import cn.nizou.sxd.ui.theme.PageTransitionAnimation
import cn.nizou.sxd.ui.theme.SettingsUiEngine
import cn.nizou.sxd.ui.theme.ThemeSettings
import cn.nizou.sxd.util.ConfigActions
import cn.nizou.sxd.util.openGithub

/** WeKit Nuke-engine settings surface using the same persisted settings as Material 3. */
@Composable
fun NukeSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val dark = ThemeSettings.themeMode.resolve()
    NukeModuleTheme(dark = dark, accent = Color(ThemeSettings.seedColor)) {
        NukePageScaffold(title = "设置", onBack = onBack) {
            item {
                NukeSettingGroup("界面") {
                    NukePreferenceRow("UI 组件引擎", "Nuke", onClick = { ThemeSettings.updateUiEngine(SettingsUiEngine.MATERIAL3) })
                    NukePreferenceRow("主题模式", ThemeSettings.themeMode.displayName, onClick = { ThemeSettings.updateThemeMode(ThemeSettings.themeMode.next()) })
                    NukeSwitchRow("预见性返回动画", "为模块页面启用系统预见性返回", ThemeSettings.predictiveBackEnabled, ThemeSettings::updatePredictiveBackEnabled)
                    NukePreferenceRow("页面过渡动画", ThemeSettings.pageTransitionAnimation.name, onClick = { ThemeSettings.updatePageTransitionAnimation(ThemeSettings.pageTransitionAnimation.next()) })
                    NukeSwitchRow("动态壁纸取色", "使用系统壁纸强调色作为种子", ThemeSettings.dynamicWallpaper, ThemeSettings::updateDynamicWallpaper)
                    NukeSwitchRow("陀螺仪光效", "底栏指示器高光随设备倾斜旋转", ThemeSettings.gravityHighlight, ThemeSettings::updateGravityHighlight)
                    NukePreferenceRow("调色板样式", ThemeSettings.paletteStyle.displayName, onClick = { ThemeSettings.updatePaletteStyle(ThemeSettings.paletteStyle.next()) })
                    NukePreferenceRow("颜色规格", ThemeSettings.effectiveColorSpec.displayName, onClick = { ThemeSettings.updateColorSpec(ThemeSettings.effectiveColorSpec.next()) })
                }
            }
            item { NukeSettingGroup("配置") {
                NukePreferenceRow("导出配置", "将全部设置保存为 JSON 文件", onClick = { ConfigActions.export(context) })
                NukePreferenceRow("导入配置", "从 JSON 文件恢复设置（覆盖当前全部设置）", onClick = { ConfigActions.importFromDocument(context) })
            } }
            item { NukeSettingGroup("关于") {
                NukePreferenceRow("版本", "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                NukePreferenceRow("Github", "github.com/sxd91/nizou", onClick = { context.openGithub() })
            } }
        }
    }
}
private fun AppThemeMode.next() = entries[(ordinal + 1) % entries.size]
private fun PageTransitionAnimation.next() = entries[(ordinal + 1) % entries.size]
private fun AppPaletteStyle.next() = entries[(ordinal + 1) % entries.size]
private fun AppColorSpec.next() = entries[(ordinal + 1) % entries.size]
