package cn.nizou.sxd.util

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/**
 * 通用抓包 / 改包引擎（供 RetrofitHook 的 okhttp Interceptor 调用）。
 *
 * 模块不打包 okhttp/okio，因此所有对宿主 okhttp 对象的操作都走反射：
 * - 读请求：url()/method()/encodedPath()/query()/body()
 * - 读请求体：RequestBody.writeTo(okio.Buffer) → readByteString().toByteArray()
 * - 请求体可回放：把原 body 包成实现 okhttp3.RequestBody 的 Proxy，writeTo 重放已缓冲字节，
 *   避免读一次 body 就把流消费掉导致原请求失败（安全回落：wrap 失败则放行原请求）。
 * - 改包规则：Packet.rules 为 JSON 数组，逐条按 path+method 匹配后改 query / body 字段。
 *   改包全程 try/catch，任何一步失败都安全回落放行原请求。
 * - 响应抓包：用 Response.peekBody(Long.MAX_VALUE).string() 读 body，不消费真正的响应流。
 *
 * 规则 JSON 示例（存到 rewrite_rules，默认 "[]"）：
 * ```
 * [
 *   {"path":"/leo-game-pk/android/math/pk/match","method":"POST","query":{"pointId":"3"}},
 *   {"path":"/leo-game-pk/android/math/pk/submit","method":"PUT","body":{"costTime":100}}
 * ]
 * ```
 * path 用 contains 匹配；method 留空则匹配任意方法。
 */
object PacketTool {

    private val fileLock = Any()

    private const val MAX_LOG_LEN = 1500

    /** 头部一行记录的上限（够放下 Cookie + 全部自定义头）。 */
    private const val MAX_HEADER_LEN = 4000

    /**
     * 抓包 / 改包主入口。传入已加完 isBackground 的 request，返回最终要 proceed 的 request。
     * [fullPath] 为完整编码路径（如 /leo-game-pk/android/math/pk/match），[method] 如 POST。
     */
    fun processRequest(
        request: Any,
        fullPath: String,
        method: String,
        classLoader: ClassLoader,
        capture: Boolean,
        writeFile: Boolean
    ): Any {
        var req = request
        val url = invokeMethod(request, "url")
        val query = invokeMethod(url, "query")?.toString() ?: ""

        val rule = if (Packet.rewrite) matchRule(fullPath, method) else null
        val needBody = capture || rule != null

        var bodyText: String? = null
        var wrapped: Any? = null
        if (needBody) {
            val body = invokeMethod(request, "body")
            if (body != null) {
                runCatching {
                    val bytes = readBodyBytes(body, classLoader)
                    bodyText = String(bytes, Charsets.UTF_8)
                    wrapped = wrapBody(body, bytes, classLoader)
                }
            }
        }

        if (capture) {
            val line = "[PKT][REQ] $method $fullPath" +
                (if (query.isNotBlank()) "?$query" else "") +
                (bodyText?.take(MAX_LOG_LEN)?.let { " body=$it" } ?: "")
            logI(line)
            writeFileSafe(writeFile, line)
            // ★ 请求头全量（2026-09-27 增强）：诊断 417/签名/设备链差异必须看 header，
            // 旧版只记 URL+body，无法比对（缺 x-shepherd-did / _grade / Cookie 等）。
            val hdrs = dumpHeaders(request)
            if (hdrs.isNotBlank()) {
                val hl = "[PKT][REQH] $method $fullPath  " + hdrs
                logI(hl)
                writeFileSafe(writeFile, hl)
            }
        }

        // 1) 请求体可回放（wrap 成功才换 body；失败用原请求，安全回落）
        if (wrapped != null) {
            req = runCatching {
                val b = invokeMethod(request, "newBuilder")
                invokeMethod(b, "method", method, wrapped)
                invokeMethod(b, "build")!!
            }.getOrElse { request }
        }

        // 2) 改包（失败安全回落原请求）
        if (rule != null) {
            req = runCatching {
                applyRewrite(req, rule, bodyText, classLoader)
            }.getOrElse { req }
        }
        return req
    }

