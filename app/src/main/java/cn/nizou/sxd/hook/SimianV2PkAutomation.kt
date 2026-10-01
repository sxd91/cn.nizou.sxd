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

/**
 * SimianV2 页面自动化（笔画提交 / 结算页点击）。
 *
 * ## ★★ 2026-10-01 重写：按 SimianV2 原实现「抄」画板获取方式
 *
 * ### 为什么重写
 *
 * 上一版用「遍历 Vue 组件树找 setupState.pad」，真机实测**永远 finding-pad 失败**
 * （`walk[0].stKeys=[]`、所有层 `hasPad=false`）—— 该页面是离线 SPA，
 * 画板不是放在某个组件的 setupState 上，**扫树这条路根本不成立**。
 *
 * ### SimianV2（`com.log.simianv2`，设备实测可用）的真实做法
 *
 * 反编译 `classes.dex` 拿到的原文：
 *
 * ```js
 * window.__strokeSubmitStatus = { status: 'loading-module', pointCount: points.length };
 * System.import('https://leo.fbcontent.cn/bh5/leo-web-oral-pk/assets/index-legacy.DMgv2yXx.js')
 *   .then(module => {
 *       const store = module.d?.();
 *       const pad = store?.pad?.value ?? store?.pad;
 *       if (!pad) throw new Error('...');
 *       pad._data = [{ points, penColor:'#000', minWidth:3, maxWidth:3,
 *                      velocityFilterWeight:0.7, compositeOperation:'source-over' }];
 *       pad.dispatchEvent(new CustomEvent('endStroke', { detail:{ synthetic:true } }));
 *       window.__strokeSubmitStatus.status = 'submitted';
 *   })
 *   .catch(error => { window.__strokeSubmitStatus.status='failed';
 *                     window.__strokeSubmitStatus.error = String(error); });
 * ```
 *
 * 即：**动态 import 画板所在模块 → 调 `d()` 拿 store → `store.pad` 就是画板**，
 * 不需要遍历组件树。设备上该模块是 `useRecognizeBoard-legacy.<hash>.js`
 * （导出 `{pad, recognizeConfig, onRecognize, ...}`，与 `store.pad` 对应）。
 *
 * ### 本实现（不硬编码 hash）
 *
 * SimianV2 把 `index-legacy.DMgv2yXx.js` 这个 **hash 写死了**，官方每次发版都会变
 * （同一份 `pk.html` 里 20 多个 hash 各不相同）——写死必然过期。
 * 这里改成**自适应**，依次尝试：
 *
 *  1. 扫 `System.entries()` / `performance.getEntriesByType('resource')` 里所有
 *     `leo-web-oral-pk/assets/` 下的 `.js` 模块，逐个 `System.import` 后取 `d().pad`（不传 hash）；
 *  2. 若拿不到，再退**遍历 Vue 组件树**（旧逻辑保留作兜底，某些页面确实只有这条路）；
 *  3. 两条都失败才报 failed，并把诊断写进 `window.__strokeSubmitStatus`。
 */
internal object SimianV2PkAutomation {
    private enum class Task { STROKE, HAPPY, CONTINUE, CONTINUE_PK }
    private val handler = Handler(Looper.getMainLooper())
    private val tasks = mutableMapOf<Task, Runnable>()
    private data class StrokeSession(val webView: WebView, val tasks: MutableSet<Runnable> = linkedSetOf())
    private var strokeSession: StrokeSession? = null

    /** 与 SimianV2 同一组笔画点（左下角竖线，识别为「1」，任何答案模式都能命中）。 */
    private val points = listOf(
        PointF(146.8571f, 498.5714f), PointF(146.8571f, 516.2858f), PointF(146.8571f, 544.4261f),
        PointF(148f, 561.7143f), PointF(148f, 584f), PointF(148f, 610.8572f), PointF(148f, 627.7143f),
        PointF(149.7143f, 652.2858f), PointF(151.4286f, 668f), PointF(153.1429f, 675.7143f),
        PointF(156.8571f, 684.5715f)
    )

