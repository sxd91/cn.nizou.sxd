package cn.nizou.sxd

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.content.res.AssetManager
import android.content.res.Resources
import android.util.Log
import cn.nizou.sxd.HOST_PACKAGE_NAME
import cn.nizou.sxd.MODULE_PREFS_NAME
import cn.nizou.sxd.hook.BaseHook
import cn.nizou.sxd.util.ActivityProxy
import cn.nizou.sxd.util.DexKitCoordinator
import cn.nizou.sxd.util.HookStatus
import cn.nizou.sxd.util.XposedHelpers
import cn.nizou.sxd.ui.host.DexKitHostProgressDialog
import cn.nizou.sxd.util.crash.JavaCrashHandler
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

/**
 * libxposed API 102 入口。继承 XposedModule，框架自动 attachFramework；
 * 不要在任何生命周期回调之前查找宿主类。宿主类一律用 param.classLoader 加载。
 */
class XposedInit : XposedModule() {

    companion object {
        /** 暴露给 util/hook 层做 hook()/getInvoker()/log() */
        lateinit var self: XposedInit
        lateinit var modulePath: String
        lateinit var moduleRes: Resources
    }

    @SuppressLint("DiscouragedApi")
    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        self = this
        modulePath = moduleApplicationInfo.sourceDir
        // 崩溃捕获：onModuleLoaded 在每个被注入进程（宿主主/子进程）都会触发，统一安装；
        // 模块独立进程由 MainActivity 补装。失败不影响模块启动。
        runCatching { JavaCrashHandler.install() }
            .onFailure { Log.e("AutoOral", "JavaCrashHandler.install failed", it) }
        // 资源加载必须容错：部分框架版本（如 LSPosed standard）下反射 AssetManager/addAssetPath
        // 可能受隐藏 API 限制抛异常，导致模块加载失败/宿主闪退。失败时回退 Resources.getSystem()，
        // 保证 moduleRes 非空（StringRes 依赖它），注入面板仍可渲染。
        moduleRes = try {
            createModuleResources(modulePath)
        } catch (e: Throwable) {
            Log.e("AutoOral", "createModuleResources failed, fallback system resources", e)
            Resources.getSystem()
        }
        // 激活标记：只在宿主进程 onModuleLoaded 时置位本进程标记（作为 RemotePreferences 失败时的兜底）。
        // 模块自身进程（独立设置页/主界面）不应误置 true，否则激活卡片永远显绿。
        if (param.processName == HOST_PACKAGE_NAME) {
            HookStatus.markLocalActive()
        }
        log(
            Log.INFO, "AutoOral",
            "event=module_loaded process=${param.processName} api=${apiVersion} framework=${frameworkName}"
        )
    }

    /**
     * 宿主主进程注入入口。现代回调可能对进程中加载的多个包触发，
     * 因此必须同时过滤 package 与 process（等价旧 packageName == processName）。
     * libxposed 的 PackageReadyParam 无 processName 字段，用 isFirstPackage
     * （true 表示当前进程中该包首次就绪）做单次保护，等价旧「主进程」判断。
     *
     * 注入策略：hook `Application.attach` —— 它在宿主应用 classLoader 完整创建后才执行，
     * 此时 findClass 宿主类必然成功（npatch 与 LSPosed standard 均适用）。此前在
     * onPackageReady 直接 hook 会因部分框架版本 classLoader 未就绪而静默失败（异常被
     * BaseHook.startHookCatching 吞掉），故统一走 attach hook。native 库（libauto_oral）
     * 改为首次使用时懒加载（见 util/Strokes.kt），避免 System.load 与框架 native 加载
     * 窗口冲突 abort 进程。
     */
    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != HOST_PACKAGE_NAME) return
        if (!param.isFirstPackage) return

        val appClassLoader = param.classLoader
        try {
            val appClass = XposedHelpers.findClass("android.app.Application", appClassLoader)
            val attach = appClass.getDeclaredMethod("attach", android.content.Context::class.java)
            attach.isAccessible = true
            hook(attach).setId("app_attach").intercept { chain ->
                val r = chain.proceed()
                try {
                    BaseHook.startHook(this, appClassLoader)
                    // ActivityProxy 借壳引擎：让模块 HostSettingsActivity 寄生宿主进程运行
                    // （真 Activity 转场动画/预测返回）。必须在宿主 Application.attach 后、
                    // 任何模块 Activity 启动前初始化；失败不影响其余 hook。
                    runCatching {
                        val app = chain.thisObject as? android.app.Application
                        if (app != null) {
                            ActivityProxy.init(
                                appContext = app,
                                moduleCl = XposedInit::class.java.classLoader!!,
                                hostCl = appClassLoader,
                            )
                        }
                    }.onFailure { Log.e("AutoOral", "ActivityProxy init failed", it) }
                    // Keep the Simian-style DecorView overlay independent of ActivityProxy success.
                    val appContext = chain.thisObject as? android.app.Application
                    appContext?.let { cn.nizou.sxd.util.LogOverlayWindow.install(it) }
                    // Start/show DexKit at the first resumed host Activity. Creation can occur before
                    // callbacks are registered on several hosts, while resumed is always observed.
                    val dexKitStarted = java.util.concurrent.atomic.AtomicBoolean(false)
                    appContext?.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
                        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
                        override fun onActivityStarted(activity: Activity) = Unit
                        override fun onActivityResumed(activity: Activity) {
                            if (!dexKitStarted.compareAndSet(false, true)) return
                            val apkPath = activity.applicationInfo.sourceDir
                            DexKitCoordinator.start(apkPath)
                            activity.window.decorView.postDelayed({
                                DexKitHostProgressDialog.show(activity)
                            }, 180L)
                        }
                        override fun onActivityPaused(activity: Activity) = Unit
                        override fun onActivityStopped(activity: Activity) = Unit
                        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                        override fun onActivityDestroyed(activity: Activity) = Unit
                    })
                    // 宿主注入成功后写入激活标记（此时 getRemotePreferences 已就绪）：
                    // 供模块设置页/注入面板读取。写入此位置可避免 onPackageReady 早期 RemotePreferences
                    // 未就绪导致写入被静默吞掉（真机 hook_active 缺失，卡片误显未激活）。
                    // 双保险写激活标记：RemotePreferences(跨进程) + 宿主进程本地 prefs。
                    runCatching { HookStatus.markActive(getRemotePreferences(MODULE_PREFS_NAME)) }
                        .onFailure { Log.e("AutoOral", "markActive(remote) failed", it) }
                    runCatching {
                        // attach 是 Application 的实例方法，chain.thisObject 即宿主 Application，
                        // 直接用它写宿主本地 prefs（宿主导入面板读同一份，可靠显示已激活）。
                        val ctx = chain.thisObject as? android.content.Context ?: return@runCatching
                        ctx.getSharedPreferences(MODULE_PREFS_NAME, android.content.Context.MODE_PRIVATE)
                            .edit().putBoolean("hook_active", true).apply()
                    }.onFailure { Log.e("AutoOral", "markActive(local) failed", it) }
                    // 首次启动注入默认配置（2026-08-30 用户提供，账号类 ui_ 键已剔除）。
                    // ⚠️ 不能用 ConfigTransfer.importJson：attach 时机 currentApplication() 未就绪
                    // （ActivityThread.mInitialApplication 尚未赋值）→ isHostProcess() 误判为模块进程
                    // → 走 root 分支 force-stop 宿主 → 闪退（真机 79d35f0 复现）。直接用 ctx 直写。
                    runCatching {
                        val ctx = chain.thisObject as? android.content.Context ?: return@runCatching
                        val prefs = ctx.getSharedPreferences(
                            MODULE_PREFS_NAME, android.content.Context.MODE_PRIVATE
                        )
                        if (!prefs.getBoolean("default_config_applied", false)) {
                            val json = moduleRes.assets.open("default_config.json")
                                .bufferedReader().use { it.readText() }
                            val root = Json.parseToJsonElement(json).jsonObject
                            val editor = prefs.edit().clear()
                            for ((k, v) in root) {
                                if (k.startsWith("ui_")) continue
                                when (v) {
                                    is JsonPrimitive -> when {
                                        v.isString -> editor.putString(k, v.content)
                                        v.booleanOrNull != null ->
                                            editor.putBoolean(k, v.boolean)

                                        v.longOrNull != null && v.intOrNull == null ->
                                            editor.putLong(k, v.long)

                                        v.intOrNull != null -> editor.putInt(k, v.int)
                                        v.floatOrNull != null -> editor.putFloat(k, v.float)
                                    }
                                    is JsonArray -> editor.putStringSet(
                                        k,
                                        v.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }.toSet(),
                                    )
                                    else -> Unit
                                }
                            }
                            editor.commit()
                            prefs.edit().putBoolean("default_config_applied", true).apply()
                        }
                    }.onFailure { Log.e("AutoOral", "default config init failed", it) }
                } catch (e: Throwable) {
                    Log.e("AutoOral", "hook after attach failed", e)
                }
                r
            }
        } catch (e: Throwable) {
            Log.e("AutoOral", "attach hook setup failed", e)
        }
    }

    @SuppressLint("DiscouragedApi")
    @Suppress("DEPRECATION")
    private fun createModuleResources(apkPath: String): Resources {
        // AssetManager() 构造在 compileSdk 34 下被隐藏（package-private），改经反射创建。
        val am = AssetManager::class.java.getDeclaredConstructor()
            .apply { isAccessible = true }
            .newInstance() as AssetManager
        val addAssetPath = AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java)
            .apply { isAccessible = true }
        addAssetPath.invoke(am, apkPath) // 隐藏 API；将模块资源与 assets 载入宿主
        return Resources(
            am,
            Resources.getSystem().displayMetrics,
            Resources.getSystem().configuration
        )
    }
}