    /** 响应抓包：code + peekBody 明文（不消费真正 body 流）。 */
    fun captureResponse(response: Any, fullPath: String, method: String, writeFile: Boolean) {
        runCatching {
            val code = invokeMethod(response, "code")
            val peeked = invokeMethod(response, "peekBody", Long.MAX_VALUE)
            val body = invokeMethod(peeked, "string")?.toString() ?: ""
            val line = "[PKT][RESP] $code $method $fullPath body=${body.take(MAX_LOG_LEN)}"
            logI(line)
            writeFileSafe(writeFile, line)
            // ★ 响应头全量（2026-09-27 增强）：`x-block-by`（solar-encoder/leo-auth）、
            // `Set-Cookie`（ks_*/sid 下发）都在响应头里，是排查 417/设备链的关键证据。
            val hdrs = dumpHeaders(response)
            if (hdrs.isNotBlank()) {
                val hl = "[PKT][RESPH] $code $method $fullPath  " + hdrs
                logI(hl)
                writeFileSafe(writeFile, hl)
            }
        }
    }

    // ---------------- 请求体读写 / 回放 ----------------

    private fun readBodyBytes(body: Any, classLoader: ClassLoader): ByteArray {
        val bufferClass = Class.forName("okio.Buffer", false, classLoader)
        val buffer = bufferClass.getDeclaredConstructor().newInstance()
        invokeMethod(body, "writeTo", buffer) // RequestBody.writeTo(BufferedSink)；Buffer 实现 BufferedSink
        val byteString = invokeMethod(buffer, "readByteString")
        return invokeMethod(byteString, "toByteArray") as ByteArray
    }

    /**
     * 把原 RequestBody 包成可回放 Proxy：writeTo 时重放已缓冲的字节，contentLength 返回字节数，
     * contentType 透传原值；isOneShot/isDuplex 返回 false（可重复读，避免读一次就废）。
     */
    private fun wrapBody(original: Any, bytes: ByteArray, classLoader: ClassLoader): Any {
        val bodyInterface = Class.forName("okhttp3.RequestBody", false, classLoader)
        val handler = InvocationHandler { _, method, args ->
            when (method.name) {
                "contentType" -> invokeMethod(original, "contentType")
                "contentLength" -> bytes.size.toLong()
                "writeTo" -> {
                    invokeMethod(args!![0], "write", bytes) // BufferedSink.write(byte[])
                    null
                }
                "isOneShot", "isDuplex" -> false
                else -> {
                    val delegate = original.javaClass.methods.firstOrNull {
                        it.name == method.name && it.parameterCount == (args?.size ?: 0)
                    }
                    if (delegate != null) {
                        delegate.isAccessible = true
                        delegate.invoke(original, *(args ?: emptyArray()))
                    } else {
                        null
                    }
                }
            }
        }
        return Proxy.newProxyInstance(classLoader, arrayOf(bodyInterface), handler)
    }

    // ---------------- 改包 ----------------

    private fun matchRule(fullPath: String, method: String): JSONObject? {
        val rules = runCatching { JSONArray(Packet.rules) }.getOrNull() ?: return null
        for (i in 0 until rules.length()) {
            val r = rules.getJSONObject(i)
            val p = r.optString("path", "")
            if (p.isNotBlank() && fullPath.contains(p)) {
                val m = r.optString("method", "")
                if (m.isBlank() || m.equals(method, true)) return r
            }
        }
        return null
    }

