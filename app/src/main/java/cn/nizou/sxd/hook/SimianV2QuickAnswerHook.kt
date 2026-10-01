package cn.nizou.sxd.hook

import cn.nizou.sxd.util.DexKitCoordinator
import cn.nizou.sxd.util.DexKitLocator
import cn.nizou.sxd.util.SimianV2AutomationPrefs
import cn.nizou.sxd.util.logI
import io.github.libxposed.api.XposedInterface

/**
 * 「任何答案为正确答案」—— 把识别结果替换成**期望答案**。
 *
 * ## 参考实现（SimianV2 `com.log.simianv2`，设备实测可用）
 *
 * 反编译其 `classes.dex` 得到的判定依据：
 *  - 它同样使用 **DexKit**（`lib/arm64-v8a/libdexkit.so` 随包分发）；
 *  - 它同样以字符串 **`/time/recognize/math`** 作为识别入口的定位特征；
 *  - 它也 hook 宿主的 `com.fenbi.android.leo.webapp.secure.commands.EncryptResult`。
 *
 * 说明：**定位特征与本类策略一致**（`(int, List, List)` + `/time/recognize/math`），
 * 所以「抄袭 SimianV2」不是换一个特征，而是**保证定位一定被执行到**。
 *
 * ## ★★ 2026-10-01 二次修复：为什么「什么都没发生」
 *
 * 上一版把定位挂在 `DexKitCoordinator.addReadyListener` 上，真机日志里
 * **连一行 `attempt#` 都没有** —— 说明 `installResolvedHook` 从未被调用。可能原因：
 *
 *  1. `DexKitCoordinator.publish(FAILED)` 时**不会**触发 readyListeners
 *     （旧代码只在 `Phase.SUCCESS` 时回调）→ DexKit 初始化失败就**永久静默**；
 *  2. `DexKitCoordinator.start()` 由「第一个 Activity onResume」调用，若该回调没跑到，
 *     `phase` 永远停在 `IDLE`，同样永久静默；
 *  3. 旧实现失败时**没有任何日志**，真机上无法区分「没跑」「跑了没命中」「跑挂了」。
 *
 * 现在的做法：**自己轮询**，不依赖 ready 回调。
 *  - 每 [RETRY_DELAY_MS] 尝试一次，最多 [MAX_ATTEMPTS] 次；
 *  - 每次尝试都写日志，包含 **DexKit bridge 是否就绪** 与 **命中数**；
 *  - 命中多个时**全部挂上**（多挂无副作用）；
 *  - 已成功则立即停止轮询。
 *
 * ## 语义
 *
 * 命中后：`answers[0]`（H5 传入的期望答案）非空 → **替换**原识别结果 → 判题必然命中。
 * 这就是「任何答案为正确答案」。练习场景由 [SimianHook] 的 `QuestionVO.getAnswers`
 * 与 `EncryptResult` 改写共同覆盖（与 SimianV2 的分工一致）。
 */
class SimianV2QuickAnswerHook(self: XposedInterface, classLoader: ClassLoader) : BaseHook(self, classLoader) {

    override val name = "SimianV2QuickAnswerHook"

    /** 已挂上的方法数（>0 表示定位成功）。 */
    @Volatile private var installedCount = 0

    /** 定位尝试次数（避免无限重试刷日志）。 */
    @Volatile private var attempts = 0

    override fun startHook() {
        // 不要只依赖 addReadyListener：DexKit 失败时它永不回调（旧版永久静默的根因）。
        // 这里直接启动自轮询，ready 回调只用来「提前触发一次」。
        DexKitCoordinator.addReadyListener { attemptInstall("ready-callback") }
        attemptInstall("startHook")
    }

    /**
     * 尝试定位并挂 hook。失败或尚未就绪时按 [RETRY_DELAY_MS] 自动重试。
     *
     * @param trigger 触发来源（日志用，便于区分是 ready 回调还是轮询）
     */
    private fun attemptInstall(trigger: String) {
        if (installedCount > 0) return
        val n = ++attempts
        val dexKitReady = DexKitLocator.isReady()
        if (!dexKitReady && n >= MAX_ATTEMPTS) {
            logI("SimianV2 correct-answer: 放弃（DexKit 始终未就绪，attempt#=$n trigger=$trigger）")
            return
        }

        // 策略 1：精确点 —— 参数类型 + 识别接口字符串常量（与 SimianV2 同一特征）
        var candidates = locate(listOf("/time/recognize/math"))
        // 策略 2：放宽 —— 只按参数类型（常量被内联/挪走时）
        if (candidates.isEmpty()) {
            candidates = locate(emptyList())
        }
        logI("SimianV2 correct-answer: attempt#$n trigger=$trigger dexKitReady=$dexKitReady matchers=${candidates.size}")

        if (candidates.isEmpty()) {
            if (n < MAX_ATTEMPTS) {
                cn.nizou.sxd.util.mainHandler.postDelayed({ attemptInstall("retry$n") }, RETRY_DELAY_MS)
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
                    if (!expected.isNullOrBlank()) expected else original
                }
                ok++
                logI("SimianV2 correct-answer hooked: ${m.declaringClass.name}#${m.name}")
            }.onFailure { e ->
                logI("SimianV2 correct-answer hook ${m.declaringClass.name}#${m.name} failed: ${e.message}")
            }
        }
        installedCount = ok
        if (ok == 0 && n < MAX_ATTEMPTS) {
            cn.nizou.sxd.util.mainHandler.postDelayed({ attemptInstall("retry-hookfail$n") }, RETRY_DELAY_MS)
        }
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

    private companion object {
        /** 定位失败自动重试上限。 */
        const val MAX_ATTEMPTS = 10

        /** 重试间隔。 */
        const val RETRY_DELAY_MS = 2000L
    }
}