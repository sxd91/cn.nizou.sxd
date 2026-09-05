package cn.nizou.sxd.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.nizou.sxd.BuildConfig
import cn.nizou.sxd.HOST_PACKAGE_NAME
import cn.nizou.sxd.ui.animation.predictiveback.weKitNavTransition
import cn.nizou.sxd.ui.components.BaseWidget
import cn.nizou.sxd.ui.components.HookStatusCard
import cn.nizou.sxd.ui.components.M3BackButton
import cn.nizou.sxd.ui.components.UserInfoCard
import cn.nizou.sxd.ui.components.M3ListScaffold
import cn.nizou.sxd.ui.components.SegmentedColumn
import cn.nizou.sxd.ui.components.SwitchWidget
import cn.nizou.sxd.ui.content.FloatingBottomBar
import cn.nizou.sxd.ui.content.FloatingBottomBarDefaults
import cn.nizou.sxd.ui.navigation.LocalNavigator
import cn.nizou.sxd.ui.navigation.Navigator
import cn.nizou.sxd.ui.navigation.rememberM3NavEffects
import cn.nizou.sxd.ui.settings.AboutScreen
import cn.nizou.sxd.ui.settings.CustomAnswerScreen
import cn.nizou.sxd.ui.settings.CustomScoreScreen
import cn.nizou.sxd.ui.settings.CustomSettleScreen
import cn.nizou.sxd.ui.settings.DebugScreen
import cn.nizou.sxd.ui.settings.GeneralScreen
import cn.nizou.sxd.ui.settings.LogsScreen
import cn.nizou.sxd.ui.settings.NukeSettingsScreen
import cn.nizou.sxd.ui.settings.PkScreen
import cn.nizou.sxd.ui.settings.SettingsScreen
import cn.nizou.sxd.ui.theme.AutoOralTheme
import cn.nizou.sxd.ui.theme.ThemeSettings
import cn.nizou.sxd.util.StringRes
import cn.nizou.sxd.util.SettingsPrefs
import cn.nizou.sxd.util.openGithub
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Article
import com.composables.icons.materialsymbols.outlined.Bolt
import com.composables.icons.materialsymbols.outlined.Bug_report
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.composables.icons.materialsymbols.outlined.Edit
import com.composables.icons.materialsymbols.outlined.Edit_note
import com.composables.icons.materialsymbols.outlined.Exposure_plus_1
import com.composables.icons.materialsymbols.outlined.Home
import com.composables.icons.materialsymbols.outlined.Info
import com.composables.icons.materialsymbols.outlined.Open_in_new
import com.composables.icons.materialsymbols.outlined.Settings
import com.composables.icons.materialsymbols.outlined.Timer
import com.composables.icons.materialsymbols.outlined.Tune
import com.composables.icons.materialsymbols.outlinedfilled.Article
import com.composables.icons.materialsymbols.outlinedfilled.Home
import com.composables.icons.materialsymbols.outlinedfilled.Settings
import com.composables.icons.materialsymbols.outlinedfilled.Tune
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection

/**
 * 共用导航目标。模块本体（独立 App）与注入宿主面板共用同一批页面 Composable 与同一套
 * `MainPagerScreen` 结构，与 WeKit「本体 / 注入共用组件」一致。
 */
@Serializable
sealed interface MainRoute : NavKey {
    @Serializable
    data object Main : MainRoute
    @Serializable
    data object General : MainRoute
    @Serializable
    data object Pk : MainRoute
    @Serializable
    data object CustomScore : MainRoute
    @Serializable
    data object CustomAnswer : MainRoute
    @Serializable
    data object CustomSettle : MainRoute
    @Serializable
    data object Debug : MainRoute
    @Serializable
    data object About : MainRoute
}

