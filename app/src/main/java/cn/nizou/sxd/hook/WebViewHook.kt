package cn.nizou.sxd.hook

import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.webkit.JavascriptInterface
import cn.nizou.sxd.Classname
import cn.nizou.sxd.XposedInit
import cn.nizou.sxd.XposedInit.Companion.moduleRes
import cn.nizou.sxd.entities.AutoAnswerMode
import cn.nizou.sxd.util.AnswerCache
import cn.nizou.sxd.util.Debug
import cn.nizou.sxd.util.PK
import cn.nizou.sxd.util.PageUrl
import cn.nizou.sxd.util.PkBundlePatcher
import cn.nizou.sxd.util.Simian
import cn.nizou.sxd.util.XposedHelpers
import cn.nizou.sxd.util.currentApplication
import cn.nizou.sxd.util.logI
import cn.nizou.sxd.util.pathPoints
import cn.nizou.sxd.util.toJSONArray
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.HookHandle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class WebViewHook(
    self: XposedInterface,
    classLoader: ClassLoader
) : BaseHook(self, classLoader) {

    override val name: String
        get() = "WebViewHook"

    private val standardJs by lazy {
        moduleRes.assets.open("js/standard.js")
            .bufferedReader().use { it.readText() }
    }

    private val quickJs by lazy {
        moduleRes.assets.open("js/quick.js")
            .bufferedReader().use { it.readText() }
    }

    private val cyclicJs by lazy {
        moduleRes.assets.open("js/cyclic.js")
            .bufferedReader().use { it.readText() }
    }

    private val fastSettleJs by lazy {
        moduleRes.assets.open("js/fastsettle.js")
            .bufferedReader().use { it.readText() }
    }

    /**
     * H5 网页调试器（Eruda）—— 逆向系老叟同款。
     *
     * 仅在 [PK.h5DebugConsole] 开启时才会被读取/注入。
     * ⚠️ 单例懒加载：474KB 的脚本，只在用户真正开启时才读盘。
     */
    private val erudaJs by lazy {
        moduleRes.assets.open("js/eruda.js")
            .bufferedReader().use { it.readText() }
    }

    /**
     * 注入 Eruda 面板：**两步** —— 先注入脚本，再调 `eruda.init()`。
     *
     * ## 为什么要两步
     *
     * `eruda.js` 只是把 `window.eruda` 挂上去；**不调 `init()` 不会浮面板**。
     *
     * ## 为什么加 `_isInit` 守卫
     *
     * 同一次页面加载 `onPageFinished` 可能回调多次（含 iframe），
     * 重复 `init()` 会叠出多个面板。所以先探一下 `eruda._isInit`。
     */
    private fun injectEruda(loadUrl: Method, webView: View) {
        try {
            injectJsCode(erudaJs, loadUrl, webView)
            injectJsCode(
                "if(window.eruda&&!window.eruda._isInit){eruda.init({useShadowDom:true,defaultPanel:'console'});" +
                    "try{eruda.get('console').config.set('displayTimestamps',true);}catch(e){}}",
                loadUrl,
                webView,
            )
            logI("eruda (H5 调试器) injected")
        } catch (e: Throwable) {
            logI("injectEruda failed: ${e.message}")
        }
    }

    private val pkPageLoaded = AtomicBoolean(false)



    private val resultPageLoaded = AtomicBoolean(false)

    private val appropriateCostTime = AtomicLong(0L)

    private var webViewRef: WeakReference<View>? = null

    private val webView get() = webViewRef?.get()

    private var loadUrl: Method? = null

    @JavascriptInterface
    fun log(str: String) {
        logI("console.log >>>>>>>")
        logI(str)
        logI("console.log <<<<<<<")
    }

    @JavascriptInterface
    fun targetCostTime(costTime: Long) {
        appropriateCostTime.set(costTime - 100)
    }

    @JavascriptInterface
    fun quickModeAwait(questionCnt: Int, callback: String) {
        val loadUrl = loadUrl ?: return
        val webView = webView ?: return
        val appropriateCostTime = appropriateCostTime.get()
        val waitTime = if (PK.quickModeMustWin && appropriateCostTime > 0) {
            appropriateCostTime
        } else {
            getSimulateCostTime(questionCnt).coerceAtLeast(questionCnt * 200L)
        }
        logI("waitTime: $waitTime, callback: $callback")
        webView.postDelayed({
            injectJsCode("window.$callback && window.$callback();", loadUrl, webView)
        }, waitTime)
    }

    private fun hookConsoleLog() {
        val loadUrl = loadUrl ?: return
        val webView = webView ?: return
        invokeOriginal(
            loadUrl,
            webView,
            arrayOf("javascript: (function() { let backup_log=console.log;console.log=function(){if(arguments.length>=1){let l=arguments[0];window.AutoOral&&window.AutoOral.log(typeof l===l?l:JSON.stringify(l))}return backup_log(arguments)}; })();")
        )
    }

    override fun startHook() {
        // 新版(3.140+) 已移除 vgo BaseWebApp（WebView 架构重构为 kanyun H5BaseWebApp 等），
        // 类不存在时优雅跳过 WebView 注入。
        val baseWebAppClass = XposedHelpers.findClassIfExists(Classname.BASE_WEB_APP, classLoader)
            ?: return logI("WebViewHook: BaseWebApp not found in host, skip webview hooks")
        val simpleWebAppFireworkClass =
            findClass(Classname.SIMPLE_WEB_APP_FIREWORK_ACTIVITY)
        val webViewField =
            simpleWebAppFireworkClass.fields.firstOrNull { it.type == baseWebAppClass }

        loadUrl = baseWebAppClass.methods.firstOrNull {
            it.name == "loadUrl" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
        }
        val addJavascriptInterface = baseWebAppClass.methods.firstOrNull {
            it.name == "addJavascriptInterface"
        }
        addJavascriptInterface?.let(::hookAddJavascriptInterface)

        simpleWebAppFireworkClass.findMethod("onCreate", Bundle::class.java)
            .intercept("webapp_onCreate") { chain ->
                val r = chain.proceed()
                logI("simpleWebApp onCreate")
                webViewField?.get(chain.thisObject)?.let {
                    webViewRef = WeakReference(it as View)
                    addJavascriptInterface?.invoke(
                        it,
                        this,
                        "AutoOral"
                    )
                }
                r
            }

        loadUrl?.intercept("webapp_loadUrl") { chain ->
            val r = chain.proceed()
            val str = chain.getArg(0).toString()
            // 诊断：每条真实页面加载都留痕（截断），便于真机核对 URL 形态
            // （离线 webbundle 目录名带 _<版本号>，字面量匹配极易失效）。
            if (!str.startsWith("javascript:") && !str.startsWith("data:")) {
                logI("loadUrl >>> " + str.take(180))
            }
            when {
                str.startsWith("javascript:") -> Unit
                // PK 答题页：3.94-3.13x 的 exercise.html / 3.140+ 的 pk.html / animation-oral.html。
                // 统一走 PageUrl（去 query/hash/_<版本号>），避免版本号夹在中间导致字面量永不命中。
                PageUrl.isPkExercise(str) -> {
                    logI("PK exercise page loaded: " + PageUrl.normalizePage(str))
                    hookConsoleLog()
                    pkPageLoaded.set(true)
                }

                PageUrl.isEnglishWords(str) -> {
                    logI("english-words.html loaded")
                    hookConsoleLog()
                    pkPageLoaded.set(true)
                }

                PageUrl.isPkResult(str) -> {
                    logI("result.html loaded")
                    hookConsoleLog()
                    resultPageLoaded.set(true)
                }
            }
            r
        }

        hookJsLoadComplete()

        hookShouldInterceptRequest()
    }

    private fun hookJsLoadComplete() {
        val commonWebViewInterfaceClass = findClass(Classname.COMMON_WEB_VIEW_INTERFACE)
        commonWebViewInterfaceClass.findMethod("jsLoadComplete", String::class.java)
            .intercept("jsLoadComplete") { chain ->
                val r = chain.proceed()
                when {
                    pkPageLoaded.compareAndSet(true, false) -> {
                        injectJs2PkPage()
                    }

                    resultPageLoaded.compareAndSet(true, false) -> {
                        injectJs2ResultPage()
                    }
                }
                r
            }
    }

    /** 注入全局配置：`window.$key = $value`（修复旧实现写死 window._$key 字面量、目标键从未被设置的 bug）。 */
    private fun injectConfig(loadUrl: Method, webView: View, key: String, value: Any) {
        invokeOriginal(
            loadUrl,
            webView,
            arrayOf("javascript: (function(){window.$key=$value;})();")
        )
    }

    /** 答题 JS 配置：`window.__aa_config` = {mode, answer, correctCount}（quick.js 读取）。 */
    private fun injectAaConfig(loadUrl: Method, webView: View) {
        val cfg = JSONObject().apply {
            put("mode", PK.mode.value)
            put("answer", if (Simian.modifyAnswer) Simian.answers else "")
            put("correctCount", Simian.customCorrectCount)
        }.toString()
        invokeOriginal(
            loadUrl,
            webView,
            arrayOf("javascript:(function(){window.__aa_config=$cfg;})();")
        )
    }

    private fun injectJsCode(jsCode: String, loadUrl: Method, webView: View) {
        invokeOriginal(
            loadUrl,
            webView,
            arrayOf("javascript:(function() { $jsCode })();")
        )
        logI("js injected")
    }

    private fun injectJs2PkPage() {
        val loadUrl = loadUrl ?: return
        val webView = webView ?: return
        webView.post {
            // 2026-08-29：整体 try-catch，防止任一环节异常（如 PK.mode 越界）吞掉注入导致
            // 「注入日志消失/JS 不注入」；任何失败都留痕。
            try {
                // ★ H5 网页调试器（Eruda）：独立于答题模式，只要开关开着就注入。
                if (PK.h5DebugConsole) injectEruda(loadUrl, webView)
                val mode = PK.mode
                // 答题 JS 配置（mode/自定义答案/自定义正确题数），quick.js 读取；标准模式同样注入。
                injectAaConfig(loadUrl, webView)
                // 题目答案注入 window.aa_answers（不依赖 JS bridge addJavascriptInterface——
                // 答题 WebView 上 window.AutoOral 可能未注册，getAnswers() 拿不到 → 直接注入变量）
                if (AnswerCache.answers != "[]") {
                    injectConfig(loadUrl, webView, "aa_answers", AnswerCache.answers)
                    logI("aa_answers injected: " + AnswerCache.answers.length + " chars")
                }
                val jsCode = when (mode) {
                    // QUICK/STANDARD 统一用通用答题脚本（Vue2/Vue3 双适配，mode 由 __aa_config 区分）；
                    // 标准模式 3.140 前用旧 standard.js（Vue2 专属），在 Vue3 exercise.html 上已失效，废弃。
                    AutoAnswerMode.QUICK -> quickJs

                    AutoAnswerMode.CUSTOM -> PK.customJs

                    AutoAnswerMode.STANDARD -> quickJs

                    AutoAnswerMode.DISABLE -> ""
                }
                // 极速模式 = 秒结算：环境加速（CSS 动画 0s/静音/自动画线/跳题 0ms）+ 秒结算答题。
                if (mode == AutoAnswerMode.QUICK) {
                    injectJsCode(fastSettleJs, loadUrl, webView)
                    logI("fastsettle injected")
                }
                if (jsCode.isEmpty()) {
                    logI("自动答题配置: ${mode.value}")
                } else {
                    injectJsCode(jsCode, loadUrl, webView)
                }
            } catch (e: Throwable) {
                logI("injectJs2PkPage failed: ${e.message}")
            }
        }
    }

    private fun injectJs2ResultPage() {
        val loadUrl = loadUrl ?: return
        val webView = webView ?: return
        webView.post {
            injectConfig(loadUrl, webView, "pk_cyclic_interval", PK.pkCyclicInterval)
            injectConfig(loadUrl, webView, "pk_cyclic_mode", PK.pkCyclicMode)

            if (PK.pkCyclic) {
                injectJsCode(cyclicJs, loadUrl, webView)
            }
        }
    }

    private fun hookAddJavascriptInterface(addJavascriptInterface: Method) {
        val openSchemaBeanClass = findClass(Classname.OPEN_SCHEMA_BEAN)
        val dataEncryptBeanClass = findClass(Classname.DATA_ENCRYPT_BEAN)
        var handle: HookHandle? = null
        var count = 0
        handle = addJavascriptInterface.also { it.isAccessible = true }
            .intercept("addJavascriptInterface") { chain ->
                val obj = chain.getArg(0)
                val name = chain.getArg(1)
                logI(name)
                when (name) {
                    "CommonWebView" -> {
                        val caller = XposedHelpers.callMethod(obj!!, "get", openSchemaBeanClass)!!
                        hookOpenSchema(caller.javaClass)
                        count++
                    }

                    "LeoSecureWebView" -> {
                        obj!!.javaClass.declaredFields.firstOrNull {
                            Map::class.java.isAssignableFrom(it.type)
                        }?.let {
                            val caller = (it.get(obj) as Map<*, *>)[dataEncryptBeanClass]!!
                            hookDataEncrypt(caller.javaClass)
                            // 2026-08-29：hook 解密命令拿题目答案（examVO.questions[].answer），
                            // 经 getAnswers() JS bridge 喂给 quick.js 顺序绘制（绕开 Vue 树取答案失败）。
                            runCatching {
                                (it.get(obj) as Map<*, *>)[findClass(Classname.DATA_DECRYPT_BEAN)]
                                    ?.let { dec -> hookDataDecrypt(dec.javaClass) }
                            }.onFailure { e -> logI("hookDataDecrypt setup failed: ${e.message}") }
                        }
                        count++
                    }

                    else -> {}
                }
                if (count >= 2) {
                    handle?.unhook()
                }
                chain.proceed()
            }
    }

    private fun hookOpenSchema(caller: Class<*>) {
        var lastSchemas: Any? = null
        caller.allMethod("call").forEach { m ->
            m.also { it.isAccessible = true }.intercept("openSchema_call") { chain ->
                if (!PK.pkCyclic && !PK.skipRanking) {
                    return@intercept chain.proceed()
                }
                val arg0 = chain.getArg(0)!!
                val schemas = XposedHelpers.getObjectField(arg0, "schemas") as Array<*>
                val url = Uri.parse(schemas[0].toString()).getQueryParameter("url")!!
                val targetUri = Uri.parse(url)
                when (targetUri.path) {
                    "/bh5/leo-web-study-group/motivation-honor-roll.html" -> {
                        when (targetUri.getQueryParameter("fromType")) {
                            "oralPkResult" -> {
                                XposedHelpers.callMethod(
                                    arg0,
                                    "trigger",
                                    webView,
                                    null,
                                    emptyArray<Any>()
                                )
                                null
                            }

                            "resultPageJs" -> {
                                // 防御：lastSchemas 未捕获（版本漂移/流程变化）时不再回放，放行原跳转避免 NPE
                                if (lastSchemas != null) {
                                    XposedHelpers.setObjectField(arg0, "schemas", lastSchemas)
                                    XposedHelpers.setBooleanField(arg0, "close", true)
                                }
                                chain.proceed()
                            }

                            else -> chain.proceed()
                        }
                    }

                    "/bh5/leo-web-oral-pk/result.html" -> chain.proceed()

                    else -> {
                        lastSchemas = schemas.copyOf(schemas.size)
                        chain.proceed()
                    }
                }
            }
        }
    }

    /**
     * 生成一条「真实连续线段」手写笔画（多点密集，含起笔/抬笔），供提交包 script/pathPoints 使用。
     *
     * 外挂检测点 = 答案只有单个点而非线段：若提交包 pathPoints 只有 1~3 个稀疏点，
     * 服务端会判定为「未作画 / 单点异常」。此方法沿一条略微弯曲的竖线生成 ~24 个连续点
     * （x 带轻微摆动、y 递增、首尾端点明确），构成完整笔画，避免被判单点。
     */
    private fun buildStrokeLine(): JSONArray {
        val pts = JSONArray()
        val x0 = 40.0
        val y0 = 30.0
        val n = 24
        for (i in 0 until n) {
            val t = i.toDouble() / (n - 1)
            // x 轻微左右摆动（模拟手写抖动），y 平滑下移，形成连续长线段
            val x = x0 + Math.sin(t * Math.PI) * 2.0 + (if (i % 2 == 0) 0.4 else -0.4)
            val y = y0 + t * 60.0
            pts.put(JSONObject().put("x", x).put("y", y))
        }
        return pts
    }

    private fun getSimulateCostTime(questionCnt: Int): Long {
        val interval = PK.quickModeInterval
        return questionCnt * interval.toLong()
    }

    /** quick.js 轮询取题目答案（JSON 数组 [{content,answer}]）。 */
    @JavascriptInterface
    fun getAnswers(): String = AnswerCache.answers

    /**
     * hook DataDecryptBean 解密命令：拦截解密结果明文（examVO.questions 带 answer），
     * 缓存答案列表供 quick.js 顺序绘制。失败静默（可能不是 PK 数据）。
     */
    private fun hookDataDecrypt(caller: Class<*>) {
        caller.allMethod("call").forEach { m ->
            m.also { it.isAccessible = true }.intercept("dataDecrypt_call") { chain ->
                val r = chain.proceed()
                if (r is String && r.contains("questions") && r.contains("pkIdStr")) {
                    runCatching {
                        val json = JSONObject(r)
                        val examVO = json.optJSONObject("examVO") ?: return@runCatching
                        val questions = examVO.getJSONArray("questions")
                        val arr = JSONArray()
                        for (i in 0 until questions.length()) {
                            val q = questions.getJSONObject(i)
                            val answer = q.optString("answer").ifBlank {
                                runCatching {
                                    q.optJSONArray("answers")?.takeIf { it.length() > 0 }?.getString(0).orEmpty()
                                }.getOrDefault("")
                            }
                            if (answer.isNotEmpty()) {
                                arr.put(JSONObject().put("content", q.optString("content")).put("answer", answer))
                            }
                        }
                        if (arr.length() > 0) {
                            AnswerCache.answers = arr.toString()
                            logI("answers cached via dataDecrypt: " + arr.length() + " questions")
                        }
                    }.onFailure {
                        logI("answers parse failed: ${it.message}")
                    }
                }
                r
            }
        }
    }

    private fun hookDataEncrypt(caller: Class<*>) {
        caller.allMethod("call").forEach { m ->
            m.also { it.isAccessible = true }.intercept("dataEncrypt_call") { chain ->
                val mode = PK.mode
                // 3.140 提交载荷兜底（秒结算/标准模式）：把提交包已有题的 userAnswer 修正为正确答案 + status=1。
                // 2026-08-29 真机 dump 实证（userAnswer="1" vs answer="<"）：**不**在此覆盖 userAnswer 为自定义答案
                // （Simian 改答案由 SimianHook EncryptResult 处理前端数据层，提交层覆盖会导致服务端判错）；
                // 也不改变 questions 结构（前端原生链推进后提交包是完整 N 题，兜底只修正答案字段）。
                // 2026-08-30：自定义结算时间独立开关（PK.settleEnabled）开启时，任意 PK 模式也进入改包（只改 costTime）。
                if (!Debug.debug && mode !in arrayOf(AutoAnswerMode.QUICK, AutoAnswerMode.STANDARD) && !PK.settleEnabled) {
                    return@intercept chain.proceed()
                }
                val bean = chain.getArg(0)
                val base64 = XposedHelpers.getObjectField(bean!!, "base64").toString()
                if (base64.isBlank()) {
                    return@intercept chain.proceed()
                }
                val json =
                    kotlin.runCatching { JSONObject(Base64.decode(base64, 0).decodeToString()) }
                        .getOrNull()
                        ?: return@intercept chain.proceed()
                if (!json.has("pkIdStr")) {
                    return@intercept chain.proceed()
                }
                if (!Debug.debug && mode !in arrayOf(AutoAnswerMode.QUICK, AutoAnswerMode.STANDARD) && !PK.settleEnabled) {
                    return@intercept chain.proceed()
                }
                runCatching {
                    val questions = json.getJSONArray("questions")
                    val correctLimit = Simian.customCorrectCount
                    var anyRewritten = false
                    for (i in 0 until questions.length()) {
                        val question = questions.getJSONObject(i)
                        val curTrueAnswer = question.optJSONObject("curTrueAnswer")
                        // 正确答案来源：question.answer -> answers[0]。
                        // **不**用 curTrueAnswer.recognizeResult（那是识别结果，可能错）；
                        // 都拿不到时不动该题（保留前端状态），避免写错答案/误判。
                        val correct = question.optString("answer").ifBlank {
                            runCatching {
                                question.optJSONArray("answers")?.takeIf { it.length() > 0 }?.getString(0).orEmpty()
                            }.getOrDefault("")
                        }
                        val shouldCorrect = correctLimit <= 0 || i < correctLimit
                        if (shouldCorrect && correct.isNotEmpty()) {
                            // 秒结算/标准：userAnswer=正确答案 + status=1（服务端按 userAnswer 判分）
                            question.put("userAnswer", correct)
                            question.put("status", 1)
                            anyRewritten = true
                        } else if (!shouldCorrect) {
                            question.put("status", 0)
                        }
                        // 2026-08-29 用户方案（画竖线不触发风控）：给提交包补一条**真实连续线段**手写笔画。
                        // 外挂检测点是「答案只有单个点而非线段」——必须生成多点密集的连续笔画路径
                        // （几十个点随 x/y 平滑前移），让服务端识别为真实手写，而非单点/稀疏断点。
                        val line = buildStrokeLine()
                        if (!question.has("script") || question.optString("script").isBlank()) {
                            question.put("script", JSONArray().put(line).toString())
                        }
                        curTrueAnswer?.put("pathPoints", JSONArray().put(line))
                    }
                    val questionCnt = json.getInt("questionCnt")
                    // 2026-08-30：结算时间覆盖 QUICK/STANDARD，或自定义结算时间独立开关（任意模式）
                    if (mode == AutoAnswerMode.QUICK || mode == AutoAnswerMode.STANDARD || PK.settleEnabled) {
                        val appropriateCostTime = appropriateCostTime.get()
                        // 2026-08-29：外挂检测点=答题时间 0.00s 触发封禁 → costTime 必须 >= 10ms(0.01s)。
                        // 自定义结算时间（秒转毫秒）低于 limit 时也保底，绝不出现 0.00s。
                        val settleMs = if (PK.settleTime > 0) PK.settleTime.toLong() else 0L
                        val costTime = when {
                            PK.quickModeMustWin && appropriateCostTime > 0 -> appropriateCostTime
                            settleMs > 0 -> settleMs
                            else -> getSimulateCostTime(questionCnt).coerceAtLeast(questionCnt.toLong())
                        }.coerceAtLeast(10L) // 最短 0.01s，防 0.00s 判外挂
                        logI("originCostTime: ${json.get("costTime")}, costTime: $costTime")
                        json.put("costTime", costTime)
                    }
                    // correctCnt 仅在实际改写发生时按 status==1 统计（自定义正确题数生效）；
                    // 未改写（前端自答/无正确答案源）时不动 correctCnt，避免覆盖服务端逻辑。
                    if (anyRewritten) {
                        var correctCnt = 0
                        for (i in 0 until questions.length()) {
                            if (questions.getJSONObject(i).optInt("status") == 1) correctCnt++
                        }
                        json.put("correctCnt", correctCnt)
                        logI("submit payload rewritten: correctCnt: $correctCnt/$questionCnt")
                    }
                    if (Debug.debug) {
                        thread {
                            val file = File(
                                currentApplication().externalCacheDir,
                                "${System.currentTimeMillis()}.json"
                            )
                            file.writeText(json.toString())
                        }
                    }
                    val newBase64 = Base64.encode(json.toString().toByteArray(), 0).decodeToString()
                    XposedHelpers.setObjectField(bean, "base64", newBase64)
                }.onFailure {
                    logI(it)
                }
                chain.proceed()
            }
        }
    }

    /** 等价旧 XposedBridge.invokeOriginalMethod：跳过所有 Hook 调用原方法。 */
    private fun invokeOriginal(loadUrl: Method, thisObject: Any?, args: Array<out Any?>): Any? {
        val invoker = XposedInit.self.getInvoker(loadUrl)
        invoker.setType(XposedInterface.Invoker.Type.ORIGIN)
        return invoker.invoke(thisObject, *args)
    }

    // ---------------------------------------------------------------------------
    // 秒结算 7 patch 挂点（2026-08-30）：PK 页 JS bundle 走 WebView 网络栈，不经 Retrofit，
    // 必须 hook WebViewClient.shouldInterceptRequest 拦截 .js 响应做文本 patch。
    // ---------------------------------------------------------------------------
    private fun hookShouldInterceptRequest() {
        val webViewClientClass = runCatching { findClass("android.webkit.WebViewClient") }.getOrNull()
            ?: return logI("WebViewHook: WebViewClient not found, skip intercept")
        val method = webViewClientClass.methods.firstOrNull {
            it.name == "shouldInterceptRequest" && it.parameterCount == 2 &&
                it.parameterTypes[0] == android.webkit.WebView::class.java
        } ?: return logI("WebViewHook: shouldInterceptRequest(WebView,String) not found, skip intercept")
        method.intercept("webview_intercept_js") { chain ->
            if (PK.mode != AutoAnswerMode.QUICK) {
                return@intercept chain.proceed()
            }
            val url = chain.getArg(1) as? String
            if (url == null || !shouldPatchPkBundle(url)) {
                return@intercept chain.proceed()
            }
            val patched = fetchAndPatchJs(url)
            if (patched == null) {
                return@intercept chain.proceed()
            }
            logI("PK bundle intercepted+patched: $url")
            patched
        }
    }

    private fun shouldPatchPkBundle(path: String): Boolean {
        val pkPage = path.contains("leo-web-oral-pk") ||
            path.contains("leo-web-math-exercise") ||
            path.contains("animation-oral")
        val jsFile = path.endsWith(".js") || path.contains(".js?")
        return pkPage && jsFile
    }

    private fun fetchAndPatchJs(url: String): android.webkit.WebResourceResponse? {
        return runCatching {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16; Build/QQ) AppleWebKit/537.36")
            conn.instanceFollowRedirects = true
            val bytes = conn.inputStream.use { it.readBytes() }
            val text = String(bytes, Charsets.UTF_8)
            val (newJs, cnt) = PkBundlePatcher.patch(text)
            if (cnt > 0) {
                logI("PK bundle fetched ${bytes.size}B patched x$cnt: $url")
                android.webkit.WebResourceResponse(
                    "application/javascript", "utf-8",
                    java.io.ByteArrayInputStream(newJs.toByteArray(Charsets.UTF_8))
                )
            } else {
                logI("PK bundle fetched but no patch match (version drift?): $url")
                null
            }
        }.getOrElse {
            logI("PK bundle fetch failed: ${it.message}")
            null
        }
    }
}