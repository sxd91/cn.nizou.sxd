package cn.nizou.sxd.ui.theme

import android.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.nizou.sxd.ui.content.FloatingBottomBarMode
import cn.nizou.sxd.util.SettingsPrefs
import cn.nizou.sxd.util.StringRes
import cn.nizou.sxd.util.currentApplication
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec

/**
 * 模块主题设置 —— 完整移植 WeKit `ThemeSettings`（material-kolor 动态配色）。
 *
 * 与 wekit 的区别：
 *  - 持久化用本项目 `SettingsPrefs`（模块 prefs `auto_oral_calculation`，键 `theme_xxx`），
 *    不用 wekit 的 WePrefs/MMKV；
 *  - `SettingsUiEngine` 支持 Material 3 与 Nuke；两者复用同一份持久化配置。
 *  - 默认 seed 保留模块绿 0xFF2E7D32（wekit 是微信绿 0xFF07C160）。
 *
 * 所有状态都是 Compose 可观察状态（mutableStateOf 种子值来自 prefs），设置行改动即时换肤；
 * 枚举按 `Enum.name` 持久化。
 */
object ThemeSettings {

    /**
     * SettingsPrefs 的 string/bool 读写需要 [StringRes] 参数，但其实现只用 `key`（res 参数
     * 实际未使用）。这里传一个不依赖 XposedInit.moduleRes 的实例，避免在独立 App 启动、
     * `onModuleLoaded` 尚未执行时初始化 ThemeSettings 触发 lateinit 崩溃。
     */
    private val prefsRes by lazy { StringRes(currentApplication().resources) }

    private const val KEY_UI_ENGINE = "theme_ui_engine"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_PREDICTIVE_BACK_ENABLED = "theme_predictive_back_enabled"
    private const val KEY_PAGE_TRANSITION_ANIMATION = "theme_page_transition"
    private const val KEY_DYNAMIC_WALLPAPER = "theme_dynamic_wallpaper"
    private const val KEY_PALETTE_STYLE = "theme_palette_style"
    private const val KEY_COLOR_SPEC = "theme_color_spec"
    private const val KEY_SEED_COLOR = "theme_seed_color"
    private const val KEY_GRAVITY_HIGHLIGHT = "theme_gravity_highlight"
    private const val KEY_BOTTOM_BAR_MODE = "theme_bottom_bar_mode"

    /** 默认取色（模块绿，对齐 wekit 语义：wekit 默认是微信绿 0xFF07C160）。 */
    const val DEFAULT_SEED_COLOR: Int = 0xFF2E7D32.toInt()

    var uiEngine by mutableStateOf(
        SettingsUiEngine.fromName(SettingsPrefs.readString(prefsRes, KEY_UI_ENGINE, ""))
    )
        private set
    var themeMode by mutableStateOf(
        AppThemeMode.fromName(SettingsPrefs.readString(prefsRes, KEY_THEME_MODE, ""))
    )
        private set
    var predictiveBackEnabled by mutableStateOf(
        SettingsPrefs.readBoolean(prefsRes, KEY_PREDICTIVE_BACK_ENABLED, false)
    )
        private set
    /** 安装平台预测返回 flag 是进程启动级操作，此处为进程启动时的快照。 */
    val appliedPredictiveBackEnabled = predictiveBackEnabled
    var pageTransitionAnimation by mutableStateOf(
        PageTransitionAnimation.fromName(
            SettingsPrefs.readString(prefsRes, KEY_PAGE_TRANSITION_ANIMATION, "")
        )
    )
        private set
    /** 从平台壁纸强调色（SDK >= 31）取 seed，而不是 [seedColor]。 */
    var dynamicWallpaper by mutableStateOf(
        SettingsPrefs.readBoolean(prefsRes, KEY_DYNAMIC_WALLPAPER, false)
    )
        private set
    var paletteStyle by mutableStateOf(
        AppPaletteStyle.fromName(SettingsPrefs.readString(prefsRes, KEY_PALETTE_STYLE, ""))
    )
        private set
    var colorSpec by mutableStateOf(
        AppColorSpec.fromName(SettingsPrefs.readString(prefsRes, KEY_COLOR_SPEC, ""))
    )
        private set

    /** 自定义取色（ARGB int，壁纸取色关闭时使用）。 */
    var seedColor by mutableIntStateOf(
        SettingsPrefs.readInt(KEY_SEED_COLOR, DEFAULT_SEED_COLOR)
    )
        private set

    /** 底栏陀螺仪光效（默认关，用户要求）：指示器高光随设备倾斜旋转。 */
    var gravityHighlight by mutableStateOf(
        SettingsPrefs.readBoolean(prefsRes, KEY_GRAVITY_HIGHLIGHT, false)
    )
        private set

    /**
     * 底栏效果模式（默认液态玻璃）。LiquidGlass 的全屏 backdrop 捕获 + blur 渲染开销大，
     * 设备卡顿可降级 Blur（毛玻璃）或 None（纯色）。
     */
    var bottomBarMode by mutableStateOf(
        runCatching {
            FloatingBottomBarMode.valueOf(
                SettingsPrefs.readString(prefsRes, KEY_BOTTOM_BAR_MODE, "")
            )
        }.getOrDefault(FloatingBottomBarMode.LiquidGlass)
    )
        private set

    /** 当前 palette style 不支持 2025 规范时，规格被强制回落到 2021。 */
    val effectiveColorSpec: AppColorSpec
        get() = if (paletteStyle.supportsSpec2025) colorSpec else AppColorSpec.SPEC_2021

