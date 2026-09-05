package cn.nizou.sxd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cn.nizou.sxd.HOST_PACKAGE_NAME
import cn.nizou.sxd.MODULE_PREFS_NAME
import cn.nizou.sxd.util.currentApplication
import cn.nizou.sxd.util.logI
import cn.nizou.sxd.util.mainHandler
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 激活检测卡片（真实状态，不硬编码）。
 *
 * 判定逻辑（三路并取，任一为 true 即已激活）：
 * 1. **本进程本地 prefs**（`hook_active`）：宿主导入面板由宿主进程写入；模块本体手动标记也写这里。
 * 2. **libxposed 跨进程 RemotePreferences**：宿主进程在 onPackageReady 注入成功后写入；
 *    需本进程被框架注入（`XposedInit.self` 已初始化）才能读。
 * 3. **root 直读宿主 shared_prefs**（新增，修复模块本体永远未激活）：模块本体是独立进程，
 *    未注入宿主、`XposedInit.self` 未初始化、也读不到宿主 App 私有目录——在 root 环境下
 *    `su -c cat /data/data/com.fenbi.android.leo/shared_prefs/auto_oral_calculation.xml`
 *    直接读宿主 attach 时写入的 hook_active，真实反映注入状态。
 *
 * **点击兜底**：仍检测不到（root 不可用/路径变化）时，点击卡片弹确认框，手动标记本地已激活
 * （仅本进程本地生效，卸载/清数据后需重新标记）。
 */
@Composable
fun HookStatusCard(modifier: Modifier = Modifier) {
    var activated by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    var showManualDialog by remember { mutableStateOf(false) }

    fun readStatus(): Boolean {
        // 1) 本进程本地 prefs（宿主导入面板写入 / 模块本体手动标记）
        val local = runCatching {
            currentApplication()?.getSharedPreferences(
                MODULE_PREFS_NAME, android.content.Context.MODE_PRIVATE
            )?.getBoolean("hook_active", false)
        }.getOrNull() ?: false
        // 2) RemotePreferences（反射读取——不能直接引用 XposedInit.self：XposedInit 继承
        //    XposedModule（compileOnly 不打包），模块本体独立进程没有该类，直接引用会在类加载
        //    阶段抛 NoClassDefFoundError 且 try/catch 无法捕获 → 闪退；反射 forName 在 try 内
        //    抛错可捕获，宿主进程正常读取）
        val remote = readRemoteActive()
        // 3) root 直读宿主 shared_prefs（真实注入状态）——**仅在非宿主进程执行**：
        //    宿主进程 local 已足够（写入的就是宿主自己）；且 su 是阻塞调用，必须在后台线程，
        //    绝不能卡 Compose 主线程（否则 ANR/闪退）。
        val isHostProcess = runCatching {
            currentApplication()?.packageName == HOST_PACKAGE_NAME
        }.getOrDefault(false)
        val hostPrefs = if (isHostProcess) false else readHostHookActive()
        logI("HookStatus: local=$local remote=$remote hostPrefs=$hostPrefs")
        return local || remote || hostPrefs
    }

    fun manualActivate() {
        runCatching {
            currentApplication()?.getSharedPreferences(
                MODULE_PREFS_NAME, android.content.Context.MODE_PRIVATE
            )?.edit()?.putBoolean("hook_active", true)?.apply()
        }.onFailure { logI("manual activate failed: ${it.message}") }
        activated = true
        manual = true
        checked = true
    }

    LaunchedEffect(Unit) {
        // 检测含阻塞式 su 调用（root 读取），必须放后台线程，结果回主线程更新 UI
        thread {
            val result = readStatus()
            mainHandler.post {
                activated = result
                checked = true
            }
        }
    }

    val containerColor = if (activated) Color(0xFF2E7D32) else Color(0xFFC62828)
    val title = when {
        activated && manual -> "已激活（手动标记）"
        activated -> "已激活"
        else -> "未激活"
    }
    val desc = when {
        activated && manual -> "本进程已手动标记；请确认模块确已在 LSPosed 作用域生效"
        activated -> "模块已注入小猿口算 · ${readFrameworkName()}"
        !checked -> "检测中…"
        else -> "点击卡片手动标记激活；或确认 LSPosed 作用域已勾选「小猿口算」并重启"
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(enabled = checked && !activated) { showManualDialog = true },
        color = containerColor,
        contentColor = Color.White,
        shape = RoundedCornerShape(CornerRadius)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.9f)
                )
            }
            Box(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }
    }

    if (showManualDialog) {
        AlertDialog(
            onDismissRequest = { showManualDialog = false },
            title = { Text("手动标记为已激活？") },
            text = {
                Text(
                    "检测不到模块注入状态。若你确认模块已在小猿口算内生效（注入菜单显示已激活），" +
                        "可手动标记；该标记仅本机本进程生效，卸载/清除数据后需重新标记。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showManualDialog = false
                    manualActivate()
                }) { Text("确认激活") }
            },
            dismissButton = {
                TextButton(onClick = { showManualDialog = false }) { Text("取消") }
            }
        )
    }
}

