package cn.nizou.sxd.hook

import cn.nizou.sxd.Classname
import cn.nizou.sxd.entities.AutoAnswerMode
import cn.nizou.sxd.util.PK
import cn.nizou.sxd.util.Packet
import cn.nizou.sxd.util.PkNativePrefs
import cn.nizou.sxd.util.PacketTool
import cn.nizou.sxd.util.PkBundlePatcher
import cn.nizou.sxd.util.ProvinceRegionPrefs
import cn.nizou.sxd.util.Practice
import cn.nizou.sxd.util.SimianV2AutomationPrefs
import cn.nizou.sxd.util.SettingsPrefs
import cn.nizou.sxd.util.UserInfoStore
import cn.nizou.sxd.util.XposedHelpers
import cn.nizou.sxd.util.logI
import io.github.libxposed.api.XposedInterface
import org.json.JSONObject
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class RetrofitHook(
    self: XposedInterface,
    classLoader: ClassLoader
) : BaseHook(self, classLoader), InvocationHandler {

    override val name: String
        get() = "RetrofitHook"

    override fun startHook() {
        val retrofitClass = findClass(Classname.RETROFIT)
        // 2026-08-29：对**所有** Retrofit.create 加拦截器（不只 OralApiService）——
        // 用户信息/cookie 可能来自其它 service（pk/home、user 等）；identity 去重防重复代理。
        retrofitClass.findMethod("create", Class::class.java).intercept("retrofit_create") { chain ->
            val r = chain.proceed()
            chain.thisObject?.let(::addInterceptor)
            r
        }
    }

    private val patchedRetrofits = java.util.Collections.newSetFromMap(
        java.util.IdentityHashMap<Any, Boolean>()
    )

    private fun addInterceptor(retrofit: Any) {
        if (!patchedRetrofits.add(retrofit)) return
        logI("addInterceptor")
        // 2026-08-29（真机定案）：retrofit.callFactory **不是** okhttp3.OkHttpClient，而是 leo-network 的
        // Call.Factory 装饰器（3.140 上为包 aq 的类 g，其字段 a 持有真正的 OkHttpClient）。直接在装饰器上读
        // 「interceptors」字段/方法全部失败（真机 logcat：interceptors(0 args) in class aq.g），此前 3 路径
        // 兜底并不生效。正确做法：沿 Call.Factory 装饰器链向下解包到真正的 okhttp3.OkHttpClient
        // （okhttp3 在本宿主**未混淆**，类名/字段名保持原名），把本模块拦截器注入其 interceptors 字段。
        // 该字段是 final List，用 setObjectField 整体替换成新列表（与旧方案一致）。失败容错跳不崩。
        runCatching {
            val clientClass = findClass("okhttp3.OkHttpClient")
            val realClient = unwrapToOkHttpClient(
                XposedHelpers.getObjectField(retrofit, "callFactory"), clientClass
            )
            if (realClient == null) {
                logI("addInterceptor skipped (no OkHttpClient in callFactory chain)")
                return@runCatching
            }
            val interceptorClass = findClass(Classname.INTERCEPTOR)
            val myInterceptor =
                Proxy.newProxyInstance(interceptorClass.classLoader, arrayOf(interceptorClass), this)
            val interceptors = XposedHelpers.getObjectField(realClient, "interceptors") as List<*>
            XposedHelpers.setObjectField(realClient, "interceptors", (interceptors + myInterceptor).toList())
            logI("addInterceptor OK: " + (interceptors.size + 1))
        }.onFailure {
            logI("addInterceptor skipped (field drift): " + it.message)
        }
    }

    /**
     * 沿 Call.Factory 装饰器链（如 aq.g 包装 okhttp3.OkHttpClient）向下解包，返回真正的
     * okhttp3.OkHttpClient；找不到返回 null。只要目标类 / Call.Factory 接口名匹配即跟随。
     */
    private fun unwrapToOkHttpClient(start: Any?, clientClass: Class<*>): Any? {
        var obj = start
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        while (obj != null && seen.add(obj)) {
            if (clientClass.isInstance(obj)) return obj
            var next: Any? = null
            for (f in obj.javaClass.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                f.isAccessible = true
                val v = runCatching { f.get(obj) }.getOrNull() ?: continue
                if (clientClass.isInstance(v)) { next = v; break }
                // 仅跟随 Call.Factory 装饰器链，避免误入无关对象
                if (v.javaClass.interfaces.any { it.name == "okhttp3.Call\$Factory" }) { next = v; break }
            }
            obj = next
        }
        return obj
    }

    override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
        if (method.name != "intercept") {
            return if (args == null) {
                method.invoke(this)
            } else {
                method.invoke(this, *args)
            }
        }
        val chain = args!![0]
        return intercept(chain)
    }

    private fun intercept(chain: Any): Any? {
        val request = XposedHelpers.callMethod(chain, "request")!!
        val httpUrl = XposedHelpers.callMethod(request, "url")!!
        val method = XposedHelpers.callMethod(request, "method").toString()
        val fullPath = XposedHelpers.callMethod(httpUrl, "encodedPath")?.toString() ?: "/"

        // AutoPK 原生链路：捕获 PK match 请求 pointId（纯只读，见 PkNativeSession），受 PkNativePrefs 控制
        runCatching {
            if (PkNativePrefs.enabled && fullPath.endsWith("/math/pk/match") && method == "POST") {
                val pointIdQuery = XposedHelpers.callMethod(httpUrl, "queryParameter", "pointId") as? String
                PkNativeSession.onMatchRequest(pointIdQuery)
            }
        }.onFailure { }

        // 1) 保持现有 isBackground 逻辑不破坏（自动上分）
        var req: Any = request
        if (Practice.autoHonor && fullPath.startsWith("/leo-math/android/exams") && method in arrayOf("POST", "PUT")) {
            req = buildIsBackground0(request)
        }
        // Host-confirmed endpoint: GET /leo-exam/android/paper/list uses provinceId.
        if (method == "GET" && fullPath == PAPER_LIST_PATH && ProvinceRegionPrefs.enabled) {
            ProvinceRegionPrefs.selectedProvinceId()?.let { provinceId ->
                req = buildProvinceId(req, provinceId)
                logI("province override: " + ProvinceRegionPrefs.selectedName + " -> " + provinceId)
            }
        }

        // 2) 通用抓包 / 改包（开关默认关；改包失败安全回落放行原请求）
        if (Packet.capture || Packet.rewrite) {
            PacketTool.processRequest(req, fullPath, method, classLoader, Packet.capture, Packet.writeFile)
                ?.let { req = it }
        }

        // 3) proceed
        var response = XposedHelpers.callMethod(chain, "proceed", req)

        // 3.5) PK 秒结算 7 patch（MITM 移植，见 util/PkBundlePatcher.kt）：
        //      仅极速(QUICK)模式 + PK 相关 .js bundle 响应；对 body 文本做判题恒真/跳题0ms/
        //      OCR回调恒真等替换（进页即改源码，绕开 Vue3 closure 拿不到组件实例的问题）。
        runCatching {
            if (response != null && (PK.mode == AutoAnswerMode.QUICK || SimianV2AutomationPrefs.quickSubmit) && shouldPatchPkBundle(fullPath)) {
                val body = XposedHelpers.callMethod(response, "body")
                val text = XposedHelpers.callMethod(body, "string") as? String
                if (text != null) {
                    val (newJs, cnt) = PkBundlePatcher.patch(text)
                    if (cnt > 0) {
                        val newBody = rebuildResponseBody(response, newJs)
                        XposedHelpers.setObjectField(response, "body", newBody)
                        logI("PK bundle patched x$cnt: $fullPath")
                    }
                }
            }
        }.onFailure { logI("pk bundle patch failed: ${it.message}") }

        // 3.75) /paper/selectors returns the live FilterItem{id,name} mapping for provinces.
        runCatching {
            if (response != null && fullPath == PAPER_SELECTOR_PATH) {
                val body = XposedHelpers.callMethod(response, "peekBody", 1024L * 1024L)
                ProvinceRegionPrefs.captureSelectorMapping(XposedHelpers.callMethod(body, "string") as? String)
            }
        }.onFailure { logI("province selector capture failed: " + it.message) }

        // 4) 响应抓包（capture 开启才用 peekBody 读，不消费真正的 body 流）
        if (Packet.capture) {
            PacketTool.captureResponse(response!!, fullPath, method, Packet.writeFile)
        }

        // 5) 用户信息 + Cookie 采集（用户信息卡片数据源，peekBody 不消费 body 流）
        runCatching {
            if (response != null) {
                val headers = XposedHelpers.callMethod(response, "headers")
                val setCookie = XposedHelpers.callMethod(headers, "get", "Set-Cookie") as? String
                UserInfoStore.updateCookie(setCookie)
                if (fullPath.contains("user") || fullPath.contains("home") || fullPath.contains("account") || fullPath.contains("profile") || fullPath.contains("pk/home")) {
                    val body = XposedHelpers.callMethod(response, "peekBody", 1024L * 1024L)
                    val text = XposedHelpers.callMethod(body, "string") as? String
                    UserInfoStore.updateFromJson(text)
                }
                // 6) 推荐知识点自动记录（真自定义分数默认值）：App 首页自动请求
                // GET /leo-math/android/recommend/keypoint，返回第一个推荐知识点 keypointId + 每局题数
                if (fullPath.contains("/leo-math/android/recommend/keypoint")) {
                    val body = XposedHelpers.callMethod(response, "peekBody", 1024L * 1024L)
                    val text = XposedHelpers.callMethod(body, "string") as? String
                    captureRecommendKeypoint(text)
                }
            }
        }.onFailure {
            // 用户信息采集属可选功能，失败静默，不打扰正常请求
        }

        // AutoPK 原生链路：只读解析 PK match / history/detail 响应（明文 JSON 时才有 pkIdStr），受 PkNativePrefs 控制
        runCatching {
            if (PkNativePrefs.enabled && response != null && fullPath.contains("/leo-game-pk/") &&
                (fullPath.endsWith("/math/pk/match") || fullPath.endsWith("/math/pk/history/detail"))
            ) {
                val body = XposedHelpers.callMethod(response, "peekBody", 4L * 1024L * 1024L)
                val text = XposedHelpers.callMethod(body, "string") as? String
                PkNativeSession.onMatchResponse(text)
            }
        }.onFailure { error -> logI("AutoPK response parse skipped: " + error.message) }

        return response
    }

    /**
     * 解析推荐知识点响应 `{"results":[{"name":"8、7、6加几","keypointId":41,"questionCnt":10,...}],...}`，
     * 取第一个 keypointId + questionCnt 写入 prefs（ScorePump 真自定义分数默认值）。
     */
    private fun captureRecommendKeypoint(text: String?) {
        if (text.isNullOrBlank()) return
        runCatching {
            val json = JSONObject(text)
            val results = json.optJSONArray("results") ?: return
            if (results.length() == 0) return
            val first = results.getJSONObject(0)
            val kp = first.optInt("keypointId", 0)
            val cnt = first.optInt("questionCnt", 0)
            if (kp > 0) {
                SettingsPrefs.writeString("custom_score_keypoint", kp.toString())
                logI("recommend keypoint auto-recorded: id=$kp questionCnt=$cnt")
            }
            if (cnt > 0) {
                SettingsPrefs.writeString("custom_score_limit", cnt.toString())
            }
        }.onFailure {
            logI("captureRecommendKeypoint failed: ${it.message}")
        }
    }
    private fun buildProvinceId(request: Any, provinceId: Int): Any {
        val url = XposedHelpers.callMethod(request, "url")!!
        val urlBuilder = XposedHelpers.callMethod(url, "newBuilder")!!
        XposedHelpers.callMethod(urlBuilder, "setQueryParameter", "provinceId", provinceId.toString())
        val newBuilder = XposedHelpers.callMethod(request, "newBuilder")!!
        XposedHelpers.callMethod(newBuilder, "url", XposedHelpers.callMethod(urlBuilder, "build")!!)
        return XposedHelpers.callMethod(newBuilder, "build")!!
    }

    /** 把 exams 请求 query 加 isBackground=0。 */
    private fun buildIsBackground0(request: Any): Any {
        val url = XposedHelpers.callMethod(request, "url")!!
        val urlBuilder = XposedHelpers.callMethod(url, "newBuilder")!!
        XposedHelpers.callMethod(urlBuilder, "setQueryParameter", "isBackground", "0")
        val newUrl = XposedHelpers.callMethod(urlBuilder, "build")!!
        val newBuilder = XposedHelpers.callMethod(request, "newBuilder")!!
        XposedHelpers.callMethod(newBuilder, "url", newUrl)
        return XposedHelpers.callMethod(newBuilder, "build")!!
    }

    private companion object {
        const val PAPER_LIST_PATH = "/leo-exam/android/paper/list"
        const val PAPER_SELECTOR_PATH = "/leo-exam/android/paper/selectors"
    }

    /** PK 相关前端 JS bundle（leo-web-oral-pk / leo-web-math-exercise / animation-oral 下的 .js）。 */
    private fun shouldPatchPkBundle(path: String): Boolean {
        val pkPage = path.contains("leo-web-oral-pk") ||
            path.contains("leo-web-math-exercise") ||
            path.contains("animation-oral")
        val jsFile = path.endsWith(".js") || path.contains(".js?")
        return pkPage && jsFile
    }

    /**
     * 反射重建 okhttp3.ResponseBody（避免模块直接依赖 okhttp 类型；兼容 3.x static create /
     * 4.x Companion.create）。替换原 body 字段（private final，反射 setAccessible 可改）。
     */
    private fun rebuildResponseBody(response: Any, newText: String): Any {
        val body = XposedHelpers.callMethod(response, "body")
        val contentType = XposedHelpers.callMethod(body, "contentType")
        val mediaTypeClass = Class.forName("okhttp3.MediaType")
        val create = runCatching {
            Class.forName("okhttp3.ResponseBody\$Companion")
                .getMethod("create", mediaTypeClass, String::class.java)
        }.getOrElse {
            Class.forName("okhttp3.ResponseBody")
                .getMethod("create", mediaTypeClass, String::class.java)
        }
        create.isAccessible = true
        return create.invoke(null, contentType, newText)
    }
}