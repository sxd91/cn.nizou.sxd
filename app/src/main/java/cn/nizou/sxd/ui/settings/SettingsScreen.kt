package cn.nizou.sxd.ui.settings

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.toColorInt
import cn.nizou.sxd.BuildConfig
import cn.nizou.sxd.ui.components.BaseWidget
import cn.nizou.sxd.ui.components.ColorPickerWidget
import cn.nizou.sxd.ui.components.DropDownMenuWidget
import cn.nizou.sxd.ui.components.DropdownOption
import cn.nizou.sxd.ui.components.M3BackButton
import cn.nizou.sxd.ui.components.M3ListScaffold
import cn.nizou.sxd.ui.components.SegmentedColumn
import cn.nizou.sxd.ui.components.SwitchWidget
import cn.nizou.sxd.ui.content.FloatingBottomBarMode
import cn.nizou.sxd.ui.theme.AppColorSpec
import cn.nizou.sxd.ui.theme.AppPaletteStyle
import cn.nizou.sxd.ui.theme.AppThemeMode
import cn.nizou.sxd.ui.theme.PageTransitionAnimation
import cn.nizou.sxd.ui.theme.SettingsUiEngine
import cn.nizou.sxd.ui.theme.ThemeSettings
import cn.nizou.sxd.util.ConfigActions
import cn.nizou.sxd.util.openGithub
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Brightness_medium
import com.composables.icons.materialsymbols.outlined.Build_circle
import com.composables.icons.materialsymbols.outlined.Colorize
import com.composables.icons.materialsymbols.outlined.Contrast
import com.composables.icons.materialsymbols.outlined.File_download
import com.composables.icons.materialsymbols.outlined.File_upload
import com.composables.icons.materialsymbols.outlined.Label
import com.composables.icons.materialsymbols.outlined.Screen_rotation
import com.composables.icons.materialsymbols.outlined.Style
import com.composables.icons.materialsymbols.outlined.Swipe
import com.composables.icons.materialsymbols.outlined.Wallpaper

/**
 * 设置页（对齐 WeKit `SettingsPager` 的 ThemeSection，完整移植到小猿口算模块）。
 *
 * 与 wekit 的差异：
 *  - 无 i18n 体系，「语言」行砍掉（死 UI 不做），「界面」组从「UI 组件引擎」开始；
 *  - `SettingsUiEngine` 只有 MATERIAL3 一套引擎，UI 引擎行用只读 [BaseWidget] 展示
 *    「Material 3」（wekit 是下拉），为将来扩展留位；
 *  - 无 wekit 的「同时对微信生效」开关（本项目注入宿主面板与本体共用同一套配色）；
 *  - seed 取色行用 t3 移植的 [ColorPickerWidget]（自绘 HSV+HEX 取色器），wekit 用的是
 *    简化版 HSV 三滑杆对话框；
 *  - 文案全部硬编码中文（对齐项目既有设置页做法，wekit 用 stringResource）。
 *
 * 所有 [ThemeSettings] 值都是 Compose 可观察状态：改一行即时换肤；「种子颜色」行在
 * 「动态壁纸取色」开启时用普通 `if` 隐藏（本项目 SegmentedColumn 是 ColumnScope 无
 * wekit 的 item(animatedVisibility=...) DSL，用 if 等价）。
 *
 * @param onBack 返回上一级（t1 导航落地后由 MainPagerScreen 委托）。
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    M3ListScaffold(
        title = "设置",
        navigationIcon = { M3BackButton(onClick = onBack) },
    ) {
        item {
            ThemeSection()
        }

        // 配置（对齐 WeKit SettingsPager「配置」区：导出/导入 JSON；放在「关于」上方）
        item {
            ConfigSection()
        }

        item {
            SegmentedColumn(title = "关于") {
                BaseWidget(
                    icon = MaterialSymbols.Outlined.Label,
                    title = "版本",
                    description = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                )
                BaseWidget(
                    icon = MaterialSymbols.Outlined.Build_circle,
                    title = "构建时间",
                    description = BuildConfig.BUILD_TIMESTAMP.takeIf { it > 0 }?.let { formatBuildTime(it) } ?: "—",
                )
                BaseWidget(
                    title = "Github",
                    description = "github.com/sxd91/nizou",
                    onClick = { context.openGithub() },
                )
            }
        }
    }
}

/** Palette 风格双语标签（对齐 wekit `palette_style_bilingual_format`：中文 (English)）。 */
private val paletteStyleLabels = mapOf(
    AppPaletteStyle.TONAL_SPOT to "色调点 (Tonal Spot)",
    AppPaletteStyle.NEUTRAL to "中性 (Neutral)",
    AppPaletteStyle.VIBRANT to "鲜艳 (Vibrant)",
    AppPaletteStyle.EXPRESSIVE to "表现力 (Expressive)",
    AppPaletteStyle.RAINBOW to "彩虹 (Rainbow)",
    AppPaletteStyle.FRUIT_SALAD to "水果沙拉 (Fruit Salad)",
    AppPaletteStyle.MONOCHROME to "单色 (Monochrome)",
    AppPaletteStyle.FIDELITY to "保真 (Fidelity)",
    AppPaletteStyle.CONTENT to "内容 (Content)",
)

