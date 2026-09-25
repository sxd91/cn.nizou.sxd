package cn.nizou.sxd.util

/**
 * sign 逆向 native 探针（方案 A：shadowhook inline hook）。
 *
 * getEncodedP 的注册绕过了 ART 反射层（60s 轮询 59 个 sign 期间方法表始终只有 getString），
 * Java hook 已到理论终点。静态逆向已定位其 native 实现位于 libRequestEncoder.so + 0x60810。
 *
 * 工作流程：
 * 1. 轮询 /proc/self/maps 等 libRequestEncoder.so 出现（so 由 vgo 运行时解压到 cache/native/host 后加载）
 * 2. 出现后 System.loadLibrary 加载本模块的 libsignprobe_native.so
 * 3. installHook -> shadowhook 挂 基址+0x60810，每次调用落盘 (s1, s2, i) -> sign
 *
 * 只读参数与返回值，不改变任何行为，对宿主零副作用。
 */
object SignProbeNative {
    private const val TAG = "SignProbeNative"
    const val GET_ENCODED_P_OFFSET = 0x60810

    init {
        System.loadLibrary("signprobe_native")
    }

    /** native 侧安装 hook。返回 0=成功，-1=so未加载，-2=init失败，-3=hook失败 */
    private external fun installHook(logPath: String, offset: Int): Int

    /**
     * 轮询等 libRequestEncoder.so 映射进进程后安装 hook。
     * 必须在宿主进程内调用（onPackageLoaded 之后）。
     */
    fun startPolling(logPath: String, maxSeconds: Int = 90) {
        Thread {
            var attempt = 0
            val deadline = System.currentTimeMillis() + maxSeconds * 1000L
            while (System.currentTimeMillis() < deadline) {
                attempt++
                val rc = try {
                    installHook(logPath, GET_ENCODED_P_OFFSET)
                } catch (t: Throwable) {
                    SignProbeHelper.log(null, "NATIVE installHook threw: $t")
                    return@Thread
                }
                when (rc) {
                    0 -> return@Thread  // hooked，native_log 会继续落盘
                    -1 -> { /* so 未映射，继续等 */ }
                    else -> return@Thread  // -2/-3 记录在 native_log
                }
                Thread.sleep(300)
            }
            SignProbeHelper.log(null, "NATIVE polling timeout after ${maxSeconds}s ($attempt attempts)")
        }.apply {
            name = "SignProbe-Native-Poll"
            isDaemon = true
            start()
        }
    }
}