/**
 * 共用根容器。架构对齐 WeKit `SettingsActivity.SettingsRoot`：
 *  - 导航用 miuix-nav `NavDisplay` + `rememberNavBackStack` + `Navigator`（预测返回动画 +
 *    页面转场 + 侧滑返回），转场由 `ThemeSettings.pageTransitionAnimation`（AOSP / MIUIX）驱动；
 *  - 主界面 = `HorizontalPager`（4 tab：首页 / 功能 / 日志 / 设置）+ `FloatingBottomBar`
 *    悬浮胶囊底栏（LiquidGlass），tab 图标用 **MaterialSymbols 同款**（Home/Tune/Article/Settings）；
 *  - 首页 = 激活卡片（强制已激活）+ 设备信息区（api102 / 宿主版本 / 模块版本 / 构建时间 /
 *    设备型号 / 安卓版本 / 加载环境）+ GitHub；
 *  - 功能 = 分类菜单（通用/练习/PK/自定义分数/自定义答案/Debug/关于 下钻，entry 压栈 +
 *    LeftToRight 侧滑返回）；
 *  - 日志 = 模块运行/崩溃日志查看页（文件日志，见 ui/settings/LogsScreen.kt）；
 *  - 设置 = SettingsPager 完整设置（wekit 同款：UI 引擎/主题模式/预测返回动画/页面转场动画/
 *    动态壁纸取色/种子取色/调色板样式/颜色规格），见 [SettingsScreen]。
 *  - 悬浮底栏**不做内容底部留白限位**（用户要求，可遮挡内容）。
 *
 * 模块本体与注入面板共用本容器：`onFinish` 在模块本体为关闭 Activity、在注入面板为关闭
 * 底部弹出的 ComponentDialog。
 *
 * 返回逻辑（对齐 WeKit SettingsRoot）：
 *  - 栈深 > 1：`NavDisplay.onBack` 弹栈（带转场 / 预测返回）；
 *  - 栈深 == 1（pager 根）：非首页 tab 时先回首页 tab，首页 tab 再返回触发 [onFinish]。
 *
 * @param res StringRes 实例。
 * @param onFinish 退到栈根首页再返回时触发（关闭整个界面）。
 */
@Composable
fun MainPagerScreen(
    res: StringRes,
    onFinish: () -> Unit,
) {
    AutoOralTheme(seedColor = ThemeSettings.seedColor) {
        val backStack = rememberNavBackStack<MainRoute>(MainRoute.Main)
        val navigator = remember(backStack) { Navigator(backStack) }
        val pagerState = rememberPagerState(pageCount = { MAIN_TABS.size })
        val scope = rememberCoroutineScope()

        /** pager 根上的返回：非首页 tab 回首页，首页 tab 关面板 / 关 Activity。 */
        fun backAtRoot() {
            if (pagerState.currentPage != 0) {
                scope.launch { pagerState.animateScrollToPage(0) }
            } else {
                onFinish()
            }
        }

        CompositionLocalProvider(LocalNavigator provides navigator) {
            NavDisplay(
                backStack = backStack,
                onBack = {
                    if (navigator.backStackSize() <= 1) backAtRoot() else navigator.pop()
                },
                transition = weKitNavTransition(ThemeSettings.pageTransitionAnimation),
                effects = rememberM3NavEffects(),
            ) {
                entry<MainRoute.Main> {
                    MainPager(
                        res = res,
                        pagerState = pagerState,
                        onNavigate = { navigator.push(it) },
                        onBackAtRoot = ::backAtRoot,
                    )
                }
                entry<MainRoute.General>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                    GeneralScreen(res, onBack = { navigator.pop() })
                }
                entry<MainRoute.Pk>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                    PkScreen(res, onBack = { navigator.pop() })
                }
                entry<MainRoute.CustomScore>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                    CustomScoreScreen(onBack = { navigator.pop() })
                }
                entry<MainRoute.CustomAnswer>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                    CustomAnswerScreen(res, onBack = { navigator.pop() })
                }
                entry<MainRoute.CustomSettle>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                    CustomSettleScreen(res, onBack = { navigator.pop() })
                }
                entry<MainRoute.Debug>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                    DebugScreen(res, onBack = { navigator.pop() })
                }
                entry<MainRoute.About>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                    AboutScreen(onBack = { navigator.pop() })
                }
            }
        }

        // 栈根 + pager 非首页 tab：系统返回先回首页 tab（对齐 WeKit SettingsRoot）。
        BackHandler(enabled = navigator.backStackSize() == 1 && pagerState.currentPage != 0) {
            scope.launch { pagerState.animateScrollToPage(0) }
        }
    }
}

/** 悬浮底栏的分类 tab（照 WeKit TAB_ITEMS：MaterialSymbols 图标+文字）。 */
private data class NavItem(
    val label: String,
    val outlined: ImageVector,
    val filled: ImageVector,
)

private val MAIN_TABS = listOf(
    NavItem("首页", MaterialSymbols.Outlined.Home, MaterialSymbols.OutlinedFilled.Home),
    NavItem("功能", MaterialSymbols.Outlined.Tune, MaterialSymbols.OutlinedFilled.Tune),
    NavItem("日志", MaterialSymbols.Outlined.Article, MaterialSymbols.OutlinedFilled.Article),
    NavItem("设置", MaterialSymbols.Outlined.Settings, MaterialSymbols.OutlinedFilled.Settings),
)