/** root 直读宿主 shared_prefs 的 hook_active（模块本体独立进程验证真实注入状态）。
 *  仅在后台线程调用；su 挂起超 2s 直接放弃，绝不让调用方无限等待。 */
private fun readHostHookActive(): Boolean {
    return runCatching {
        val p = ProcessBuilder(
            "su", "-c",
            "cat /data/data/com.fenbi.android.leo/shared_prefs/auto_oral_calculation.xml"
        ).redirectErrorStream(true).start()
        if (!p.waitFor(2, TimeUnit.SECONDS)) {
            p.destroy()
            logI("HookStatus: su timeout, treat as inactive")
            return false
        }
        val text = p.inputStream.bufferedReader().readText()
        p.destroy()
        text.contains("name=\"hook_active\"") && text.contains("value=\"true\"")
    }.getOrDefault(false)
}

/**
 * 反射读取 RemotePreferences 的 hook_active。
 *
 * ⚠️ 不能直接引用 `XposedInit.self`：`XposedInit` 继承 `io.github.libxposed.api.XposedModule`
 * （compileOnly 依赖，不打包进 APK）。模块本体是独立 App，进程里没有 XposedModule 类——
 * 字节码一旦直接引用 XposedInit，**类加载阶段**就抛 `NoClassDefFoundError`，try/catch 无法捕获，
 * 表现为模块本体打开即闪退（真机 1.7.13/1.7.14 已复现）。改用反射 `Class.forName`：
 * 宿主进程（框架注入，XposedModule 存在）正常读取；模块本体进程 forName 抛错在 try 内被捕获 → false。
 */
/** Reads the active framework identity inside the injected process without linking XposedModule in launcher code. */
private fun readFrameworkName(): String = runCatching {
    val companion = Class.forName("cn.nizou.sxd.XposedInit" + '$' + "Companion")
    val self = companion.getField("self").get(null) ?: return "已注入"
    val type = self.javaClass
    val name = type.methods.first { it.name == "getFrameworkName" && it.parameterCount == 0 }.invoke(self)
    val api = type.methods.first { it.name == "getApiVersion" && it.parameterCount == 0 }.invoke(self)
    "$name · API $api（LSPosed / npatch）"
}.getOrDefault("已注入（LSPosed / npatch）")

private fun readRemoteActive(): Boolean {
    return runCatching {
        val companion = Class.forName("cn.nizou.sxd.XposedInit\$Companion")
        val self = companion.getField("self").get(null) ?: return false
        val m = self.javaClass.methods.first {
            it.name == "getRemotePreferences" && it.parameterCount == 1
        }
        m.isAccessible = true
        val prefs = m.invoke(self, MODULE_PREFS_NAME) as? android.content.SharedPreferences
            ?: return false
        prefs.getBoolean("hook_active", false)
    }.getOrDefault(false)
}