    /** 一次可取消的提交会话：拥有当前答题页的全部延迟提交任务。 */
    fun scheduleStroke(webView: WebView, firstDelay: Long) {
        strokeSession?.let { active -> cancelStrokeSession(active.webView, "replaced by a new exercise page") }
        val rewrittenSet = SimianV2AutomationPrefs.linkedCustomAnswer
        val nativeN = PkNativeSession.nativeQuestionCount
        val countSource = when {
            rewrittenSet -> "rewrite"
            nativeN > 0 -> "native-match"
            else -> "config"
        }
        val total = when {
            rewrittenSet -> Simian.strokeSubmissionCount
            nativeN > 0 -> nativeN
            else -> Simian.strokeSubmissionCount
        }
        val interval = SimianV2AutomationPrefs.submitInterval.coerceAtLeast(0L)
        val effectiveFirst =
            if (SimianV2AutomationPrefs.quickSubmit) SimianV2AutomationPrefs.quickDelay.coerceAtLeast(0L)
            else firstDelay.coerceAtLeast(0L)
        val session = StrokeSession(webView)
        strokeSession = session
        repeat(total) { index ->
            lateinit var task: Runnable
            task = Runnable {
                if (strokeSession !== session || !session.tasks.remove(task)) return@Runnable
                if (!webView.isAttachedToWindow) {
                    logI("SimianV2 笔画提交失败：WebView已经离开窗口")
                    return@Runnable
                }
                submitWithRetry(webView, index + 1, total, 0, SimianV2AutomationPrefs.quickSubmit)
                if (session.tasks.isEmpty() && strokeSession === session) strokeSession = null
            }
            session.tasks += task
            handler.postDelayed(task, effectiveFirst + index * interval)
        }
        logI("SimianV2 stroke session scheduled: $total first=${effectiveFirst}ms interval=${interval}ms (source=$countSource)")
    }

    /** 取消该 WebView 上全部待执行的笔画任务。 */
    fun cancelStrokeSession(webView: WebView, reason: String) {
        val session = strokeSession ?: return
        if (session.webView !== webView) return
        session.tasks.forEach(handler::removeCallbacks)
        val cancelled = session.tasks.size
        session.tasks.clear()
        strokeSession = null
        if (cancelled > 0) logI("SimianV2 stroke session cancelled: $cancelled ($reason)")
    }

    /** 带重试的笔画提交：失败重复提交，不超过 retryMax 次，间隔 retryDelay 毫秒。 */
    private fun submitWithRetry(webView: WebView, index: Int, total: Int, attempt: Int, quick: Boolean) {
        if (!webView.isAttachedToWindow) return
        submitStrokeOnce(webView, index, total) { ok ->
            if (ok) return@submitStrokeOnce
            val max = if (quick) 2000 else SimianV2AutomationPrefs.retryMax.coerceAtLeast(1)
            if (attempt < max) {
                val rd = if (quick) 150L else SimianV2AutomationPrefs.retryDelay.coerceAtLeast(0L)
                logI("SimianV2 笔画提交 $index/$total 失败，重试 ${attempt + 1}/$max (间隔 ${rd}ms)")
                handler.postDelayed({ submitWithRetry(webView, index, total, attempt + 1, quick) }, rd)
            } else {
                logI("SimianV2 笔画提交 $index/$total 失败，已达最大重试次数")
            }
        }
    }