/**
 * 主界面：HorizontalPager + FloatingBottomBar 悬浮胶囊底栏（对齐 WeKit MainPagerScreen）。
 * 悬浮底栏不占布局空间，内容可被其遮挡（用户要求，不做 CONTENT_BOTTOM_INSET 限位）。
 */
@Composable
private fun MainPager(
    res: StringRes,
    pagerState: PagerState,
    onNavigate: (MainRoute) -> Unit,
    onBackAtRoot: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val backdrop = rememberLayerBackdrop()
    val barBottomPadding = 12.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize()) {
        // 内容层捕获 backdrop，供底部悬浮底栏做 LiquidGlass 反射/模糊。
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { p ->
                when (p) {
                    0 -> HomeTab(res, onNavigate = onNavigate, onBack = onBackAtRoot)
                    1 -> FeaturesTab(res, onNavigate = onNavigate, onBack = onBackAtRoot)
                    2 -> LogsScreen(res, onBack = onBackAtRoot)
                    else -> when (ThemeSettings.uiEngine) {
                        cn.nizou.sxd.ui.theme.SettingsUiEngine.MATERIAL3 -> SettingsScreen(onBack = onBackAtRoot)
                        cn.nizou.sxd.ui.theme.SettingsUiEngine.NUKE -> NukeSettingsScreen(onBack = onBackAtRoot)
                    }
                }
            }
        }

        FloatingBottomBar(
            items = MAIN_TABS,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = barBottomPadding),
            selectedIndex = { pagerState.targetPage },
            onSelected = { scope.launch { pagerState.animateScrollToPage(it) } },
            backdrop = backdrop,
            // 底栏效果模式（设置页「界面」组可切；卡顿可降级 Blur/None）+ 陀螺仪光效开关（默认关）
            mode = ThemeSettings.bottomBarMode,
            dynamicGravityHighlight = ThemeSettings.gravityHighlight,
            colors = FloatingBottomBarDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                indicatorColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                activeContentColor = MaterialTheme.colorScheme.primary,
            ),
            iconContent = { item, index ->
                // 选中态用 filled 图标、非选中用 outlined（照 WeKit Crossfade 语义）
                Icon(
                    imageVector = if (index == pagerState.targetPage) item.filled else item.outlined,
                    contentDescription = item.label,
                )
            },
            labelContent = { item, _ ->
                Text(
                    text = item.label,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 1,
                    softWrap = false,
                )
            },
        )
    }
}

// ---------------------------------------------------------------------------
//  Page 0 — 首页（对齐 WeKit HomePager：激活卡片 + 设备信息 + GitHub）
// ---------------------------------------------------------------------------

private data class HomeInfoEntry(
    val title: String,
    val description: String,
)

/** 首页：激活卡片（强制已激活）+ 设备信息区 + GitHub。 */
@Composable
private fun HomeTab(
    res: StringRes,
    onNavigate: (MainRoute) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    M3ListScaffold(
        title = "老挂戏老叟",
        navigationIcon = { M3BackButton(onClick = onBack) },
    ) {
        item { HookStatusCard() }
        item { UserInfoCard() }
        item {
            SegmentedColumn(title = "设备信息") {
                deviceInfoEntries().forEach { entry ->
                    BaseWidget(title = entry.title, description = entry.description)
                }
            }
        }
        item {
            SegmentedColumn(title = "了解更多") {
                BaseWidget(
                    title = "Github",
                    description = "github.com/sxd91/nizou",
                    icon = MaterialSymbols.Outlined.Open_in_new,
                    onClick = { context.openGithub() },
                )
            }
        }
    }
}