    fun updateUiEngine(value: SettingsUiEngine) {
        uiEngine = value
        SettingsPrefs.writeString(prefsRes, KEY_UI_ENGINE, value.name)
    }

    fun updateThemeMode(value: AppThemeMode) {
        themeMode = value
        SettingsPrefs.writeString(prefsRes, KEY_THEME_MODE, value.name)
    }

    fun updatePredictiveBackEnabled(value: Boolean) {
        predictiveBackEnabled = value
        SettingsPrefs.writeBoolean(prefsRes, KEY_PREDICTIVE_BACK_ENABLED, value)
    }

    fun updatePageTransitionAnimation(value: PageTransitionAnimation) {
        pageTransitionAnimation = value
        SettingsPrefs.writeString(prefsRes, KEY_PAGE_TRANSITION_ANIMATION, value.name)
    }

    fun updateDynamicWallpaper(value: Boolean) {
        dynamicWallpaper = value
        SettingsPrefs.writeBoolean(prefsRes, KEY_DYNAMIC_WALLPAPER, value)
    }

    fun updatePaletteStyle(value: AppPaletteStyle) {
        paletteStyle = value
        SettingsPrefs.writeString(prefsRes, KEY_PALETTE_STYLE, value.name)
    }

    fun updateColorSpec(value: AppColorSpec) {
        colorSpec = value
        SettingsPrefs.writeString(prefsRes, KEY_COLOR_SPEC, value.name)
    }

    fun updateSeedColor(value: Int) {
        seedColor = value
        SettingsPrefs.writeInt(KEY_SEED_COLOR, value)
    }

    fun updateGravityHighlight(value: Boolean) {
        gravityHighlight = value
        SettingsPrefs.writeBoolean(prefsRes, KEY_GRAVITY_HIGHLIGHT, value)
    }

    fun updateBottomBarMode(value: FloatingBottomBarMode) {
        bottomBarMode = value
        SettingsPrefs.writeString(prefsRes, KEY_BOTTOM_BAR_MODE, value.name)
    }

    /** ARGB int → HSV float[3]。 */
    fun colorToHsv(color: Int): FloatArray = FloatArray(3).also {
        Color.colorToHSV(color, it)
    }

    /** HSV(h,s,v) → ARGB int。 */
    fun hsvToColor(h: Float, s: Float, v: Float): Int =
        Color.HSVToColor(floatArrayOf(h, s, v))

    /** 取色行显示的色值字符串（如 #2E7D32）。 */
    fun seedColorHex(): String = String.format("#%06X", 0xFFFFFF and seedColor)
}

/** 设置界面决定浅色 / 深色的方式。 */
enum class AppThemeMode(val displayName: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色模式"),
    DARK("深色模式");

    /** [SYSTEM] 读取 [isSystemInDarkTheme]；必须在 composable 中调用。 */
    @Composable
    fun resolve(): Boolean = when (this) {
        SYSTEM -> isSystemInDarkTheme()
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun fromName(value: String?) = entries.find { it.name == value } ?: SYSTEM
    }
}

/** 页面转场动画引擎。 */
enum class PageTransitionAnimation {
    AOSP,
    MIUIX;

    companion object {
        fun fromName(value: String?) = entries.find { it.name == value } ?: AOSP
    }
}

/** 设置 UI 引擎：Material 3 为默认，Nuke 使用 WeKit 同源的紧凑设置组件。 */
enum class SettingsUiEngine(val displayName: String) {
    MATERIAL3("Material 3"),
    NUKE("Nuke");

    companion object {
        fun fromName(value: String?): SettingsUiEngine = when (value) {
            "NUKE" -> NUKE
            "MATERIAL3", "MIUIX", "Miuix", null -> MATERIAL3
            else -> MATERIAL3
        }
    }
}

/**
 * 色板生成风格。只有 [supportsSpec2025] 为 true 的四种风格支持 Spec2025，
 * 其余风格回落到 Spec2021。
 */
enum class AppPaletteStyle(
    val displayName: String,
    val materialKolor: PaletteStyle,
) {
    TONAL_SPOT("Tonal Spot", PaletteStyle.TonalSpot),
    NEUTRAL("Neutral", PaletteStyle.Neutral),
    VIBRANT("Vibrant", PaletteStyle.Vibrant),
    EXPRESSIVE("Expressive", PaletteStyle.Expressive),
    RAINBOW("Rainbow", PaletteStyle.Rainbow),
    FRUIT_SALAD("Fruit Salad", PaletteStyle.FruitSalad),
    MONOCHROME("Monochrome", PaletteStyle.Monochrome),
    FIDELITY("Fidelity", PaletteStyle.Fidelity),
    CONTENT("Content", PaletteStyle.Content);

    val supportsSpec2025: Boolean
        get() = this == TONAL_SPOT || this == NEUTRAL || this == VIBRANT || this == EXPRESSIVE

    companion object {
        fun fromName(value: String?) = entries.find { it.name == value } ?: TONAL_SPOT
    }
}

/** Material 配色规范版本。 */
enum class AppColorSpec(
    val displayName: String,
    val materialKolor: ColorSpec.SpecVersion,
) {
    SPEC_2021("Material 3 (2021)", ColorSpec.SpecVersion.SPEC_2021),
    SPEC_2025("Expressive (2025)", ColorSpec.SpecVersion.SPEC_2025);

    companion object {
        fun fromName(value: String?) = entries.find { it.name == value } ?: SPEC_2025
    }
}