    private fun applyRewrite(req: Any, rule: JSONObject, bodyText: String?, classLoader: ClassLoader): Any {
        var r = req

        // 改 query 参数
        val query = rule.optJSONObject("query")
        if (query != null && query.length() > 0) {
            val url = invokeMethod(r, "url")
            val ub = invokeMethod(url, "newBuilder")
            val names = query.keys()
            while (names.hasNext()) {
                val k = names.next()
                invokeMethod(ub, "setQueryParameter", k, query.getString(k))
            }
            val nu = invokeMethod(ub, "build")
            val rb = invokeMethod(r, "newBuilder")
            invokeMethod(rb, "url", nu)
            r = invokeMethod(rb, "build")!!
        }

        // 改 JSON body 字段（仅当 body 为 JSON 文本才改；非 JSON 原样放行）
        val body = rule.optJSONObject("body")
        if (body != null && body.length() > 0 && bodyText != null && bodyText.trimStart().startsWith("{")) {
            val jo = JSONObject(bodyText)
            val keys = body.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                jo.put(k, body.get(k))
            }
            val newText = jo.toString()
            val contentType = contentTypeString(r, classLoader) ?: "application/json; charset=utf-8"
            val bodyInterface = Class.forName("okhttp3.RequestBody", false, classLoader)
            val mediaType = createMediaType(contentType, classLoader)
            val newBody = invokeStaticMethod(bodyInterface, "create", mediaType, newText)
            val method = invokeMethod(r, "method").toString()
            val rb = invokeMethod(r, "newBuilder")
            invokeMethod(rb, "method", method, newBody)
            r = invokeMethod(rb, "build")!!
        }
        return r
    }

    private fun contentTypeString(req: Any, classLoader: ClassLoader): String? {
        return runCatching {
            val body = invokeMethod(req, "body") ?: return null
            val ct = invokeMethod(body, "contentType") ?: return null
            invokeMethod(ct, "toString")?.toString()
        }.getOrNull()
    }

    private fun createMediaType(contentType: String, classLoader: ClassLoader): Any {
        val mediaTypeClass = Class.forName("okhttp3.MediaType", false, classLoader)
        // okhttp4 用 get(String)，okhttp3 用 parse(String)；逐类回退
        runCatching { return invokeStaticMethod(mediaTypeClass, "get", contentType)!! }
        runCatching { return invokeStaticMethod(mediaTypeClass, "parse", contentType)!! }
        throw IllegalStateException("无法构造 MediaType: $contentType")
    }

    // ---------------- 文件 / 日志 ----------------

    /**
     * 把 okhttp `Request`/`Response` 的 `headers()` 拼成一行文本。
     *
     * 走反射读 `headers()`，再用其 `toString()`（okhttp Headers.toString 输出
     * `Name: value\nName: value`），把换行压成 `; `。读不到时返回空串（不抛）。
     */
    private fun dumpHeaders(any: Any?): String = runCatching {
        val h = invokeMethod(any, "headers") ?: return@runCatching ""
        val s = invokeMethod(h, "toString")?.toString() ?: return@runCatching ""
        s.replace("\r", "")
            .split("\n")
            .filter { it.isNotBlank() }
            .joinToString("; ")
            .take(MAX_HEADER_LEN)
    }.getOrDefault("")


    private fun writeFileSafe(enabled: Boolean, line: String) {
        if (!enabled) return
        runCatching {
            val app = currentApplication()
            val dir = app.externalCacheDir ?: app.cacheDir
            val file = File(dir, "packet_capture.log")
            synchronized(fileLock) {
                file.appendText(line + "\n", Charsets.UTF_8)
            }
        }
    }

    // ---------------- 反射（按名+参数个数匹配，反射自动拆装箱，兼容原始类型） ----------------

    private fun invokeMethod(obj: Any?, name: String, vararg args: Any?): Any? {
        val o = obj ?: return null
        var c: Class<*>? = o.javaClass
        while (c != null) {
            for (m in c.declaredMethods) {
                if (m.name == name && m.parameterCount == args.size) {
                    m.isAccessible = true
                    return m.invoke(o, *args)
                }
            }
            c = c.superclass
        }
        throw NoSuchMethodException("$name(${args.size}) in $o")
    }

    private fun invokeStaticMethod(clazz: Class<*>, name: String, vararg args: Any?): Any? {
        for (m in clazz.methods) {
            if (m.name == name && m.parameterCount == args.size && Modifier.isStatic(m.modifiers)) {
                m.isAccessible = true
                return m.invoke(null, *args)
            }
        }
        throw NoSuchMethodException("static $name(${args.size}) in $clazz")
    }
}
