package cn.nizou.sxd.util

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.util.concurrent.ConcurrentHashMap

/**
 * DexKit 定位器（版本适配兜底层，高手方案——见 skill 05 §8）。
 *
 * DexKit 2.0（org.luckypray:dexkit）：C++ 实现的 dex 运行时解析库，遍历/建索引极快；
 * 按「方法参数类型 / 返回类型 / 方法内字符串引用」在宿主 dex 中模糊搜索类，替代每个版本
 * 手工逆向硬编码混淆类/方法名。
 *
 * 场景：
 * - 类名被混淆（Classname 常量失效）→ 用本类按方法特征搜类名；
 * - 方法签名不唯一（多个同签名方法）→ 用 usingStrings（URL 路径/字段名等）缩小。
 *
 * 注意：
 * - [init] 需要**宿主 APK 路径**（DexKit 2.0 `create(apkPath)`，不再吃 classLoader）；
 *   首次建索引走后台线程，结果缓存到 [cache]，后续同步命中。
 * - native 库：`System.loadLibrary("dexkit")` 在 [init] 内加载（AAR 已随 APK 打包 libdexkit.so）。
 * - 生命周期：宿主进程常驻，进程结束由系统回收；[close] 可选。
 */
object DexKitLocator {

    private val cache = ConcurrentHashMap<String, Set<String>>()

    @Volatile
    private var bridge: DexKitBridge? = null

    /** 用宿主 APK 路径初始化（幂等；线程安全）。失败返回 false 不抛。 */
    fun init(apkPath: String): Boolean {
        if (bridge != null) return true
        return runCatching {
            // native 库必须先加载（DexKitBridge.create 内部依赖 JNI）
            System.loadLibrary("dexkit")
            val b = DexKitBridge.create(apkPath) ?: return false
            bridge = b
            true
        }.onFailure {
            logI("DexKitLocator init failed: ${it.message}")
        }.getOrDefault(false)
    }

    /** 释放 DexKit（宿主进程结束时调用，可选）。 */
    fun close() {
        runCatching { bridge?.close() }.onFailure { logI(it) }
        bridge = null
        cache.clear()
    }

    /**
     * 按「方法签名特征」搜索类名集合（缓存命中直接返回）。
     *
     * @param paramTypeNames 参数类型名数组（如 "java.lang.String"、"java.util.List"）；
     *   元素传 null 表示该位参数任意（隐含参数个数），传空数组表示不限参数
     * @param returnTypeName 返回类型名（可为 null；如 "void"、"java.util.List"）
     * @param usingStrings 方法体内引用的字符串（URL 路径、字段名等），全部命中才算
     * @return 匹配类名集合（如 "com.fenbi.android.leo.exercise.math.quick.QuickExercisePresenter"）
     */
    fun findClassNamesByMethod(
        paramTypeNames: List<String?>,
        returnTypeName: String? = null,
        usingStrings: List<String> = emptyList(),
    ): Set<String> {
        val key = listOf(paramTypeNames, returnTypeName ?: "", usingStrings).toString()
        cache[key]?.let { return it }

        val b = bridge ?: return emptySet()
        return runCatching {
            val classes = b.findClass {
                matcher {
                    methods {
                        add {
                            if (paramTypeNames.isNotEmpty()) {
                                paramTypes(*paramTypeNames.toTypedArray())
                            }
                            if (returnTypeName != null) {
                                returnType = returnTypeName
                            }
                            if (usingStrings.isNotEmpty()) {
                                usingStrings(usingStrings, StringMatchType.Equals)
                            }
                        }
                    }
                }
            }
            val names = classes.map { it.name }.toSet()
            cache[key] = names
            logI("DexKitLocator: key=$key → ${names.size} classes, first=${names.firstOrNull()}")
            names
        }.getOrElse {
            logI("DexKitLocator search failed: ${it.message}")
            emptySet()
        }
    }

    /** 便捷：定位唯一类名；不唯一/失败返回 null。 */
    fun findClassName(
        paramTypeNames: List<String?>,
        returnTypeName: String? = null,
        usingStrings: List<String> = emptyList(),
    ): String? {
        val names = findClassNamesByMethod(paramTypeNames, returnTypeName, usingStrings)
        return if (names.size == 1) names.first() else null
    }
}

/** Host-startup DexKit state stream. UI observes it; parsing never waits for UI. */
object DexKitCoordinator {
    enum class Phase { IDLE, RESOLVING, SUCCESS, FAILED }
    data class Progress(
        val phase: Phase = Phase.IDLE,
        val task: String = "等待解析",
        val completed: Int = 0,
        val total: Int = 1,
        val detail: String? = null,
    )

    @Volatile private var started = false
    @Volatile var progress: Progress = Progress()
        private set
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<(Progress) -> Unit>()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun addListener(listener: (Progress) -> Unit) { listeners += listener; listener(progress) }
    fun removeListener(listener: (Progress) -> Unit) { listeners -= listener }
    private fun publish(next: Progress) {
        progress = next
        mainHandler.post { listeners.forEach { it(next) } }
    }

    @Synchronized fun start(apkPath: String): Boolean {
        if (started) return false
        started = true
        publish(Progress(Phase.RESOLVING, "正在建立 DexKit 索引", 0, 1))
        kotlin.concurrent.thread(name = "AutoOral-DexKit", isDaemon = true) {
            val ready = DexKitLocator.init(apkPath)
            if (ready) {
                publish(Progress(Phase.SUCCESS, "DexKit 解析完成", 1, 1))
            } else {
                publish(Progress(Phase.FAILED, "DexKit 初始化失败，已回退签名定位", 1, 1, "不影响宿主继续启动"))
            }
        }
        return true
    }
}
