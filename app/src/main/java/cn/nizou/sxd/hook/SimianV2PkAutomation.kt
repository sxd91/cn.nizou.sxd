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

    /**
     * 带重试的笔画提交（**带节流**）。
     *
     * ## ★★ 2026-10-01 关键修复：重试过密会把 PK 答题页「打回主页」
     *
     * 真机现象：提交后 PK 页面直接退回 PK 主页。
     *
     * 真因：`submitStrokeOnce` 每次都会在页面里跑「扫全部模块候选 → 逐个 `System.import`」，
     * 真机实测 `candidates=42`。而重试间隔默认只有 **20ms**，`retryMax` 默认 **10**
     * （`quickSubmit` 时上限是 **2000**！）→ 一秒内发起几十次全量 import，
     * 442 次 import 的 Promise 洪水把页面 JS 线程与网络栈打爆，
     * 页面自身逻辑（心跳/路由）随之超时 → SPA 自己退回 `pk.html` 主页。
     *
     * 修法（三重节流，任意一条生效即停止重试）：
     *  1. **最小重试间隔 [MIN_RETRY_DELAY_MS]**（默认 1500ms，配置值更小也不低于它）；
     *  2. **全局重试上限 [MAX_TOTAL_ATTEMPTS]**（默认 6）—— 不管 `retryMax` 配多大；
     *  3. **页面内候选探测只做一次**：第一次尝试后，把命中结果记忆在页面
     *     `window.__aa_padCache`，后续 attempt 直接复用，不再重复 import 全量候选。
     */
    private fun submitWithRetry(webView: WebView, index: Int, total: Int, attempt: Int, quick: Boolean) {
        if (!webView.isAttachedToWindow) return
        if (attempt >= MAX_TOTAL_ATTEMPTS) {
            logI("SimianV2 笔画提交 $index/$total 失败，已达全局重试上限 $MAX_TOTAL_ATTEMPTS（停止，避免打爆页面）")
            return
        }
        submitStrokeOnce(webView, index, total) { ok ->
            if (ok) return@submitStrokeOnce
            val configured = if (quick) 150L else SimianV2AutomationPrefs.retryDelay.coerceAtLeast(0L)
            val rd = configured.coerceAtLeast(MIN_RETRY_DELAY_MS)
            logI("SimianV2 笔画提交 $index/$total 失败，重试 ${attempt + 1}/$MAX_TOTAL_ATTEMPTS (间隔 ${rd}ms)")
            handler.postDelayed({ submitWithRetry(webView, index, total, attempt + 1, quick) }, rd)
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
    //
    // ⚠️ 代价很高：真机 candidates=42，逐个 import 会产生大量 Promise。
    // 所以**结果缓存到 window.__aa_padCache**，重试时不再重复 import 全量候选
    // （否则重试会把页面 JS 线程打爆，SPA 自己退回主页 —— 真机已复现）。
    /**
     * 取模块导出的 store 对象集合。
     *
     * ## ★★ 2026-10-01 真因：导出名不是 `d`
     *
     * SimianV2 写死了 `module.d?.()` **和** `index-legacy.DMgv2yXx.js` 这个 hash。
     * 官方每次发版 hash 与导出名都会变 —— **本机这一版的导出名是 `b`**，真机取证
     * （`/root/work/pkassets2/useRecognizeBoard-legacy.D1CLb53q.js` 尾部原文）：
     *
     * ```js
     * e("b", () => ({ pad: I, writeContent: G, recognizeConfig: Q, onRecognize: J,
     *                 performanceTimestamp: q, initWritingPad: $, disposeWritingPad: tt,
     *                 resetCanvas: lt, resetServerCanvas: et, setOnRecognize: xt }))
     * ```
     *
     * 写死 `d` → store 恒为 null → 42 个候选逐个 import 全部拿不到 pad
     * （真机 `{"status":"finding-pad","candidates":42}`），叠加重试就是 Promise 洪水，
     * 把 PK 答题页的 JS 线程打爆 → SPA 自己退回 `pk.html` 主页。
     *
     * 所以这里**不写死任何导出名**：遍历模块的全部导出，函数就地调用、对象直接用，
     * 谁返回含 `pad` 的 store 就用谁（官方再改导出名也不会失效）。
     */
    const storesOf = m => {
        const out = [];
        if (!m || typeof m !== 'object') return out;
        for (const k of Object.getOwnPropertyNames(m)) {
            let v;
            try { v = m[k]; } catch (e) { continue; }
            if (typeof v === 'function') {
                // store 工厂（本机为 `b`，SimianV2 那版为 `d`，将来还可能是别的）
                try { const s = v(); if (s && typeof s === 'object') out.push(s); } catch (e) {}
            } else if (v && typeof v === 'object') {
                out.push(v);
            }
        }
        return out;
    };
    /** 在模块里找画板：遍历所有 store，取第一个 pad 可判定的。 */
    const padFrom = (m, where) => {
        for (const store of storesOf(m)) {
            const pad = unref(store.pad);
            if (isPad(pad)) { status.via = where; return pad; }
        }
        return null;
    };
    const PAD_CACHE = window.__aa_padCache = window.__aa_padCache || {};
    const candidateUrls = () => {
        const urls = new Set();
        // 只认 PK 答题页自己的资源目录，避免把别的服务的同名 chunk 也 import 进来。
        const want = '/bh5/leo-web-oral-pk/assets/';
        const ok = n => typeof n === 'string' && n.indexOf(want) >= 0 && n.slice(-3) === '.js';
        try {
            if (typeof System !== 'undefined' && typeof System.entries === 'function') {
                System.entries().forEach((v, k) => { if (ok(k)) urls.add(k); });
            }
        } catch (e) {}
        try {
            performance.getEntriesByType('resource').forEach(e => {
                const n = e && e.name ? e.name : '';
                if (ok(n)) urls.add(n);
            });
        } catch (e) {}
        return Array.from(urls);
    };
    /**
     * 取模块的 store（`m.d()`）。
     *
     * 命中策略（三级，越靠后代价越低）：
     *  1. 先只试**已记住的模块**（上次扫描确认「store 上有 pad 槽位」的那个）——
     *     重试时只 import 1 个，不再全量扫描；
     *  2. 没记住才做**全量扫描**，并记下「store 上有 pad 键」的模块 URL；
     *  3. 全量扫描过一遍仍无所获就置 `scanDone`，后续 attempt 不再重复扫描
     *     （模块清单在同一页面生命周期内是静态的，重复扫没有新信息）。
     */
    const tryImportRoute = async () => {
        // 1) 只试记住的那个模块（重试时只 import 1 个，不再全量扫描）
        if (PAD_CACHE.moduleUrl) {
            try {
                const m = await System.import(PAD_CACHE.moduleUrl);
                const pad = padFrom(m, 'cache:' + PAD_CACHE.moduleUrl.split('/').pop());
                if (pad) return pad;
            } catch (e) {}
            status.cacheMiss = true;
            return null;
        }
        // 2) 已扫过且没结果 —— 不再重复扫描（避免 Promise 洪水打爆页面）
        if (PAD_CACHE.scanDone) { status.scanSkipped = true; return null; }

        // 3) 全量扫描（每个页面生命周期只做一次）
        const urls = candidateUrls();
        status.candidates = urls.length;
        PAD_CACHE.scanDone = true;
        for (const u of urls) {
            let m = null;
            try { m = await System.import(u); } catch (e) { continue; }
            const pad = padFrom(m, 'import:' + u.split('/').pop());
            if (pad) { PAD_CACHE.moduleUrl = u; return pad; }
            // 记下「有 pad 槽位」的模块：画板可能稍后才填进这个 ref，重试时优先它。
            for (const store of storesOf(m)) {
                if ('pad' in store) { PAD_CACHE.moduleUrl = u; break; }
            }
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

    /**
     * 重试最小间隔。配置值比它小也按它走 —— 每次重试都会在页面里跑一遍
     * 「候选模块 import」，间隔太小会把页面 JS 线程打爆（真机已验证会把 PK 页打回主页）。
     */
    private val MIN_RETRY_DELAY_MS = 1500L

    /**
     * 全局重试上限（不受 `simianv2_retry_max` 配置影响）。
     * 旧行为在 quickSubmit 下上限是 2000，实测会把 PK 页面打回主页。
     */
    private val MAX_TOTAL_ATTEMPTS = 6
}