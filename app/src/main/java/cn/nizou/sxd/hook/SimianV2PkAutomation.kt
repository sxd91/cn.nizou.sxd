package cn.nizou.sxd.hook

import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import cn.nizou.sxd.util.logI
import org.json.JSONArray
import org.json.JSONObject

/** SimianV2 WebView scheduler: each action replaces only its own pending task. */
internal object SimianV2PkAutomation {
    private enum class Task { STROKE, HAPPY, CONTINUE, CONTINUE_PK }
    private val handler = Handler(Looper.getMainLooper())
    private val tasks = mutableMapOf<Task, Runnable>()
    private val points = listOf(PointF(146.8571f,498.5714f),PointF(146.8571f,516.2858f),PointF(146.8571f,544.4261f),PointF(148f,561.7143f),PointF(148f,584f),PointF(148f,610.8572f),PointF(148f,627.7143f),PointF(149.7143f,652.2858f),PointF(151.4286f,668f),PointF(153.1429f,675.7143f),PointF(156.8571f,684.5715f))
    fun scheduleStroke(webView: WebView, delay: Long) = schedule(Task.STROKE, webView, delay, "提交笔画") {
        val started = System.currentTimeMillis(); val json = JSONArray()
        points.forEachIndexed { index, point -> json.put(JSONObject().put("x",point.x).put("y",point.y).put("pressure",0).put("time",started + index * 8L)) }
        evaluate(webView, "javascript:(()=>{const p=" + json.toString() + ";const pad=window.__pkPad||window.pad;if(!pad){console.log('SimianV2:画板未就绪');return;}pad._data=[{points:p,penColor:'#000',minWidth:3,maxWidth:3,velocityFilterWeight:.7,compositeOperation:'source-over'}];pad.dispatchEvent(new CustomEvent('endStroke',{detail:{synthetic:true}}));})()")
    }
    fun clickHappyAccept(webView: WebView, delay: Long = 3000L) = schedule(Task.HAPPY, webView, delay, "开心收下") { click(webView,"开心收下") }
    fun clickContinue(webView: WebView, delay: Long = 500L) = schedule(Task.CONTINUE, webView, delay, "继续") { click(webView,"继续") }
    fun clickContinuePk(webView: WebView, delay: Long = 2000L) = schedule(Task.CONTINUE_PK, webView, delay, "继续PK") { click(webView,"继续PK") }
    private fun schedule(kind: Task, webView: WebView, delay: Long, label: String, action: () -> Unit) {
        tasks.remove(kind)?.let(handler::removeCallbacks); lateinit var task: Runnable
        task = Runnable { if (tasks[kind] !== task) return@Runnable; tasks.remove(kind); if (!webView.isAttachedToWindow) { logI("SimianV2 " + label + " cancelled: WebView detached"); return@Runnable }; runCatching(action).onFailure { error -> logI("SimianV2 " + label + " failed: " + error.message) } }
        tasks[kind] = task; handler.postDelayed(task, delay.coerceAtLeast(0L))
    }
    private fun click(webView: WebView, label: String) {
        val text = JSONObject.quote(label); val script = "javascript:(()=>{const t=" + text + ",v=e=>{if(!e)return false;const s=getComputedStyle(e),r=e.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0},n=e=>(e.textContent||'').replace(/\\s+/g,'');const e=[...document.querySelectorAll('button,[role=button],.button,.btn,.retry,.modal-confirm,.bottom-content-button,.btn-confirm-wrap')].find(x=>v(x)&&n(x)===t);if(e)e.click();else console.log('SimianV2 missing '+t);})()"; evaluate(webView,script)
    }
    private fun evaluate(webView: WebView, script: String) = webView.post { if (webView.isAttachedToWindow) webView.evaluateJavascript(script, null) }
}
