package cn.nizou.sxd.util

import android.graphics.PointF
import cn.nizou.sxd.XposedInit
import io.github.libxposed.api.XposedModule
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/**
 * native libauto_oral 加载（笔迹识别）。
 *
 * **延迟加载**：System.load 与 Xposed 框架（尤其 LSPosed standard）的 native 加载窗口冲突会
 * 直接 abort 进程（JNI 错误无法用 runCatching 捕获）。因此不在启动/attach 时加载，而是在
 * 首次真正使用笔迹功能时才加载，避开框架初始化窗口；加载失败仅影响笔迹，不影响其它功能。
 */
private val nativeLoaded = AtomicBoolean(false)

private val loadAttempted = AtomicBoolean(false)

/**
 * 3.140 适配（2026-08-29）：
 *  - 失败结果缓存：每进程只尝试一次，不再每局重复 System.load + 刷日志；
 *  - 现代 AGP 默认 extractNativeLibs=false，nativeLibraryDir 下没有解压的 .so，
 *    System.load 直接 FileNotFoundException；改为从模块 APK（lib/<abi>/）解压到模块 filesDir 再加载。
 * 说明：libauto_oral 是 Rust(jni crate) 笔迹路径生成库；即使加载失败，秒结算仍可工作——
 * 判题/提交的正确性由 WebViewHook.hookDataEncrypt（提交载荷改写）保证。
 */
private fun ensureNativeLoaded(): Boolean {
    if (nativeLoaded.get()) return true
    synchronized(nativeLoaded) {
        if (nativeLoaded.get()) return true
        if (loadAttempted.get()) return false
        loadAttempted.set(true)
        return try {
            val self = XposedInit.self
            var path: String? = File(self.moduleApplicationInfo.nativeLibraryDir, "libauto_oral.so")
                .takeIf { it.exists() }?.absolutePath
            if (path == null) {
                path = extractLibFromApk(self)?.absolutePath
            }
            if (path != null) {
                System.load(path)
                nativeLoaded.set(true)
                logI("libauto_oral loaded: " + path)
                true
            } else {
                logI("libauto_oral not found (nativeLibraryDir empty), strokes disabled")
                false
            }
        } catch (e: Throwable) {
            logI("libauto_oral load failed: " + e.message)
            false
        }
    }
}

/** extractNativeLibs=false 时从模块 APK 解压 .so 到模块 filesDir。 */
private fun extractLibFromApk(self: XposedModule): File? = runCatching {
    val apkFile = File(self.moduleApplicationInfo.sourceDir)
    val abi = self.moduleApplicationInfo.nativeLibraryDir
        .split(File.separator).lastOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: android.os.Build.SUPPORTED_ABIS.firstOrNull()
        ?: "arm64-v8a"
    val zip = ZipFile(apkFile)
    try {
        val entry = zip.getEntry("lib/" + abi + "/libauto_oral.so")
            ?: zip.getEntry("lib/arm64-v8a/libauto_oral.so")
            ?: return null
        val dir = File(self.moduleApplicationInfo.dataDir, "files/lib").apply { mkdirs() }
        val out = File(dir, "libauto_oral.so")
        zip.getInputStream(entry).use { input ->
            out.outputStream().use { it.write(input.readBytes()) }
        }
        logI("libauto_oral extracted from APK to " + out)
        out
    } finally {
        runCatching { zip.close() }
    }
}.getOrNull()

val String.nativeStrokes: List<Array<DoubleArray>>
    external get

/**
 * 纯 Kotlin 笔画生成（**不依赖 native**）。
 *
 * ## 为什么需要它（2026-09-27）
 *
 * `nativeStrokes` 由内置 `libauto_oral.so` 提供，但该 so 是 AOC(TinyHai) 编的
 * （Rust + jni crate）。反查其动态符号：**只导出 `JNI_OnLoad`，没有 `Java_*` 导出**，
 * 且内部字符串里查不到 `cn.nizou.sxd` / `Strokes` 之类注册目标类名 —— 它应是按
 * AOC 自己的类名 `RegisterNatives`。本模块调用 `nativeStrokes` 会抛
 * `UnsatisfiedLinkError`（或返回空）→ **script 为空 → 被判「未作画/单点」风控**。
 * 这就是「画笔提交」失效的根因。
 *
 * 本函数给出服务端能识别为真实手写的连续线段笔画：每条 24 个密集点、y 平滑下移、
 * x 带手抖摆动（与 [cn.nizou.sxd.hook.WebViewHook.buildStrokeLine] 同款思路，
 * 那条竖线方案在真机 PK 上验证不触发风控）。逐字符生成（答案 "12" → 两条笔画），
 * 字符间横向错开，避免叠成一点。
 */
