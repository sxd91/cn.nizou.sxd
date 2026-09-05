package cn.nizou.sxd.hook

import cn.nizou.sxd.util.DexKitCoordinator
import cn.nizou.sxd.util.DexKitLocator
import cn.nizou.sxd.util.SimianV2AutomationPrefs
import cn.nizou.sxd.util.logI
import io.github.libxposed.api.XposedInterface

/** SimianV2 quick answer: replaces recognition output with the host-provided correct answer. */
class SimianV2QuickAnswerHook(self: XposedInterface, classLoader: ClassLoader) : BaseHook(self, classLoader) {
    override val name = "SimianV2QuickAnswerHook"
    override fun startHook() { DexKitCoordinator.addReadyListener { installResolvedHook() } }
    private fun installResolvedHook() {
        val className = DexKitLocator.findClassName(listOf("int", "java.util.List", "java.util.List"), usingStrings = listOf("/time/recognize/math"))
            ?: return logI("SimianV2 quick answer method unresolved")
        runCatching {
            val method = findClass(className).declaredMethods.singleOrNull { candidate ->
                candidate.parameterTypes.size == 3 && candidate.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    List::class.java.isAssignableFrom(candidate.parameterTypes[1]) && List::class.java.isAssignableFrom(candidate.parameterTypes[2])
            } ?: return@runCatching
            method.isAccessible = true
            method.intercept("simianv2_quick_answer") { chain ->
                if (!SimianV2AutomationPrefs.quickAnswer) return@intercept chain.proceed()
                val answers = chain.getArg(2) as List<*>
                val original = chain.proceed()
                answers.firstOrNull()?.toString() ?: original
            }
            logI("SimianV2 quick answer hook installed: " + className + "#" + method.name)
        }.onFailure { error -> logI("SimianV2 quick answer install failed: " + error.message) }
    }
}
