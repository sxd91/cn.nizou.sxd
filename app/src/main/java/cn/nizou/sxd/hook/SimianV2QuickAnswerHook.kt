package cn.nizou.sxd.hook

import cn.nizou.sxd.util.DexKitCoordinator
import cn.nizou.sxd.util.DexKitLocator
import cn.nizou.sxd.util.SimianV2AutomationPrefs
import cn.nizou.sxd.util.logI
import io.github.libxposed.api.XposedInterface

/**
 * 「任何答案为正确答案」—— 把识别结果替换成**期望答案**。
 *
 * ## 为什么需要它
 *
 * 手写识别的返回值决定 H5 判对与否。原先有两条路：
 *  - `RecognizerHook`：hook 宿主的 `MathScriptRecognizer.a(int,List,List)`；
 *  - 本类：SimianV2 的 DexKit 定位版。
 *
 * 但 **`MathScriptRecognizer` 在 3.140+ 已被宿主移除**，所以 `RecognizerHook`
 * 只剩「找不到类就退出」的死代码 —— 现在真正生效的只有本类。
 *
 * ## ★★ 2026-10-01 修复（原实现「搜不到就不装」，是失效的主因）
 *
 * 原实现的问题：
 *  1. `findMethods(...).singleOrNull()` —— DexKit 匹配到 **0 个或 >1 个**就直接放弃，
 *     而且**只挂一次**（`addReadyListener` 回调里），失败后**没有重试**；
 *  2. `usingStrings(Equals)` 要求 DexKit 精确看到 `/time/recognize/math` 这个常量；
 *     宿主改版/常量挪位置时就会匹配不到；
 *  3. 命中不足时**不打印匹配数量**，日志里只看到一句失败，无法判断是 0 还是多个。
 *
 * 现在的做法：
 *  - **多策略**依次尝试（精确字符串 → 仅按参数类型），每种都打印命中数量；
 *  - 命中 >1 时**全部挂上**（多挂一个识别入口没有副作用：识别入口只在答题时被调）；
 *  - 定位失败**自动重试**（宿主可能稍后才完成类加载），最多 [MAX_ATTEMPTS] 次；
 *  - 每次尝试都留日志，便于真机定位。
 *
 * ## 与原版语义一致的部分
 *
 * 命中后：`answers[0]` = 期望答案（由 H5 传入），**替换**原识别结果 →
 * 判题必然命中。这就是「任何答案为正确答案」。
 */
class SimianV2QuickAnswerHook(self: XposedInterface, classLoader: ClassLoader) : BaseHook(self, classLoader) {

    override val name = "SimianV2QuickAnswerHook"

    /** 已挂上的方法数（>0 表示本次定位成功）。 */
    private val installedCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** 定位尝试次数（避免无限重试刷日志）。 */
    private val attempts = java.util.concurrent.atomic.AtomicInteger(0)

    override fun startHook() {
        DexKitCoordinator.addReadyListener(::installResolvedHook)
    }

    /**
     * 多策略定位「识别入口」并挂 hook。
     *
     * 识别入口的稳定特征：`(int, List, List)` 三参、返回 String。
     * 其中第 3 个 List 通常是「期望答案候选」，第 2 个 List 是输入特征。
     */
    private fun installResolvedHook() {
        if (installedCount.get() > 0) return
        val n = attempts.incrementAndGet()

        // 策略 1：精确点 —— 参数类型 + 识别接口字符串常量
        var candidates = locate(listOf("/time/recognize/math"))
        // 策略 2：放宽 —— 只按参数类型（有些版本常量被内联/挪走）
        if (candidates.isEmpty()) {
            logI("SimianV2 correct-answer: strategy-1 (string) hit 0, fallback to param-types only")
            candidates = locate(emptyList())
        }
        logI("SimianV2 correct-answer: attempt#$n matchers=${candidates.size}")

        if (candidates.isEmpty()) {
            if (n < MAX_ATTEMPTS) {
                // 宿主可能尚未完成类加载：稍后重试（不影响其它功能）。
                handlerPostDelayed(::installResolvedHook, RETRY_DELAY_MS)
            } else {
                logI("SimianV2 correct-answer: 已达最大尝试次数($MAX_ATTEMPTS)，放弃定位")
            }
            return
        }

        var ok = 0
        for (m in candidates) {
            runCatching {
                m.isAccessible = true
                m.intercept("simianv2_quick_answer") { chain ->
                    if (!SimianV2AutomationPrefs.effectiveQuickAnswer) {
                        return@intercept chain.proceed()
                    }
                    val expected = (chain.getArg(2) as? List<*>)?.firstOrNull()?.toString()
                    val original = chain.proceed()
                    // 期望答案非空就替换 —— 「任何答案为正确答案」。
                    if (!expected.isNullOrBlank()) expected else original
                }
                ok++
                logI("SimianV2 correct-answer hooked: ${m.declaringClass.name}#${m.name}")
            }.onFailure { e ->
                logI("SimianV2 correct-answer hook ${m.declaringClass.name}#${m.name} failed: ${e.message}")
            }
        }
        installedCount.set(ok)
        if (ok == 0 && n < MAX_ATTEMPTS) handlerPostDelayed(::installResolvedHook, RETRY_DELAY_MS)
    }

    /** 按给定字符串集合定位识别方法（返回全部命中，不去重也不单取）。 */
    private fun locate(strings: List<String>): List<java.lang.reflect.Method> = runCatching {
        DexKitLocator.findMethods(
            paramTypeNames = listOf("int", "java.util.List", "java.util.List"),
            usingStrings = strings,
        ).mapNotNull { it.getMethodInstance(classLoader) }
    }.getOrElse {
        logI("SimianV2 correct-answer: locate failed: ${it.message}")
        emptyList()
    }

    /** `handler` 在 BaseHook 里；这里只做「延迟重试」的薄封装。 */
    private fun handlerPostDelayed(block: () -> Unit, delayMs: Long) {
        runCatching { cn.nizou.sxd.util.mainHandler.postDelayed(block, delayMs) }
            .onFailure { logI("SimianV2 correct-answer: retry scheduling failed: ${it.message}") }
    }

    private companion object {
        /** 定位失败自动重试上限（宿主类加载可能滞后）。 */
        const val MAX_ATTEMPTS = 8

        /** 重试间隔。 */
        const val RETRY_DELAY_MS = 2500L
    }
}