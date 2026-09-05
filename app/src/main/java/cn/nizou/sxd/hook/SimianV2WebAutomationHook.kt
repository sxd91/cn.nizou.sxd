package cn.nizou.sxd.hook

import android.webkit.WebView
import cn.nizou.sxd.util.SimianV2AutomationPrefs
import io.github.libxposed.api.XposedInterface

/** SimianV2 page automation independent of the removed host BaseWebApp abstraction. */
class SimianV2WebAutomationHook(self: XposedInterface, classLoader: ClassLoader) : BaseHook(self, classLoader) {
    override val name = "SimianV2WebAutomationHook"
    override fun startHook() {
        WebView::class.java.getDeclaredMethod("loadUrl", String::class.java).intercept("simianv2_webview_url") { chain ->
            val result = chain.proceed(); scheduleForUrl(chain.thisObject as WebView, chain.getArg(0) as String); result
        }
        WebView::class.java.getDeclaredMethod("loadUrl", String::class.java, Map::class.java).intercept("simianv2_webview_url_headers") { chain ->
            val result = chain.proceed(); scheduleForUrl(chain.thisObject as WebView, chain.getArg(0) as String); result
        }
    }
    private fun scheduleForUrl(webView: WebView, url: String) {
        if (url.startsWith("javascript:")) return
        when {
            url.contains("leo-web-oral-pk/exercise.html") || url.contains("animation-oral.html") -> if (SimianV2AutomationPrefs.autoAnswer) SimianV2PkAutomation.scheduleStroke(webView, SimianV2AutomationPrefs.autoAnswerDelay)
            url.contains("motivation-honor-roll.html") -> {
                if (SimianV2AutomationPrefs.autoHappyAccept) SimianV2PkAutomation.clickHappyAccept(webView)
                if (SimianV2AutomationPrefs.autoContinue) SimianV2PkAutomation.clickContinue(webView)
            }
            url.contains("leo-web-oral-pk/result.html") -> if (SimianV2AutomationPrefs.autoContinuePk) SimianV2PkAutomation.clickContinuePk(webView)
        }
    }
}
