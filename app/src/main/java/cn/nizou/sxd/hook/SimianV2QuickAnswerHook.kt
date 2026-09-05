package cn.nizou.sxd.hook

import cn.nizou.sxd.util.DexKitCoordinator
import cn.nizou.sxd.util.DexKitLocator
import cn.nizou.sxd.util.SimianV2AutomationPrefs
import cn.nizou.sxd.util.logI
import io.github.libxposed.api.XposedInterface
import org.luckypray.dexkit.util.getMethodInstance

/** Direct port of SimianV2 QuickAnswerHook: resolve the exact recognition method, then replace its result. */
class SimianV2QuickAnswerHook(self: XposedInterface, classLoader: ClassLoader) : BaseHook(self, classLoader) {
    override val name = "SimianV2QuickAnswerHook"
    override fun startHook() = DexKitCoordinator.addReadyListener(::installResolvedHook)

    private fun installResolvedHook() {
        runCatching {
            val method = DexKitLocator.findMethods(
                paramTypeNames = listOf("int", "java.util.List", "java.util.List"),
                usingStrings = listOf("/time/recognize/math"),
            ).singleOrNull()?.getMethodInstance(classLoader)
                ?: return@runCatching logI("SimianV2 correct-answer target unresolved")
            method.isAccessible = true
            method.intercept("simianv2_quick_answer") { chain ->
                if (!SimianV2AutomationPrefs.quickAnswer) return@intercept chain.proceed()
                val answers = chain.getArg(2) as List<*>
                val original = chain.proceed()
                answers.firstOrNull()?.toString() ?: original
            }
            logI("SimianV2 correct-answer hook installed: " + method.declaringClass.name + "#" + method.name)
        }.onFailure { error -> logI("SimianV2 correct-answer install failed: " + error.message) }
    }
}
