package cn.nizou.sxd.hook

import android.webkit.WebView
import cn.nizou.sxd.util.SimianV2AutomationPrefs
import io.github.libxposed.api.XposedInterface

/** SimianV2 page automation independent of host BaseWebApp; exact Simian URL routing. */
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
        val clean = url.substringBefore("?")
        when {
            clean.endsWith("/leo-web-oral-pk/exercise.html") || clean.endsWith("/exercise.html") -> {
                if (SimianV2AutomationPrefs.autoAnswer) {
                    SimianV2PkAutomation.scheduleStroke(webView, SimianV2AutomationPrefs.autoAnswerDelay)
                }
            }
            clean.endsWith("/leo-web-study-group/motivation-honor-roll.html") || clean.endsWith("/motivation-honor-roll.html") -> {
                if (SimianV2AutomationPrefs.autoHappyAccept) SimianV2PkAutomation.clickHappyAccept(webView)
                if (SimianV2AutomationPrefs.autoContinue) SimianV2PkAutomation.clickContinue(webView)
            }
            clean.endsWith("/leo-web-oral-pk/result.html") -> {
                if (SimianV2AutomationPrefs.autoContinuePk) SimianV2PkAutomation.clickContinuePk(webView)
            }
            else -> {
                // 离开 PK 链路（非 exercise/result/honor-roll URL）时主动取消未执行的笔画任务，
                // 避免旧 session 的延迟任务挂在 handler 上、与新页面 session 抢占同一个画板。
                SimianV2PkAutomation.cancelStrokeSession(webView, "navigated away: " + clean)
            }
        }
    }
}