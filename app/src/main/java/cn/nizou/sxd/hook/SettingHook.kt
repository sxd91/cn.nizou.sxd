package cn.nizou.sxd.hook

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.TextView
import cn.nizou.sxd.Classname
import cn.nizou.sxd.HOST_PACKAGE_NAME
import cn.nizou.sxd.KEY_START_SETTINGS
import cn.nizou.sxd.api.LegacyApiService
import cn.nizou.sxd.api.OralApiService
import cn.nizou.sxd.ui.host.HostComposePanel
import cn.nizou.sxd.ui.host.HostSettingsActivity
import cn.nizou.sxd.util.HostResultBus
import cn.nizou.sxd.util.XposedHelpers
import cn.nizou.sxd.util.logI
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.HookHandle
import java.lang.reflect.Constructor

class SettingHook(
    self: XposedInterface,
    classLoader: ClassLoader
) : BaseHook(self, classLoader) {

    override val name: String
        get() = "SettingHook"

    private var shouldStartSettings = false

    override fun startHook() {
        hookSettingActivity()
        hookRouterActivity()
        hookHomeActivity()
        hookOnActivityResult()
    }

    /**
     * 钩住宿主设置页 onActivityResult → [HostResultBus]。
     *
     * 注入面板（ComponentDialog）起 SAF 需要宿主 Activity.startActivityForResult 发起、
     * 结果回到宿主 onActivityResult；面板回调在宿主进程内直读写 prefs（内存权威，免 root）。
     * 用 getMethod 取 public（含继承）签名，宿主 Activity 未重写时 hook 基类方法，对所有
     * Activity 生效但只分发匹配 requestCode，无副作用。
     */
    private fun hookOnActivityResult() {
        val settingsActivityClass = findClass(Classname.SETTINGS_ACTIVITY)
        val method = runCatching {
            settingsActivityClass.getMethod(
                "onActivityResult",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Intent::class.java,
            )
        }.getOrNull() ?: return
        method.intercept("settings_onActivityResult") { chain ->
            val r = chain.proceed()
            val args = chain.args
            if (args.size >= 3) {
                HostResultBus.dispatch(
                    (args[0] as? Int) ?: -1,
                    (args[1] as? Int) ?: 0,
                    args[2] as? Intent,
                )
            }
            r
        }
    }

    private fun hookRouterActivity() {
        val routerActivityClass = findClass(Classname.ROUTER_ACTIVITY)
        routerActivityClass.findMethod("onCreate", Bundle::class.java)
            .intercept("router_onCreate") { chain ->
                val r = chain.proceed()
                val activity = chain.thisObject as Activity
                val intent = activity.intent
                if (intent != null) {
                    shouldStartSettings = intent.getBooleanExtra(KEY_START_SETTINGS, false)
                }
                r
            }
    }

    private fun hookHomeActivity() {
        val homeActivityClass = findClass(Classname.HOME_ACTIVITY)
        homeActivityClass.findMethod("onResume").intercept("home_onResume_settings") { chain ->
            val r = chain.proceed()
            if (shouldStartSettings) {
                val context = chain.thisObject as Context
                val intent = Intent().apply {
                    component = ComponentName(HOST_PACKAGE_NAME, Classname.SETTINGS_ACTIVITY)
                }
                context.startActivity(intent)
            }
            r
        }

        val apiServiceCompanionClass = findClass("${Classname.ORAL_API_SERVICE}\$a")
        val legacyApiServiceCompanionClass = findClass("${Classname.LEGACY_API_SERVICE}\$a")
        val gsonClass = findClass(Classname.GSON)
        var handle: HookHandle? = null
        handle = homeActivityClass.findMethod("onResume").intercept("home_onResume_initApi") { chain ->
            val r = chain.proceed()
            runCatching {
                val apiServiceCompanion =
                    XposedHelpers.getStaticObjectField(apiServiceCompanionClass, "a")
                val apiService = XposedHelpers.callMethod(apiServiceCompanion, "a")!!
                OralApiService.init(apiService)
                val legacyApiServiceCompanion =
                    XposedHelpers.getStaticObjectField(legacyApiServiceCompanionClass, "a")
                val legacyApiService = XposedHelpers.callMethod(legacyApiServiceCompanion, "a")!!
                val gson = gsonClass.getDeclaredConstructor().newInstance()
                LegacyApiService.init(legacyApiService, gson)
            }.onFailure {
                logI(it)
            }
            handle?.unhook()
            r
        }
    }

    private fun hookSettingActivity() {
        val lifecycleOwnerKtClass = findClass(Classname.LIFECYCLE_OWNER_KT)
        val settingsActivityClass = findClass(Classname.SETTINGS_ACTIVITY)
        val sectionItemClass = findClass(Classname.SECTION_ITEM)
        // 新版(3.140+) LeoSectionItemCell 已删除单参构造器，仅剩 (Context, AttributeSet) /
        // (Context, AttributeSet, int)。优先双参构造器（兼容单参旧版，单参存在时用它）。
        val sectionItemConstructor: Constructor<*> = try {
            sectionItemClass.getConstructor(Context::class.java, AttributeSet::class.java)
        } catch (_: NoSuchMethodException) {
            sectionItemClass.getConstructor(Context::class.java)
        }
        settingsActivityClass.findMethod("onCreate", Bundle::class.java)
            .intercept("settings_onCreate") { chain ->
                val r = chain.proceed()
                val activity = chain.thisObject as Activity
                val scope =
                    XposedHelpers.callStaticMethod(lifecycleOwnerKtClass, "getLifecycleScope", activity)
                val coroutineContext = XposedHelpers.callMethod(scope, "getCoroutineContext")
                LegacyApiService.setup(coroutineContext!!)
                // 2026-08-30：练习批量上传刷分（ScorePump）也要用宿主协程上下文，注入面板内一并初始化
                runCatching { OralApiService.setup(coroutineContext!!) }.onFailure {
                    logI("OralApiService.setup failed: ${it.message}")
                }

                addSectionItems(activity, sectionItemConstructor)
                r
            }

        settingsActivityClass.findMethod("onResume").intercept("settings_onResume") { chain ->
            val r = chain.proceed()
            if (shouldStartSettings) {
                shouldStartSettings = false
                showSettingsPanel(chain.thisObject as Context)
            }
            r
        }
    }

    private fun showSettingsPanel(context: Context) {
        if (context is Activity) {
            // 优先：借壳启动模块 HostSettingsActivity 寄生宿主进程（真 Activity 转场动画/预测返回，
            // 2026-08-31 用户决策对齐 wekit ActivityProxy）。Intent component 是模块类名，ActivityProxy
            // 的 IActivityManager hook 会自动换壳成宿主 SplashActivity 走系统栈。
            runCatching {
                val intent = android.content.Intent(context, HostSettingsActivity::class.java)
                context.startActivity(intent)
                // ActivityProxy enters through the host shell; force the visible module entry transition.
                context.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                return
            }.onFailure { logI("showSettings via ActivityProxy failed: ${it.message}") }
            // 回退：ComponentDialog（ActivityProxy 未初始化或宿主不支持时保底）
            HostComposePanel.showSettings(context)
        }
    }

    private fun addSectionItems(activity: Activity, sectionItemConstructor: Constructor<*>) {
        val appWidgetId = activity.resources.getIdentifier(
            "cell_appwidget",
            "id",
            activity.packageName
        )
        val appWidget = activity.findViewById<View>(appWidgetId)
        val container = appWidget.parent as LinearLayout
        val labelId =
            activity.resources.getIdentifier("text_label", "id", activity.packageName)

        // 注入入口已改名"老挂戏老叟设置"；「自定义分数」不再是独立 section item，
        // 已并入模块菜单内（SettingsScreen 的 Main 菜单 → 自定义分数 → CustomScoreScreen）。
        val moduleSectionItem = buildModuleSectionItem(activity, sectionItemConstructor, labelId)
        container.addView(moduleSectionItem, 0)
    }

    private fun buildModuleSectionItem(
        activity: Activity,
        itemConstructor: Constructor<*>,
        labelId: Int
    ): View {
        // 双参构造器需要 (Context, AttributeSet)；单参构造器只传 Context。
        val item = if (itemConstructor.parameterCount >= 2) {
            itemConstructor.newInstance(activity, null as AttributeSet?) as View
        } else {
            itemConstructor.newInstance(activity) as View
        }
        return buildSectionItem(item, labelId, "老挂戏老叟设置") {
            showSettingsPanel(activity)
        }
    }

    private fun buildSectionItem(
        item: View,
        labelId: Int,
        label: String,
        onClick: (() -> Unit)? = null
    ): View {
        val labelTv = item.findViewById<TextView>(labelId)
        labelTv.text = label
        item.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        )
        onClick?.let {
            item.setOnClickListener { onClick() }
        }
        return item
    }
}
