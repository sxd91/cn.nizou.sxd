package cn.nizou.sxd.ui.content.nukex

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Target-owned compact Nuke engine derived from WeKit's Nuke setting primitives. */
@Immutable
data class NukeColors(val background: Color, val surface: Color, val border: Color, val textPrimary: Color, val textSecondary: Color, val accent: Color)
private val LocalNukeColors = compositionLocalOf { NukeColors(Color(0xFFF4F4F6), Color.White, Color(0xFFEFEFEF), Color(0xFF1A1A1A), Color(0xFF757575), Color(0xFF2E7D32)) }
object NukeTheme { val colors: NukeColors @Composable get() = LocalNukeColors.current }

@Composable
fun NukeModuleTheme(dark: Boolean, accent: Color, content: @Composable () -> Unit) {
    val colors = if (dark) NukeColors(Color(0xFF0A0A0A), Color(0xFF161616), Color(0xFF242424), Color.White, Color(0xFF888888), accent)
    else NukeColors(Color(0xFFF4F4F6), Color.White, Color(0xFFEFEFEF), Color(0xFF1A1A1A), Color(0xFF757575), accent)
    CompositionLocalProvider(LocalNukeColors provides colors, content = content)
}

@Composable
fun NukePageScaffold(title: String, onBack: () -> Unit, content: LazyListScope.() -> Unit) {
    val statusTop = androidx.compose.foundation.layout.WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Column(Modifier.fillMaxSize().background(NukeTheme.colors.background)) {
        Row(Modifier.fillMaxWidth().height(64.dp + statusTop).padding(top = statusTop, start = 18.dp, end = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = NukeTheme.colors.accent, fontSize = 38.sp, modifier = Modifier.size(44.dp).clickable { onBack() })
            Spacer(Modifier.width(6.dp)); Text(title, color = NukeTheme.colors.textPrimary, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 112.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun NukeSettingGroup(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, color = NukeTheme.colors.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(NukeTheme.colors.surface)) { content() }
    }
}

@Composable
fun NukePreferenceRow(title: String, description: String? = null, enabled: Boolean = true, icon: ImageVector? = null, onClick: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    val haptics = LocalHapticFeedback.current
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(enabled = enabled) { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); onClick() } else Modifier).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { androidx.compose.material3.Icon(icon, null, tint = NukeTheme.colors.accent, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) NukeTheme.colors.textPrimary else NukeTheme.colors.textSecondary, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (description != null) Text(description, color = NukeTheme.colors.textSecondary, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        }
        Spacer(Modifier.width(10.dp)); trailing()
    }
}

@Composable
fun NukeSwitchRow(title: String, description: String? = null, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    NukePreferenceRow(title, description, onClick = { onCheckedChange(!checked) }) {
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedTrackColor = NukeTheme.colors.accent))
    }
}