    /** 执行一次笔画提交，延迟读最终状态并回调 ok。 */
    private fun submitStrokeOnce(webView: WebView, index: Int, total: Int, onDone: (Boolean) -> Unit) {
        if (!webView.isAttachedToWindow) {
            logI("SimianV2 笔画提交失败：WebView已经离开窗口")
            return
        }
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
        // 注意：脚本内**不要**再出现 `${...}` Kotlin 模板拼接；用 $pointsJson 一处足够。
        // 内嵌 JS 一律避免正则字面量（历史事故：模板渲染后语法错误导致整段注入失效）。
        val script = """
(() => {
    const points = $pointsJson;
    const status = window.__strokeSubmitStatus = { status: 'finding-pad', pointCount: points.length };
    const unref = t => (t && typeof t === 'object' && 'value' in t) ? t.value : t;
    const isPad = p => !!p && typeof p.dispatchEvent === 'function' && typeof p.toData === 'function';

    // ---------- 与 SimianV2 一致的提交动作 ----------
    const commit = pad => {
        pad._data = [{
            points: points, penColor: '#000', minWidth: 3, maxWidth: 3,
            velocityFilterWeight: 0.7, compositeOperation: 'source-over'
        }];
        if ('_isEmpty' in pad) pad._isEmpty = false;
        try {
            if (typeof pad._fromData === 'function') {
                pad._fromData(pad._data, pad._drawCurve.bind(pad), pad._drawDot.bind(pad));
            }
        } catch (e) {}
        if ('_isEmpty' in pad) pad._isEmpty = false;
        status.status = 'dispatching-end-stroke';
        pad.dispatchEvent(new CustomEvent('endStroke', { detail: { synthetic: true } }));
        status.status = 'submitted';
    };

    // ---------- 路线 A（SimianV2 同款）：动态 import 模块取 store.pad ----------
    const padFrom = (m, where) => {
        if (!m) return null;
        let store = null;
        try { store = (typeof m.d === 'function') ? m.d() : (m.default || null); } catch (e) { return null; }
        if (!store) return null;
        const pad = unref(store.pad);
        if (isPad(pad)) { status.via = where; return pad; }
        return null;
    };
    const candidateUrls = () => {
        const urls = new Set();
        try {
            if (typeof System !== 'undefined' && typeof System.entries === 'function') {
                System.entries().forEach((v, k) => {
                    if (typeof k === 'string' && k.indexOf('leo-web-oral-pk') >= 0 && k.slice(-3) === '.js') urls.add(k);
                });
            }
        } catch (e) {}
        try {
            performance.getEntriesByType('resource').forEach(e => {
                const n = e && e.name ? e.name : '';
                if (n.indexOf('leo-web-oral-pk') >= 0 && n.indexOf('/assets/') >= 0 && n.slice(-3) === '.js') urls.add(n);
            });
        } catch (e) {}
        return Array.from(urls);
    };
    const tryImportRoute = async () => {
        const urls = candidateUrls();
        status.candidates = urls.length;
        for (const u of urls) {
            try {
                const m = await System.import(u);
                const pad = padFrom(m, 'import:' + u.split('/').pop());
                if (pad) return pad;
            } catch (e) {}
        }
        return null;
    };

    // ---------- 路线 B（兜底）：遍历 Vue 组件树 / 全局 ----------
    let hitSeen = null;
    const scanInstance = (inst, depth) => {
        if (!inst || hitSeen || depth > 60) return;
        const st = inst.setupState || {};
        const p1 = unref(st.pad);
        if (isPad(p1)) { hitSeen = p1; return; }
        const ctx = inst.ctx || {};
        const p2 = unref(ctx.pad);
        if (isPad(p2)) { hitSeen = p2; return; }
        const kids = [];
        const walk = v => {
            if (!v) return;
            if (v.component) kids.push(v.component);
            if (Array.isArray(v.children)) v.children.forEach(walk);
            if (v.suspense && v.suspense.activeBranch) walk(v.suspense.activeBranch);
        };
        walk(inst.subTree);
        for (const k of kids) { scanInstance(k, depth + 1); if (hitSeen) return; }
    };
    const scanDom = () => {
        const els = document.querySelectorAll('*');
        const cap = Math.min(els.length, 6000);
        for (let i = 0; i < cap && !hitSeen; i++) {
            const inst = els[i].__vueParentComponent || (els[i].__vnode && els[i].__vnode.component) || null;
            let cur = inst, d = 0;
            while (cur && d < 40 && !hitSeen) { scanInstance(cur, d); cur = cur.parent; d++; }
        }
    };
    const scanVue = () => {
        scanDom();
        if (hitSeen) return hitSeen;
        const appEl = document.getElementById('app');
        const app = appEl && appEl.__vue_app__;
        if (app) {
            let root = app._instance || (app._container && app._container._vnode && app._container._vnode.component);
            scanInstance(root, 0);
        }
        if (hitSeen) return hitSeen;
        const provides = (app && app._context && app._context.provides) || {};
        for (const k of Object.keys(provides)) {
            const p = unref(provides[k] && provides[k].pad);
            if (isPad(p)) { hitSeen = p; return hitSeen; }
        }
        return hitSeen;
    };

    // ---------- 主流程：先 DOM 扫（同步、最快），再 import（异步） ----------
    try {
        const live = scanVue();
        if (live) { status.via = 'vue-tree'; commit(live); return JSON.stringify(status); }
    } catch (e) {
        status.vueScanError = String(e && e.message ? e.message : e);
    }
    if (typeof System !== 'undefined' && typeof System.import === 'function') {
        tryImportRoute().then(pad => {
            if (pad) { commit(pad); return; }
            status.status = 'failed';
            status.error = 'no pad: import candidates=' + (status.candidates || 0) + ', vue-tree=miss';
        }).catch(err => {
            status.status = 'failed';
            status.error = String(err && err.message ? err.message : err);
        });
    } else {
        status.status = 'failed';
        status.error = 'System.import unavailable and vue-tree miss';
    }
    return JSON.stringify(status);
})();
""".trimIndent()
        webView.post {
            if (!webView.isAttachedToWindow) return@post
            webView.evaluateJavascript(script) { result ->
                logI("SimianV2 笔画提交 $index/$total result: " + result)
                handler.postDelayed({
                    if (!webView.isAttachedToWindow) {
                        onDone(false)
                        return@postDelayed
                    }
                    webView.evaluateJavascript("JSON.stringify(window.__strokeSubmitStatus || { status: 'missing' })") { raw ->
                        val final = (raw ?: "null")
                        logI("SimianV2 笔画提交 $index/$total final: " + final)
                        onDone(final.contains("submitted") || final.contains("dispatching-end-stroke"))
                    }
                }, 1500L)
            }
        }
    }

