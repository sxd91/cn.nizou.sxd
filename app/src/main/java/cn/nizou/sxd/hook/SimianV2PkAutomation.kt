package cn.nizou.sxd.hook

import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import cn.nizou.sxd.util.Simian
import cn.nizou.sxd.util.SimianV2AutomationPrefs
import cn.nizou.sxd.util.logI
import org.json.JSONArray
import org.json.JSONObject

/** Direct SimianV2 WebApi scheduling model with the actual 3.140 dynamic pad module. */
/** Direct SimianV2 WebApi scheduling model. The pad instance comes from the live Vue/Pinia store. */
internal object SimianV2PkAutomation {
    private enum class Task { STROKE, HAPPY, CONTINUE, CONTINUE_PK }
    private val handler = Handler(Looper.getMainLooper())
    private val tasks = mutableMapOf<Task, Runnable>()
    private data class StrokeSession(val webView: WebView, val tasks: MutableSet<Runnable> = linkedSetOf())
    private var strokeSession: StrokeSession? = null
    private val points = listOf(PointF(146.8571f,498.5714f),PointF(146.8571f,516.2858f),PointF(146.8571f,544.4261f),PointF(148f,561.7143f),PointF(148f,584f),PointF(148f,610.8572f),PointF(148f,627.7143f),PointF(149.7143f,652.2858f),PointF(151.4286f,668f),PointF(153.1429f,675.7143f),PointF(156.8571f,684.5715f))

    /** One cancellable session owns every delayed stroke for the current exercise page. */
    fun scheduleStroke(webView: WebView, firstDelay: Long) {
        strokeSession?.let { active -> cancelStrokeSession(active.webView, "replaced by a new exercise page") }
        val rewrittenSet = Simian.customTitleEnabled || Simian.modifyAnswer
        val nativeN = PkNativeSession.nativeQuestionCount
        val countSource = when { rewrittenSet -> "rewrite"; nativeN > 0 -> "native-match"; else -> "config" }
        val total = when {
            rewrittenSet -> Simian.strokeSubmissionCount
            nativeN > 0 -> nativeN
            else -> Simian.strokeSubmissionCount
        }
        val interval = SimianV2AutomationPrefs.submitInterval.coerceAtLeast(0L)
        // 秒提交：去掉开场等待，用快速尝试轮询待页面/画板就绪后立即提交。
        val effectiveFirst = if (SimianV2AutomationPrefs.quickSubmit) SimianV2AutomationPrefs.quickDelay.coerceAtLeast(0L) else firstDelay.coerceAtLeast(0L)
        val session = StrokeSession(webView)
        strokeSession = session
        repeat(total) { index ->
            lateinit var task: Runnable
            task = Runnable {
                if (strokeSession !== session || !session.tasks.remove(task)) return@Runnable
                if (!webView.isAttachedToWindow) { logI("SimianV2 笔画提交失败：WebView已经离开窗口"); return@Runnable }
                submitWithRetry(webView, index + 1, total, 0, SimianV2AutomationPrefs.quickSubmit)
                if (session.tasks.isEmpty() && strokeSession === session) strokeSession = null
            }
            session.tasks += task
            handler.postDelayed(task, effectiveFirst + index * interval)
        }
        logI("SimianV2 stroke session scheduled: $total first=${effectiveFirst}ms interval=${interval}ms (source=$countSource)")
    }

    /** Cancels the complete delayed-stroke sequence for this WebView. */
    fun cancelStrokeSession(webView: WebView, reason: String) {
        val session = strokeSession ?: return
        if (session.webView !== webView) return
        session.tasks.forEach(handler::removeCallbacks)
        val cancelled = session.tasks.size
        session.tasks.clear()
        strokeSession = null
        if (cancelled > 0) logI("SimianV2 stroke session cancelled: $cancelled ($reason)")
    }