fun String.kotlinStrokes(): List<Array<PointF>> {
    if (isEmpty()) return listOf(buildDenseStroke(40.0, 30.0))
    return mapIndexed { idx, _ ->
        buildDenseStroke(x0 = 40.0 + idx * 26.0, y0 = 30.0 + (idx % 2) * 3.0)
    }
}

/**
 * 带 seed 的纯 Kotlin 笔画（**每题起点/抖动不同**）。
 *
 * 服务端会比对多题笔迹是否完全雷同（雷同判定为机器作答）。pk-node 的
 * `strokes.buildPathPoints(answer, idx + 1, 'ARC')` 就是「按题号换 seed」，
 * 这里对齐同样语义：seed 只影响起点与手抖相位，不影响「长得像人手写」这一点。
 */
fun String.kotlinStrokesAt(seed: Int): List<Array<PointF>> {
    if (isEmpty()) return listOf(buildDenseStroke(40.0 + seed * 3.0, 30.0 + seed * 2.0, seed = seed))
    return mapIndexed { idx, _ ->
        buildDenseStroke(
            x0 = 40.0 + idx * 26.0 + (seed % 7) * 3.0,
            y0 = 30.0 + (idx % 2) * 3.0 + (seed % 5) * 2.0,
            seed = seed + idx,
        )
    }
}

/** 一条密集连续线段（24 点，带手抖摆动），坐标与 native 版同量级（数十 px）。 */
private fun buildDenseStroke(x0: Double, y0: Double, n: Int = 24, seed: Int = 0): Array<PointF> {
    val phase = (seed % 11) * 0.37
    return Array(n) { i ->
        val t = i.toDouble() / (n - 1)
        val x = x0 + kotlin.math.sin(t * Math.PI + phase) * 2.0 + (if (i % 2 == 0) 0.4 else -0.4)
        val y = y0 + t * 60.0
        PointF(x.toFloat(), y.toFloat())
    }
}

val String.strokes: List<Array<PointF>> get() {
    // 优先 native；失败/为空回落纯 Kotlin。
    // 注意 nativeStrokes 抛的是 Error(UnsatisfiedLinkError)，必须 catch Throwable，
    // 否则会直接把宿主崩掉（Java 的 catch(Exception) 接不住）。
    val native = runCatching { if (ensureNativeLoaded()) nativeStrokes else null }.getOrNull()
    if (!native.isNullOrEmpty()) {
        return native.map { it.map { p -> PointF(p[0].toFloat(), p[1].toFloat()) }.toTypedArray() }
            .also { logI("answer: $this, native strokes: ${it.size}") }
    }
    return kotlinStrokes().also { logI("answer: $this, kotlin strokes: ${it.size}") }
}

val String.pathPoints get(): List<Array<DoubleArray>> {
    val native = runCatching { if (ensureNativeLoaded()) nativeStrokes else null }.getOrNull()
    if (!native.isNullOrEmpty()) return native
    return kotlinStrokes().map { stroke ->
        stroke.map { doubleArrayOf(it.x.toDouble(), it.y.toDouble()) }.toTypedArray()
    }
}

fun List<Array<*>>.toJsonString(): String {
    return toJSONArray().toString()
}

fun List<Array<*>>.toJSONArray(): JSONArray {
    val jsonArray = JSONArray()
    forEach {
        val arr = JSONArray()
        it.forEach { point ->
            val p = JSONObject()
            when (point) {
                is PointF -> {
                    p.put("x", point.x)
                    p.put("y", point.y)
                }
                is DoubleArray -> {
                    p.put("x", point[0])
                    p.put("y", point[1])
                }
                else -> throw UnsupportedOperationException()
            }
            arr.put(p)
        }
        jsonArray.put(arr)
    }
    return jsonArray
}