    fun clickHappyAccept(webView: WebView, delay: Long = 3000L) =
        schedule(Task.HAPPY, webView, delay, "开心收下") { click(webView, "开心收下") }

    fun clickContinue(webView: WebView, delay: Long = 500L) =
        schedule(Task.CONTINUE, webView, delay, "继续") { click(webView, "继续") }

    fun clickContinuePk(webView: WebView, delay: Long = 2000L) =
        schedule(Task.CONTINUE_PK, webView, delay, "继续PK") { click(webView, "继续PK") }

    private fun schedule(kind: Task, webView: WebView, delay: Long, label: String, action: () -> Unit) {
        tasks.remove(kind)?.let(handler::removeCallbacks)
        lateinit var task: Runnable
        task = Runnable {
            if (tasks[kind] !== task) return@Runnable
            tasks.remove(kind)
            if (!webView.isAttachedToWindow) {
                logI("SimianV2 $label cancelled: WebView detached")
                return@Runnable
            }
            runCatching(action).onFailure { error -> logI("SimianV2 $label failed: " + error.message) }
        }
        tasks[kind] = task
        handler.postDelayed(task, delay.coerceAtLeast(0L))
    }

    private fun click(webView: WebView, label: String) {
        val text = JSONObject.quote(label)
        val script = """(() => {
            const t=$text, visible=e=>{if(!e)return false;const s=getComputedStyle(e),r=e.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0}, textOf=e=>(e.textContent||'').split(/\s+/).join('');
            const button=[].slice.call(document.querySelectorAll('button,[role=button],.button,.btn,.retry,.modal-confirm,.bottom-content-button,.btn-confirm-wrap')).filter(e=>visible(e)&&textOf(e)===t)[0];
            if(button) button.click(); else console.log('SimianV2 missing '+t);
        })();""".trimIndent()
        evaluate(webView, script, "点击" + label)
    }

    private fun evaluate(webView: WebView, script: String, action: String) = webView.post {
        if (!webView.isAttachedToWindow) {
            logI("SimianV2 $action failed: WebView detached")
            return@post
        }
        webView.evaluateJavascript(script) { result -> logI("SimianV2 $action result: " + result) }
    }
}