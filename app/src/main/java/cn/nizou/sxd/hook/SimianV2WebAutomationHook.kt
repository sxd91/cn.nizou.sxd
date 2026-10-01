package cn.nizou.sxd.hook

import android.webkit.WebView
import cn.nizou.sxd.util.PageUrl
import cn.nizou.sxd.util.SimianV2AutomationPrefs
import cn.nizou.sxd.util.logI
import io.github.libxposed.api.XposedInterface

/**
 * SimianV2 页面自动化（不依赖宿主 BaseWebApp，只按 URL 路由）。
 *
 * ## ★★ 2026-10-01 修复：页面判定从不命中 → 「自动提交画笔」整条链路失效
 *
 * ### 真机证据（`auto_oral-2026-10-01.log`）
 *
 * 日志里 `SimianV2 点击继续 / 点击开心收下` **每次对局都有**（说明本类的 `loadUrl` hook
 * 与 `route()` 确实在跑），但**从来没有任何** `stroke session scheduled` / `笔画提交` 行
 * → `scheduleStroke` 一次都没被调用。
 *
 * ### 根因（两条，都在 URL 判定上）
 *
 *  1. **服务名与页面名之间夹着版本号**。PK 页面走离线 webbundle，真机缓存目录是
 *     `cache/webbundle/leo-web-oral-pk_1789638371210/pk.html`，而旧代码匹配的是
 *     `contains("/bh5/leo-web-oral-pk/exercise.html")` —— `leo-web-oral-pk` 与
 *     `.html` 之间**隔着 `_1789638371210` 和另一层目录**，**字面量永不命中**；
 *  2. **答题页文件名换了**。3.140+ 的答题页是 `pk.html`（SPA，答题态在 hash 路由里），
 *     不再是 `exercise.html`；`animation-oral.html` 只在 `leo-web-math-exercise` 里，
 *     而旧代码写的是 `leo-web-oral-pk/animation-oral.html`（**该路径不存在**）。
 *
 * 于是 `pkPageLoaded` **永不置位** → `jsLoadComplete` 里 `compareAndSet(true, false)`
 * 恒为 false → `injectJs2PkPage()` 从不执行 → `scheduleStroke()` 从不调用。
 *
 * ### 修法
 *
 * 统一走 [PageUrl]：先归一化（去 query / hash / `_<10+位版本号>`），再按**服务名 + 页面名**
 * 宽松判定，不依赖版本号与目录层级；同时补 `#` hash 路由（`pk.html#/exercise`）。
 * 并加 **URL 诊断日志**：每条 `loadUrl`（截断）记一次，命中与否都留痕，真机可直接核对。
 */
class SimianV2WebAutomationHook(self: XposedInterface, classLoader: ClassLoader) : BaseHook(self, classLoader) {
    override val name = "SimianV2WebAutomationHook"

    override fun startHook() {
        val one = WebView::class.java.getDeclaredMethod("loadUrl", String::class.java)
        val two = WebView::class.java.getDeclaredMethod("loadUrl", String::class.java, Map::class.java)
        one.intercept("simianv2_webview_url") { chain ->
            route(chain.thisObject as WebView, chain.getArg(0) as String)
            chain.proceed()
        }
        two.intercept("simianv2_webview_url_headers") { chain ->
            route(chain.thisObject as WebView, chain.getArg(0) as String)
            chain.proceed()
        }
    }

    private fun route(webView: WebView, url: String) {
        if (url.startsWith("javascript:")) return
        val normalized = PageUrl.normalizePage(url)

        when {
            // PK 答题页：3.94-3.13x 的 exercise.html / 3.140+ 的 pk.html / animation-oral.html
            PageUrl.isPkExercise(url) -> {
                logI("SimianV2 route: PK exercise page -> $normalized")
                if (SimianV2AutomationPrefs.effectiveAutoStroke) {
                    SimianV2PkAutomation.scheduleStroke(webView, SimianV2AutomationPrefs.autoAnswerDelay)
                } else {
                    logI("SimianV2 route: auto-stroke disabled (autoAnswer=${SimianV2AutomationPrefs.autoAnswer}, linked=${SimianV2AutomationPrefs.linkedCustomAnswer})")
                }
            }

            PageUrl.isHonorRoll(url) -> {
                logI("SimianV2 route: honor-roll page -> $normalized")
                if (SimianV2AutomationPrefs.autoHappyAccept) SimianV2PkAutomation.clickHappyAccept(webView)
                if (SimianV2AutomationPrefs.autoContinue) SimianV2PkAutomation.clickContinue(webView)
            }

            PageUrl.isPkResult(url) -> {
                logI("SimianV2 route: PK result page -> $normalized")
                if (SimianV2AutomationPrefs.autoContinuePk) SimianV2PkAutomation.clickContinuePk(webView)
            }

            else -> {
                if (PageUrl.isPkRelated(url)) {
                    // PK 链路内的其它页面（pk.html 首页、匹配页、赛季页等）：保留会话，只留痕。
                    logI("SimianV2 route: pk-related page (no action) -> $normalized")
                } else {
                    // 离开 PK 链路时取消未执行的笔画任务，避免旧 session 与新页面抢同一个画板。
                    SimianV2PkAutomation.cancelStrokeSession(webView, "navigated away: " + normalized)
                }
            }
        }
    }
}