/** 页面过渡动画标签（对齐 wekit 文案：AOSP / Miuix）。 */
private val pageTransitionLabels = mapOf(
    PageTransitionAnimation.AOSP to "AOSP",
    PageTransitionAnimation.MIUIX to "Miuix",
)

/** 底栏效果模式标签。 */
private val bottomBarModeLabels = mapOf(
    FloatingBottomBarMode.LiquidGlass to "液态玻璃 (Liquid Glass)",
    FloatingBottomBarMode.Blur to "毛玻璃 (Blur)",
    FloatingBottomBarMode.None to "纯色 (None)",
)

/**
 * 「界面」设置组（照 wekit `SettingsPager.ThemeSection` 行序）：
 * UI 组件引擎 → 主题模式 → 预见性返回动画 → 页面过渡动画 → 动态壁纸取色 →
 * 种子颜色（壁纸取色开启时隐藏）→ 调色板样式 → 颜色规格。
 */
@Composable
private fun ThemeSection() {
    val context = LocalContext.current

    SegmentedColumn(title = "界面") {
        DropDownMenuWidget(
            icon = MaterialSymbols.Outlined.Style,
            title = "UI 组件引擎",
            description = "切换设置页组件引擎",
            value = ThemeSettings.uiEngine,
            options = SettingsUiEngine.entries.map { DropdownOption(it, it.displayName) },
            onValueChange = ThemeSettings::updateUiEngine,
        )

        DropDownMenuWidget(
            icon = MaterialSymbols.Outlined.Brightness_medium,
            title = "主题模式",
            description = null,
            value = ThemeSettings.themeMode,
            options = AppThemeMode.entries.map { DropdownOption(it, it.displayName) },
            onValueChange = ThemeSettings::updateThemeMode,
        )

        SwitchWidget(
            icon = MaterialSymbols.Outlined.Swipe,
            title = "预见性返回动画",
            description = "为模块页面启用系统预见性返回",
            checked = ThemeSettings.predictiveBackEnabled,
            onCheckedChange = { enabled ->
                ThemeSettings.updatePredictiveBackEnabled(enabled)
                // 该 flag 是进程启动级，需重启宿主面板/模块 UI 才生效（wekit 同款 toast 语义）。
                Toast.makeText(context, "重启小猿口算后生效", Toast.LENGTH_SHORT).show()
            },
        )

        DropDownMenuWidget(
            icon = MaterialSymbols.Outlined.Style,
            title = "页面过渡动画",
            description = null,
            value = ThemeSettings.pageTransitionAnimation,
            options = PageTransitionAnimation.entries.map {
                DropdownOption(it, pageTransitionLabels.getValue(it))
            },
            onValueChange = ThemeSettings::updatePageTransitionAnimation,
        )

        SwitchWidget(
            icon = MaterialSymbols.Outlined.Wallpaper,
            title = "动态壁纸取色",
            description = "使用系统壁纸的强调色作为种子，需系统 Android SDK ≥ 31",
            checked = ThemeSettings.dynamicWallpaper,
            onCheckedChange = ThemeSettings::updateDynamicWallpaper,
        )

        SwitchWidget(
            icon = MaterialSymbols.Outlined.Screen_rotation,
            title = "陀螺仪光效",
            description = "底栏指示器高光随设备倾斜旋转（默认关闭）",
            checked = ThemeSettings.gravityHighlight,
            onCheckedChange = ThemeSettings::updateGravityHighlight,
        )

        DropDownMenuWidget(
            icon = MaterialSymbols.Outlined.Style,
            title = "底栏效果",
            description = "液态玻璃渲染开销大，设备卡顿可降级",
            value = ThemeSettings.bottomBarMode,
            options = FloatingBottomBarMode.entries.map {
                DropdownOption(it, bottomBarModeLabels.getValue(it))
            },
            onValueChange = ThemeSettings::updateBottomBarMode,
        )

        // 壁纸取色开启时隐藏自定义种子行（wekit 用 item(animatedVisibility=...)，
        // 本项目 SegmentedColumn 无 DSL，用 if 实现同等显隐）。
        if (!ThemeSettings.dynamicWallpaper) {
            ColorPickerWidget(
                icon = MaterialSymbols.Outlined.Colorize,
                title = "种子颜色",
                value = ThemeSettings.seedColorHex(),
                onValueChange = { hex ->
                    runCatching { hex.toColorInt() }.getOrNull()?.let(ThemeSettings::updateSeedColor)
                },
            )
        }

        DropDownMenuWidget(
            icon = MaterialSymbols.Outlined.Style,
            title = "调色板样式",
            description = null,
            value = ThemeSettings.paletteStyle,
            options = AppPaletteStyle.entries.map {
                DropdownOption(it, paletteStyleLabels.getValue(it))
            },
            onValueChange = {
                ThemeSettings.updatePaletteStyle(it)
                // 新风格不支持 Spec2025 时把存储的 colorSpec 降级回 2021（wekit 同款逻辑）。
                if (!it.supportsSpec2025 && ThemeSettings.colorSpec == AppColorSpec.SPEC_2025) {
                    ThemeSettings.updateColorSpec(AppColorSpec.SPEC_2021)
                }
            },
        )

        val spec2025Supported = ThemeSettings.paletteStyle.supportsSpec2025
        DropDownMenuWidget(
            icon = MaterialSymbols.Outlined.Contrast,
            title = "颜色规格",
            description = if (!spec2025Supported) "当前调色板样式仅支持 Material 3 (2021)" else null,
            value = ThemeSettings.effectiveColorSpec,
            options = (if (spec2025Supported) AppColorSpec.entries else listOf(AppColorSpec.SPEC_2021)).map {
                DropdownOption(it, it.displayName)
            },
            onValueChange = ThemeSettings::updateColorSpec,
            enabled = spec2025Supported,
        )
    }
}

/** 构建时间格式化（与 MainPagerScreen 首页设备信息同款格式）。 */
private fun formatBuildTime(epochMillis: Long): String {
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    return fmt.format(java.util.Date(epochMillis))
}

/**
 * 「配置」区（对齐 WeKit SettingsPager 配置区）：导出/导入 JSON。
 * 经 [ConfigActions] 统一走透明 Activity + 安卓官方文件选取工具（SAF）。
 */
@Composable
private fun ConfigSection() {
    val context = LocalContext.current

    SegmentedColumn(title = "配置") {
        BaseWidget(
            icon = MaterialSymbols.Outlined.File_upload,
            title = "导出配置",
            description = "将全部设置保存为 JSON 文件",
            onClick = { ConfigActions.export(context) },
        )
        BaseWidget(
            icon = MaterialSymbols.Outlined.File_download,
            title = "导入配置",
            description = "从 JSON 文件恢复设置（覆盖当前全部设置）",
            onClick = { ConfigActions.importFromDocument(context) },
        )
    }
}
