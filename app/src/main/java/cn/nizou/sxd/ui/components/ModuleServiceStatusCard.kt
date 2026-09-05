package cn.nizou.sxd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cn.nizou.sxd.AutoOralApplication
import io.github.libxposed.service.XposedService

/** Module-launcher-only status card, modeled on Simian XposedService state observation. */
@Composable
fun ModuleServiceStatusCard(modifier: Modifier = Modifier) {
    var service by remember { mutableStateOf<XposedService?>(AutoOralApplication.service) }
    DisposableEffect(Unit) {
        val listener: (XposedService?) -> Unit = { current -> service = current }
        AutoOralApplication.addServiceListener(listener)
        onDispose { AutoOralApplication.removeServiceListener(listener) }
    }

    val active = service != null
    val details = service?.let { current ->
        runCatching { current.frameworkName.ifBlank { "Xposed" } + " · API " + current.apiVersion }
            .getOrDefault("Xposed 服务已连接")
    } ?: "未连接 Xposed 服务"

    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        color = if (active) Color(0xFF2E7D32) else Color(0xFFC62828),
        contentColor = Color.White,
        shape = RoundedCornerShape(CornerRadius),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(if (active) "已激活" else "未激活", style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text(details, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = .9f))
            }
            Box(Modifier.padding(start = 12.dp).size(14.dp).clip(CircleShape).background(Color.White))
        }
    }
}
