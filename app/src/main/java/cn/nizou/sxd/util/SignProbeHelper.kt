package cn.nizou.sxd.util

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主域 `sign` 参数白盒探针（临时工具，sign 破解后删除）。
 *
 * ## 背景
 *
 * 主域（xyks.yuanfudao.com）业务接口要求 URL 带 32 位 MD5 的 `sign` 参数，
 * 缺它一律 417 `x-block-by: solar-encoder`。sign 已确证是 (路径,参数集) 纯函数，
 * 但 3900+ 黑盒候选全部未命中 —— 需要白盒拿到调用栈。
 *
 * 独立探针模块（cn.sxd.signprobe）配置正确却始终未被 LSPosed 注入
 * （db enabled=1 / scope 正确 / 重启宿主无效），疑似手工构建的 APK 被框架静默拒绝。
 * 本探针借 **cn.nizou.sxd（已验证可注入的模块）** 的身份加载。
 *
 * ## 只读纪律
 *
 * 不修改任何请求/返回值/参数 —— 只在命中时落盘 + 打栈。每处 hook 包 runCatching。
 *
 * ## 落盘
 *
 * `/data/data/com.fenbi.android.leo/files/signprobe.log`（root 可读）。
 * 同时走框架 log()（LSPosed 模块日志可筛 AutoOral/SignProbe tag）。
 */
object SignProbeHelper {

    const val TAG = "SignProbe"

    private const val LOG_FILE = "signprobe.log"
    private const val HOST_PKG = "com.fenbi.android.leo"
    private const val STACK_DEPTH = 24

    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var logFile: File? = null

    private fun file(): File {
        logFile?.let { return it }
        val f = File("/data/data/$HOST_PKG/files/$LOG_FILE")
        logFile = f
        return f
    }

    @Synchronized
    fun log(xlog: ((Int, String, String) -> Unit)?, line: String) {
        runCatching { file().appendText("${fmt.format(Date())} $line\n") }
            .onFailure { runCatching { Log.i(TAG, "$line (write failed: ${it.message})") } }
        runCatching { xlog?.invoke(Log.INFO, TAG, line) }
    }

    /** 生成调用栈文本（跳过探针自身帧）。 */
    private fun stackLines(): List<String> {
        val frames = Throwable().stackTrace
            .drop(1)
            .filterNot { it.className.startsWith("cn.nizou.sxd") }
            .take(STACK_DEPTH)
        return frames.mapIndexed { i, e ->
            "  #$i at ${e.className}.${e.methodName}(${e.fileName}:${e.lineNumber})"
        }
    }

    private fun isInteresting(v: String?): Boolean =
        !v.isNullOrEmpty() && (
            v.contains("yuanfudao.com") || v.contains("sign") || v.contains("solar")
            )