    /** 带重试的笔画提交：失败时重复提交，不超过 retryMax 次，间隔 retryDelay 毫秒。 */
    private fun submitWithRetry(webView: WebView, index: Int, total: Int, attempt: Int, quick: Boolean) {
        if (!webView.isAttachedToWindow) return
        submitStrokeOnce(webView, index, total) { ok ->
            if (ok) return@submitStrokeOnce
            val max = if (quick) 2000 else SimianV2AutomationPrefs.retryMax.coerceAtLeast(1)
            if (attempt < max) {
                val rd = (if (quick) 150L else SimianV2AutomationPrefs.retryDelay.coerceAtLeast(0L))
                logI("SimianV2 笔画提交 $index/$total 失败，重试 ${attempt + 1}/$max (间隔 ${rd}ms)")
                handler.postDelayed({ submitWithRetry(webView, index, total, attempt + 1, quick) }, rd)
            } else {
                logI("SimianV2 笔画提交 $index/$total 失败，已达最大重试次数")
            }
        }
    }

    /** 执行一次笔画提交，延迟读最终状态并回调 ok。 */
    private fun submitStrokeOnce(webView: WebView, index: Int, total: Int, onDone: (Boolean) -> Unit) {
        if (!webView.isAttachedToWindow) { logI("SimianV2 笔画提交失败：WebView已经离开窗口"); return }
        val startTime = System.currentTimeMillis()
        val pointsJson = JSONArray().apply {
            points.forEachIndexed { pointIndex, point ->
                put(JSONObject().apply {
                    put("x", point.x.toDouble())
                    put("y", point.y.toDouble())
                    put("pressure", 0)
                    put("time", startTime + pointIndex * 8L)
                })
            }
        }
        val script = """
(() => {
    const points = $pointsJson;
    const status = window.__strokeSubmitStatus = { status: 'finding-pad', pointCount: points.length };
    const unref = t => (t && typeof t === 'object' && 'value' in t) ? t.value : t;
    const isPad = p => !!p && typeof p.dispatchEvent === 'function' && typeof p.toData === 'function';
    const isLive = cfg => !!cfg && !!cfg.keypointId;
    // 画板实例不是放在 Pinia store 里，而是 useRecognizeBoard 这个 composable 内部的局部 ref。
    // 真机 probe 已证实：globalProperties 无 dollar-pinia、System.entries() 为空、PK 页面不走离线包。
    // 唯一能拿到活体 pad 的方式：遍历 Vue 组件树，找 setupState 上带 pad/recognizeConfig 的组件。
    const looksLikePad = v => isPad(unref(v));
    // ---- 诊断：遍历组件树并记录每层观察，写进 status.walk ----
    const walkLog = [];
    let hitSeen = null;
    const subTreeChildren = vnode => {
        const kids = [];
        const walk = v => {
            if (!v) return;
            if (v.component) kids.push(v.component);
            if (Array.isArray(v.children)) v.children.forEach(walk);
            if (v.suspense && v.suspense.activeBranch) walk(v.suspense.activeBranch);
        };
        walk(vnode);
        return kids;
    };
    const scanInstance = (inst, depth) => {
        if (!inst || hitSeen) return;
        if (depth > 60) return;
        const st = inst.setupState || {};
        const stKeys = Object.keys(st);
        const hasPad = !!st.pad;
        const hasCfg = !!st.recognizeConfig;
        const padObj = unref(st.pad);
        const cfgObj = unref(st.recognizeConfig);
        const rec = {
            d: depth,
            name: (inst.type && (inst.type.name || inst.type.__name)) || '',
            stKeys: stKeys.slice(0, 20),
            hasPad: hasPad,
            padShape: hasPad ? (typeof padObj + (padObj && typeof padObj === 'object' ? ':' + Object.getOwnPropertyNames(padObj).slice(0, 10).join(',') : '')) : '',
            hasCfg: hasCfg,
            cfgKeypoint: cfgObj && cfgObj.keypointId
        };
        walkLog.push(rec);
        if (hasPad && isPad(padObj) && isLive(cfgObj)) {
            hitSeen = { source: 'vue-setup-pad', pad: padObj, config: cfgObj };
            return;
        }
        const ctx = inst.ctx || {};
        const ctxPad = unref(ctx.pad);
        if (ctxPad && isPad(ctxPad) && isLive(unref(ctx.recognizeConfig))) {
            hitSeen = { source: 'vue-ctx-pad', pad: ctxPad, config: unref(ctx.recognizeConfig) };
            return;
        }
        const kids = subTreeChildren(inst.subTree);
        for (const k of kids) { scanInstance(k, depth + 1); if (hitSeen) return; }
    };
    const collect = () => {
        const el = document.querySelector('#app') || document.body;
        const app = el && el.__vue_app__;
        if (!app) return null;
        const root = app._instance;
        scanInstance(root, 0);
        status.walk = walkLog.slice(0, 40);
        status.walkCount = walkLog.length;
        if (hitSeen) { status.piniaFound = 1; return hitSeen; }
        const provides = (app._context && app._context.provides) || {};
        const pKeys = Object.keys(provides);
        status.providesKeys = pKeys.slice(0, 20);
        for (const k of pKeys) {
            let v = null;
            try { v = provides[k]; } catch (_) { continue; }
            const pad = unref(v && v.pad);
            const cfg = unref(v && v.recognizeConfig);
            if (isPad(pad) && isLive(cfg)) { status.piniaFound = 1; return { source: 'vue-provides', pad: pad, config: cfg }; }
        }
        status.piniaFound = 0;
        return null;
    };
    const fromRegistry = async () => {
        if (typeof System === 'undefined' || typeof System.import !== 'function' || typeof System.entries !== 'function') return null;
        const urls = [];
        System.entries().forEach((v, k) => {
            if (typeof k === 'string' && k.indexOf('index-legacy.') >= 0 && k.slice(-3) === '.js') urls.push(k);
        });
        status.registry = urls;
        for (const url of urls) {
            try {
                const m = await System.import(url);
                if (typeof m.d !== 'function') continue;
                const store = m.d();
                const pad = unref(store && store.pad);
                const cfg = unref(store && store.recognizeConfig);
                if (!isPad(pad) || !isLive(cfg)) continue;
                return { source: 'systemjs-registry', pad: pad, config: cfg };
            } catch (_) { }
        }
        return null;
    };
    const groupsFromPoints = () => [{ points: points, penColor: '#000', minWidth: 3, maxWidth: 3, dotSize: 0, velocityFilterWeight: 0.7, compositeOperation: 'source-over' }];
    const commit = found => {
        status.source = found.source;
        status.keypointId = found.config.keypointId;
        status.expectedResult = found.config.answers;
        const pad = found.pad;
        status.status = 'injecting-data';
        // 优先走画板自身的数据注入 API：fromData 会把点集正规化为贝塞尔段并真正画到 canvas，
        // 这样后续 endStroke 监听器通过 toData() 读到的是完整笔画，识别端不再拿到空内容。
        let injected = false;
        try {
            if (typeof pad.fromData === 'function') {
                pad.fromData(groupsFromPoints(), { clear: true });
                injected = true;
                status.injectMode = 'fromData';
            }
        } catch (err) {
            status.injectError = String(err && err.message ? err.message : err);
        }
        // 回退：直接写 _data 并同步 _isEmpty / 重绘，兼容没有 fromData 的旧画板实现。
        if (!injected) {
            pad._data = groupsFromPoints();
            if ('_isEmpty' in pad) { pad._isEmpty = false; }
            try {
                if (typeof pad._fromData === 'function') {
                    pad._fromData(pad._data, pad._drawCurve.bind(pad), pad._drawDot.bind(pad));
                }
            } catch (_) { }
            if ('_isEmpty' in pad) { pad._isEmpty = false; }
            status.injectMode = 'raw-data';
        }
        status.status = 'dispatching-end-stroke';
        try {
            pad.dispatchEvent(new CustomEvent('endStroke', { detail: { synthetic: true } }));
            status.dispatchOk = true;
        } catch (err2) {
            status.dispatchOk = false;
            status.dispatchError = String(err2 && err2.message ? err2.message : err2);
            status.status = 'failed';
            status.error = 'endStroke dispatch threw: ' + status.dispatchError;
            return;
        }
        status.status = 'waiting-recognition';
    };
    const live = collect();
    if (live) { commit(live); return JSON.stringify(status); }
    fromRegistry().then(found => {
        if (found) { commit(found); return; }
        status.status = 'failed';
        status.error = 'no live pad found in pinia store or SystemJS registry';
    }).catch(err => {
        status.status = 'failed';
        status.error = String(err && err.message ? err.message : err);
    });
    return JSON.stringify(status);
})();
""".trimIndent()
        webView.post {
            if (!webView.isAttachedToWindow) return@post
            webView.evaluateJavascript(script) { result ->
                logI("SimianV2 笔画提交 $index/$total result: " + result)
                handler.postDelayed({
                    if (!webView.isAttachedToWindow) { onDone(false); return@postDelayed }
                    webView.evaluateJavascript("JSON.stringify(window.__strokeSubmitStatus || { status: 'missing' })") { raw ->
                        val final = (raw ?: "null")
                        logI("SimianV2 笔画提交 $index/$total final: " + final)
                        onDone(final.contains("waiting-recognition") || final.contains("dispatching-end-stroke"))
                    }
                }, 1200L)
            }
        }
    }
    fun clickHappyAccept(webView: WebView, delay: Long = 3000L) = schedule(Task.HAPPY, webView, delay, "开心收下") { click(webView,"开心收下") }
    fun clickContinue(webView: WebView, delay: Long = 500L) = schedule(Task.CONTINUE, webView, delay, "继续") { click(webView,"继续") }
    fun clickContinuePk(webView: WebView, delay: Long = 2000L) = schedule(Task.CONTINUE_PK, webView, delay, "继续PK") { click(webView,"继续PK") }