/** 设备信息区（对齐 WeKit HomePager.DeviceInformation）。 */
@Composable
private fun deviceInfoEntries(): List<HomeInfoEntry> {
    val context = LocalContext.current
    val hostVersion = remember {
        runCatching {
            // ⚠️ 曾误写 "com.fenbian.android.leo"（多一个 n）→ getPackageInfo 永远查不到 → 一直显示「未安装」
            context.packageManager.getPackageInfo(HOST_PACKAGE_NAME, 0)
        }.getOrNull()
    }
    val frameworkInfo = remember { readInjectedFrameworkInfo() }
    return listOf(
        HomeInfoEntry("加载环境", frameworkInfo ?: "未检测到框架（独立打开）"),
        HomeInfoEntry(
            "小猿口算版本",
            hostVersion?.let { "v${it.versionName} (${it.versionCode})" } ?: "未安装",
        ),
        HomeInfoEntry(
            "模块版本",
            "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        ),
        HomeInfoEntry(
            "构建时间",
            BuildConfig.BUILD_TIMESTAMP.takeIf { it > 0 }?.let { formatBuildTime(it) } ?: "—",
        ),
        HomeInfoEntry("设备型号", "${Build.MANUFACTURER} ${Build.MODEL}"),
        HomeInfoEntry("安卓版本", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"),
    )
}

/** Reads the host-only Xposed module through reflection so launcher bytecode stays standalone-safe. */
private fun readInjectedFrameworkInfo(): String? = runCatching {
    val companion = Class.forName("cn.nizou.sxd.XposedInit\$Companion")
    val self = companion.getField("self").get(null) ?: return null
    val type = self.javaClass
    val apiVersion = type.methods.first { it.name == "getApiVersion" && it.parameterCount == 0 }.invoke(self)
    val frameworkName = type.methods.first { it.name == "getFrameworkName" && it.parameterCount == 0 }.invoke(self)
    "API $apiVersion · $frameworkName"
}.getOrNull()

private fun formatBuildTime(epochMillis: Long): String {
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    return fmt.format(java.util.Date(epochMillis))
}

// ---------------------------------------------------------------------------
//  Page 1 — 功能（对齐 WeKit FeaturesPager：分类菜单下钻）
// ---------------------------------------------------------------------------

private data class FeatureMenuEntry(
    val title: String,
    val description: String?,
    val icon: ImageVector,
    val route: MainRoute,
)

@Composable
private fun FeaturesTab(
    res: StringRes,
    onNavigate: (MainRoute) -> Unit,
    onBack: () -> Unit,
) {
    var skipRanking by remember {
        mutableStateOf(SettingsPrefs.readBoolean(res, res.KEY_PK_SKIP_RANKING, false))
    }
    val entries = remember {
        listOf(
            FeatureMenuEntry("通用", "识别/昵称通用开关", MaterialSymbols.Outlined.Tune, MainRoute.General),
            FeatureMenuEntry("PK 自动化", "快速答题与统一循环 PK", MaterialSymbols.Outlined.Bolt, MainRoute.Pk),
            FeatureMenuEntry("Debug", "调试开关", MaterialSymbols.Outlined.Bug_report, MainRoute.Debug),
            FeatureMenuEntry("关于", "版本与项目信息", MaterialSymbols.Outlined.Info, MainRoute.About),
        )
    }
    val customEntries = remember {
        listOf(
            FeatureMenuEntry("自定义分数", "刷取指定分数", MaterialSymbols.Outlined.Exposure_plus_1, MainRoute.CustomScore),
            FeatureMenuEntry("自定义答案", "改题目/改答案/口算答案", MaterialSymbols.Outlined.Edit, MainRoute.CustomAnswer),
            FeatureMenuEntry("自定义结算时间", "极速结算/自定义 costTime", MaterialSymbols.Outlined.Timer, MainRoute.CustomSettle),
        )
    }

    M3ListScaffold(
        title = "功能",
        navigationIcon = { M3BackButton(onClick = onBack) },
    ) {
        item {
            SegmentedColumn(title = "自定义功能") {
                customEntries.forEach { entry ->
                    BaseWidget(
                        title = entry.title,
                        description = entry.description,
                        icon = entry.icon,
                        onClick = { onNavigate(entry.route) },
                        trailingContent = {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Chevron_right,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp),
                            )
                        },
                    )
                }
            }
        }
        item {
            SegmentedColumn(title = "实验新功能") {
                BaseWidget(
                    title = "⚠ 大部分功能不可用",
                    description = "仅「去除排行榜展示动效」可用",
                )
                SwitchWidget(
                    title = "去除排行榜展示动效",
                    description = "跳过 PK 结果后的排行榜展示动效（独立于循环PK）",
                    checked = skipRanking,
                    onCheckedChange = {
                        skipRanking = it
                        SettingsPrefs.writeBoolean(res, res.KEY_PK_SKIP_RANKING, it)
                    }
                )
            }
        }
        item {
            SegmentedColumn(title = "功能") {
                entries.forEach { entry ->
                    BaseWidget(
                        title = entry.title,
                        description = entry.description,
                        icon = entry.icon,
                        onClick = { onNavigate(entry.route) },
                        trailingContent = {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Chevron_right,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp),
                            )
                        },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
//  Page 2 — 日志（完整日志查看页见 ui/settings/LogsScreen.kt：运行/崩溃双 tab +
//  文件下拉选择 + 分享/保存/刷新/清除 + 下拉刷新 + 滚顶滚底，对齐 wekit LogsPager）
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
//  Page 3 — 设置（完整 SettingsPager，wekit 同款，见 ui/settings/SettingsScreen.kt）
// ---------------------------------------------------------------------------