    /**
     * 安装全部探针（在宿主 Application.attach 之后调用）。
     *
     * @param hookExecutable 版本感知的 hook builder（宿主 XposedInit 的同名方法）
     * @param xlog 框架日志函数（XposedModule.log）
     */
    fun install(
        hookExecutable: (String, java.lang.reflect.Executable) -> Any,
        xlog: (Int, String, String) -> Unit,
        hostClassLoader: ClassLoader,
    ) {
        // 关键：宿主的 okhttp3 类要用**宿主 classLoader** 查 —— 模块自己的
        // classLoader 里没有 okhttp（模块 compileOnly 依赖不打进包），
        // Class.forName 会抛 ClassNotFoundException。
        // java.security.MessageDigest 是系统类，两个 loader 一致，不受影响。
        val cl = hostClassLoader
        val write: (String) -> Unit = { line -> log(xlog, line) }
        val writeStack: (String) -> Unit = { prefix ->
            log(xlog, prefix + " STACK:")
            stackLines().forEach { log(xlog, it) }
        }

        runCatching {
            fun hookMethod(
                className: String,
                methodName: String,
                id: String,
                paramTypes: Array<Class<*>>? = null,
                onCall: (List<Any?>) -> Unit,
            ) {
                val cls = Class.forName(className, false, cl)
                val methods = if (paramTypes != null) {
                    listOf(cls.getDeclaredMethod(methodName, *paramTypes))
                } else {
                    cls.declaredMethods.filter { it.name == methodName }
                }
                if (methods.isEmpty()) {
                    write("skip $className.$methodName: not found")
                    return
                }
                methods.forEachIndexed { i, m ->
                    m.isAccessible = true
                    @Suppress("UNCHECKED_CAST")
                    val builder = hookExecutable("${id}_$i", m) as io.github.libxposed.api.XposedInterface.HookBuilder
                    builder.intercept { chain ->
                        val args = chain.args
                        runCatching { onCall(args) }
                        chain.proceed()
                    }
                }
                write("hooked $className.$methodName x${methods.size}")
            }
            // ---- 1b前置. 惰性 hook sign native 入口：SecureStub.getEncodedP ----
            // libRequestEncoder.so 由 JNI_OnLoad 动态注册 getEncodedP。
            // 三轮真机取证：appClassLoader（及一切委托到它的候选）里的 SecureStub
            // 都只有 getString —— vgo 虚拟环境的私有 loader 不在委托链上可见。
            // 决定性方案：hook System.loadLibrary —— JNI_OnLoad 里 FindClass 用
            // **调用方的 classloader**，即 System.loadLibrary("RequestEncoder")
            // 那一刻调用者所在 loader 就是注册目标 loader，当场枚举 + hook。
            fun tryHookSecureStubIn(loader: ClassLoader, from: String) {
                runCatching {
                    val ss = Class.forName("com.yuanfudao.android.leo.stub.SecureStub", false, loader)
                    val targets = ss.declaredMethods.filter { m ->
                        java.lang.reflect.Modifier.isNative(m.modifiers) ||
                            m.name.startsWith("getEncodedP")
                    }
                    if (targets.isEmpty()) {
                        write("SecureStub probe($from, loader=$loader): only " +
                            ss.declaredMethods.joinToString(",") { it.name })
                        return
                    }
                    targets.forEach { m ->
                        m.isAccessible = true
                        @Suppress("UNCHECKED_CAST")
                        val builder = hookExecutable("securestub_native", m) as io.github.libxposed.api.XposedInterface.HookBuilder
                        builder.intercept { chain ->
                            val args = chain.args
                            write("SecureStub.${m.name}(" +
                                "s1=${args.getOrNull(0)}, " +
                                "s2=${args.getOrNull(1)}, " +
                                "i=${args.getOrNull(2)})")
                            val r = chain.proceed()
                            write("  -> ${r}")
                            r
                        }
                        write("hooked SecureStub.${m.name} via loader=$loader ($from)")
                    }
                }.onFailure { write("SecureStub probe($from) failed: $it") }
            }

            // hook System.loadLibrary / System.load：登记每个调用者的 loader；
            // RequestEncoder 的 loader 到手后立即枚举 SecureStub。
            hookMethod("java.lang.System", "loadLibrary", "sys_loadlib") { args ->
                val lib = args.getOrNull(0)?.toString().orEmpty()
                val frame = Throwable().stackTrace
                    .firstOrNull { it.className.startsWith("com.fenbi") || it.className.startsWith("com.yuanfudao") }
                val loader = frame?.let {
                    runCatching { Class.forName(it.className, false, cl).classLoader }.getOrNull()
                }
                write("loadLibrary($lib) by=${frame?.className} loader=${loader?.toString()?.take(80)}")
                if (lib.contains("RequestEncoder", true)) {
                    loader?.let { tryHookSecureStubIn(it, "loadLibrary") }
                }
            }
            hookMethod("java.lang.System", "load", "sys_load") { args ->
                val path = args.getOrNull(0)?.toString().orEmpty()
                if (!path.contains("RequestEncoder", true)) return@hookMethod
                val frame = Throwable().stackTrace
                    .firstOrNull { it.className.startsWith("com.fenbi") || it.className.startsWith("com.yuanfudao") }
                val loader = frame?.let {
                    runCatching { Class.forName(it.className, false, cl).classLoader }.getOrNull()
                }
                write("load($path) by=${frame?.className} loader=${loader?.toString()?.take(80)}")
                loader?.let { tryHookSecureStubIn(it, "load") }
            }

            // ---- 1/2. HttpUrl.Builder 写 query ----
            hookMethod("okhttp3.HttpUrl\$Builder", "addQueryParameter", "url_add_query") { args ->
                val k = args.getOrNull(0)?.toString()
                val v = args.getOrNull(1)?.toString()
                if (k == "sign") {
                    // sign 已被写入 —— 网络栈已活。若 loadLibrary 时机错过（如库在
                    // attach 之前已加载），再补一次「全 loader 扫描」兜底。
                    tryHookSecureStubIn(cl, "sign-fallback")
                    write("addQueryParameter($k=$v)")
                    writeStack("  ^^")
                } else if (k == "_productId") {
                    write("addQueryParameter($k=$v)")
                }
            }
            // ---- 1c. 定位 sign 拦截器的真实类名并落盘其方法表 ----
            // 真机栈显示 sign 写入点上游是 pv1.intercept / qm1.invoke（R8 混淆名）。
            // 在运行时按名字找到宿主类，dump declaredMethods 签名 —— 离线即可在
            // apktool_out 里按签名特征反查混淆前的实现（smali 里同名类在别的 dex 分卷）。
            runCatching {
                val hostCl = cl
                for (name in listOf("pv1", "qm1")) {
                    val c = runCatching { Class.forName(name, false, hostCl) }.getOrNull() ?: continue
                    write("HOSTCLASS $name methods:")
                    c.declaredMethods.forEach { m ->
                        write("  ${m.name}${java.lang.reflect.Modifier.toString(m.modifiers)}(${m.parameterTypes.joinToString(",") { it.name }}) -> ${m.returnType.name}")
                    }
                }
            }.onFailure { write("hostclass dump failed: $it") }
            hookMethod("okhttp3.HttpUrl\$Builder", "addEncodedQueryParameter", "url_add_enc") { args ->
                val k = args.getOrNull(0)?.toString()
                val v = args.getOrNull(1)?.toString()
                if (k == "sign" || isInteresting(v)) {
                    write("addEncodedQueryParameter($k=$v)")
                    writeStack("  ^^")
                }
            }

            // ---- 3. MessageDigest.digest(byte[]) —— MD5 输入串就在这里 ----
            hookMethod(
                "java.security.MessageDigest", "digest", "md5_digest",
                paramTypes = arrayOf(ByteArray::class.java),
            ) { args ->
                val input = args.getOrNull(0) as? ByteArray ?: return@hookMethod
                val stack = Throwable().stackTrace
                val hostFrame = stack.firstOrNull {
                    it.className.startsWith("com.fenbi") || it.className.startsWith("com.yuanfudao")
                } ?: return@hookMethod
                write("MessageDigest.digest(len=${input.size}) <- ${hostFrame.className}.${hostFrame.methodName}")
                if (input.size in 1..512) {
                    write("  input(bytes)=${input.toString(Charsets.ISO_8859_1)}")
                }
            }
            // ---- 4. digest() 无参 ----
            hookMethod("java.security.MessageDigest", "digest", "md5_digest_noarg") { _ ->
                val stack = Throwable().stackTrace
                val hostFrame = stack.firstOrNull {
                    it.className.startsWith("com.fenbi") || it.className.startsWith("com.yuanfudao")
                } ?: return@hookMethod
                write("MessageDigest.digest() <- ${hostFrame.className}.${hostFrame.methodName}")
            }

            // ---- 5. HttpUrl.toString ----
            hookMethod("okhttp3.HttpUrl", "toString", "httpurl_tostring") { _ -> }
            // 拦不到返回值（本 helper 不拿到 chain），改为在 proceed 外处理 ——
            // 见下方对 toString 的专门处理说明：HttpUrl.toString 的结果由 addQueryParameter
            // 栈已足够定位，这里只保留 hook 占位以便后续扩展。
        }.onFailure {
            write("SignProbe install FAILED: $it")
        }
        write("--- probes installed (via cn.nizou.sxd) ---")
    }
}