    private fun schedule(kind: Task, webView: WebView, delay: Long, label: String, action: () -> Unit) {
        tasks.remove(kind)?.let(handler::removeCallbacks)
        lateinit var task: Runnable
        task = Runnable {
            if (tasks[kind] !== task) return@Runnable
            tasks.remove(kind)
            if (!webView.isAttachedToWindow) { logI("SimianV2 " + label + " cancelled: WebView detached"); return@Runnable }
            runCatching(action).onFailure { error -> logI("SimianV2 " + label + " failed: " + error.message) }
        }
        tasks[kind] = task
        handler.postDelayed(task, delay.coerceAtLeast(0L))
    }
    private fun click(webView: WebView, label: String) {
        val text = JSONObject.quote(label)
        val script = """(() => {
            const t=$text, visible=e=>{if(!e)return false;const s=getComputedStyle(e),r=e.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0}, textOf=e=>(e.textContent||'').replace(/\s+/g,'');
            const button=[...document.querySelectorAll('button,[role=button],.button,.btn,.retry,.modal-confirm,.bottom-content-button,.btn-confirm-wrap')].find(e=>visible(e)&&textOf(e)===t);
            if(button) button.click(); else console.log('SimianV2 missing '+t);
        })();""".trimIndent()
        evaluate(webView, script, "点击" + label)
    }
    private fun evaluate(webView: WebView, script: String, action: String) = webView.post {
        if (!webView.isAttachedToWindow) { logI("SimianV2 " + action + " failed: WebView detached"); return@post }
        webView.evaluateJavascript(script) { result -> logI("SimianV2 " + action + " result: " + result) }
    }
}
