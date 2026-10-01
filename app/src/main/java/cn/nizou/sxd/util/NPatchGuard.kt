package cn.nizou.sxd.util

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import java.io.File

/**
 * 禁止 npatch「内置」——只允许 npatch **注入/修补**，内置包一启动就中止宿主。
 *
 * ## 两种 npatch 产物（2026-10-01 真机 APK 逐字节对比）
 *
 * | | 修补版（允许） | 内置版（要禁） |
 * |---|---|---|
 * | 举例 | 设备在用 `com.fenbi.android.leo` | `小猿AI_original-762-npatched.apk` |
 * | `<application>` 的 `appComponentFactory` | `top.nkbe.npatch...LSPAppComponentFactoryStub` | 同左（**两者相同，不能当判据**）|
 * | `assets/npatch/modules/` 下的 `.apk` | **无** | **有** ← 模块被内置进宿主 |
 * | manifest `<meta-data name="xposedmodule" value="true">` | **无** | **有** ← npatch 为内置模块生成的虚拟 xposed 声明 |
 * | manifest `<meta-data name="npatch" …>` | **无** | **有** |
 * | manifest `<meta-data name="xposeddescription" value="NPatch Embed LoadedModule">` | **无** | **有** |
 *
 * ## 判据（按可靠性排序）
 *
 * 1. **`xposedmodule=true` 元数据** —— 最直接：原版宿主不会把自己声明成 xposed 模块；
 *    npatch 内置时会给每个内置模块注入这段 manifest 标记。运行时用
 *    `PackageManager.getApplicationInfo(..., GET_META_DATA)` 读 `ApplicationInfo.metaData`。
 * 2. **内置模块目录** `assets/npatch/modules/` 内存在 `*.apk` —— 无法用 PM 读到，
 *    但可以直接读宿主 APK（`ApplicationInfo.sourceDir`）的 zip 目录项，成本低、判据硬。
 * 3. `xposeddescription` / `npatch` 元数据含 `NPatch Embed` 字样。
 *
 * ## 与「修补版」的区分（重要）
 *
 * 两者都会在 manifest 上挂 npatch 的 `appComponentFactory`，**所以绝不能拿它当判据**
 * ——早先按它判定会把用户正常在用的修补版一起误杀。
 *
 * ## 行为
 *
 * 命中即 `Process.killProcess(myPid())` + `System.exit(1)`。
 * **强制开启、不可关闭**（[Common.blockNPatchEmbed] 恒为 true）——模块只允许在
 * npatch 注入/修补模式下工作。
 */
object NPatchGuard {

    /** 内置版才有的 xposed 元数据键（npatch 为内置模块生成）。 */
    private const val META_XPOSED_MODULE = "xposedmodule"
    private const val META_XPOSED_DESC = "xposeddescription"
    private const val META_NPATCH = "npatch"

    /** 内置版 assets 下的内置模块目录。 */
    private const val NPATCH_MODULES_DIR = "assets/npatch/modules/"

    /**
     * 在宿主 `Application.attach` 后立即调用。
     *
     * @return true 表示判定为「npatch 内置」并已中止宿主；false 表示放行。
     */
    fun enforceOrKill(app: Application?): Boolean {
        if (!Common.blockNPatchEmbed) {
            logI("NPatchGuard: 已关闭（block_npatch_embed=false），放行")
            return false
        }
        val evidence = detect(app)
        if (evidence == null) {
            logI("NPatchGuard: 未检测到 npatch 内置，放行（仅允许 npatch 注入/修补）")
            return false
        }
        logI("NPatchGuard: 检测到 npatch 内置，中止宿主 >>> $evidence")
        Log.e("AutoOral", "NPatchGuard: blocked npatch-embedded host: $evidence")
        runCatching { Process.killProcess(Process.myPid()) }
        runCatching { System.exit(1) }
        return true
    }

    /** 返回命中证据（null = 非内置）。 */
    private fun detect(app: Application?): String? {
        // 判据 1：manifest 元数据（最直接）
        readMeta(app)?.let { return it }
        // 判据 2：宿主 APK 里带了内置模块
        readEmbeddedModules(app)?.let { return it }
        return null
    }

    /** 读宿主 manifest 元数据，识别 npatch 内置标记。 */
    private fun readMeta(app: Application?): String? {
        val bundle: Bundle = runCatching {
            @Suppress("DEPRECATION")
            app?.packageManager
                ?.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA)
                ?.metaData
        }.getOrNull() ?: return null

        // (1) 被声明成 xposed 模块 —— 原版宿主绝不会
        if (bundle.getBoolean(META_XPOSED_MODULE, false) ||
            "true".equals(bundle.getString(META_XPOSED_MODULE), ignoreCase = true)
        ) {
            return "meta-data xposedmodule=true (description=${bundle.getString(META_XPOSED_DESC)})"
        }
        // (2) npatch 元数据里的内置标记
        val npatch = bundle.getString(META_NPATCH)
        if (!npatch.isNullOrBlank()) {
            return "meta-data npatch=$npatch"
        }
        // (3) 描述里直接写了 Embed
        val desc = bundle.getString(META_XPOSED_DESC)
        if (!desc.isNullOrBlank() && desc.contains("Embed", ignoreCase = true)) {
            return "meta-data xposeddescription=$desc"
        }
        return null
    }

    /**
     * 读宿主 APK 的 zip 目录项，判断 `assets/npatch/modules/` 里是否有内置模块。
     *
     * 不用解压，只读中央目录 —— 开销可忽略。
     */
    private fun readEmbeddedModules(app: Application?): String? {
        val apk = app?.applicationInfo?.sourceDir ?: return null
        val f = File(apk)
        if (!f.exists()) return null
        return runCatching {
            java.util.zip.ZipFile(f).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val name = entries.nextElement().name
                    if (name.startsWith(NPATCH_MODULES_DIR) && name.endsWith(".apk")) {
                        return "embedded module: $name"
                    }
                }
            }
            null
        }.getOrNull()
    }

    /** 便于设置页展示当前判定结果（不改动任何状态）。 */
    fun describe(ctx: Context): String {
        val app = ctx.applicationInfo
        return buildString {
            append("package=").append(app?.packageName).append('\n')
            append("appComponentFactory=").append(app?.appComponentFactory ?: "(null)").append('\n')
            append("block_npatch_embed=").append(Common.blockNPatchEmbed).append('\n')
            append("sdk=").append(Build.VERSION.SDK_INT)
        }
    }
}