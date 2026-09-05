package cn.nizou.sxd.ui.host

import android.app.Activity
import androidx.activity.ComponentDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearWavyProgressIndicator
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import cn.nizou.sxd.ui.theme.AutoOralTheme
import cn.nizou.sxd.util.DexKitCoordinator

/** WeKit-style parsing card: automatic close only; it never offers host restart. */
internal object DexKitHostProgressDialog {
    private var dialog: ComponentDialog? = null

    fun show(activity: Activity) {
        if (dialog?.isShowing == true) return
        val context = ModuleContextWrapper.wrap(activity)
        val host = ComponentDialog(context, android.R.style.Theme_Translucent_NoTitleBar).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCancelable(false)
            setCanceledOnTouchOutside(false)
        }
        val content = ComposeView(context).apply {
            setViewTreeLifecycleOwner(host)
            setViewTreeSavedStateRegistryOwner(host)
            setContent { AutoOralTheme { DexKitProgressContent { host.dismiss() } } }
        }
        host.setContentView(content)
        host.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        host.setOnDismissListener { if (dialog === host) dialog = null }
        dialog = host
        host.show()
        host.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
    }
}

@Composable
private fun DexKitProgressContent(onDone: () -> Unit) {
    var state by remember { mutableStateOf(DexKitCoordinator.progress) }
    DisposableEffect(Unit) {
        val listener: (DexKitCoordinator.Progress) -> Unit = { next -> state = next }
        DexKitCoordinator.addListener(listener)
        onDispose { DexKitCoordinator.removeListener(listener) }
    }
    when (state.phase) {
        DexKitCoordinator.Phase.SUCCESS, DexKitCoordinator.Phase.FAILED -> {
            androidx.compose.runtime.LaunchedEffect(state.phase) {
                kotlinx.coroutines.delay(500)
                onDone()
            }
        }
        else -> Unit
    }

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().padding(24.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("DexKit 解析", style = MaterialTheme.typography.headlineSmall)
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer) {
                    Text(state.total.toString(), modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            HorizontalDivider()
            Text(state.task, style = MaterialTheme.typography.bodyMedium)
            if (state.phase == DexKitCoordinator.Phase.RESOLVING) {
                LinearWavyProgressIndicator(
                    progress = { state.completed.toFloat() / state.total.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth(),
                    amplitude = { progress -> if (progress == 0f || progress == 1f) 0f else 1f },
                )
                Text("已完成 ${state.completed} / ${state.total}", style = MaterialTheme.typography.labelSmall)
                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                Text(state.detail ?: if (state.phase == DexKitCoordinator.Phase.SUCCESS) "定位结果已写入内存缓存" else "", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
