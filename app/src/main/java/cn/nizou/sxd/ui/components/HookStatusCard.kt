package cn.nizou.sxd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cn.nizou.sxd.BuildConfig
import cn.nizou.sxd.util.HookStatus
import cn.nizou.sxd.util.currentApplication
import cn.nizou.sxd.util.readInjectedModuleSelf
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Check_circle
import com.composables.icons.materialsymbols.outlined.Error
import com.composables.icons.materialsymbols.outlined.Remove_circle

/**
 * WeKit injected SettingsActivity home-card counterpart.
 *
 * The card deliberately uses the host process' live XposedInit instance, exactly
 * as an injected menu should: the menu exists only after the host injection has
 * completed. It is not the standalone module-process XposedService detector.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HookStatusCard(modifier: Modifier = Modifier) {
    val environment = remember { readInjectedLoadingEnvironment() }
    val contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    val fallbackEnv = HookStatus.readEnv(null)
    val hookBridgeName = environment?.hookBridgeName ?: fallbackEnv ?: "未提供"

    // 注入菜单的激活判定：环境探测成功且框架 API 版本满足 minApiVersion 才算「已激活」。
    // environment == null 表示宿主进程里读不到活体 XposedInit（模块独立进程/未注入）。
    val headline = when {
        environment == null -> "模块未激活"
        !environment.apiCompatible -> "框架版本过低"
        else -> "模块已激活"
    }
    val leadingIcon = when {
        environment == null -> MaterialSymbols.Outlined.Remove_circle
        !environment.apiCompatible -> MaterialSymbols.Outlined.Error
        else -> MaterialSymbols.Outlined.Check_circle
    }
    val tagColor = when {
        environment == null -> Color(0xFF757575)
        !environment.apiCompatible -> Color(0xFFE65100)
        else -> MaterialTheme.colorScheme.primary
    }
    val detail = when {
        environment == null -> fallbackEnv ?: "未注入宿主"
        !environment.apiCompatible ->
            environment.frameworkName.ifBlank { "Xposed" } +
                " · API " + environment.apiVersion +
                " < " + HookStatus.MIN_API
        else -> hookBridgeName
    }

    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.large,
    ) {
        ListItem(
            leadingContent = {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = headline,
                )
            },
            supportingContent = {
                Text(
                    text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            trailingContent = {
                StatusTag(
                    label = detail,
                    backgroundColor = tagColor,
                    contentColor = Color.White,
                )
            },
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = contentColor,
                leadingContentColor = contentColor,
                trailingContentColor = contentColor,
                supportingContentColor = contentColor.copy(alpha = 0.7f),
            ),
            headlineContent = {
                Text(
                    text = headline,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                )
            },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StatusTag(label: String, backgroundColor: Color, contentColor: Color) {
    Box(
        modifier = Modifier
            .padding(end = 4.dp)
            .background(color = backgroundColor, shape = RoundedCornerShape(4.dp)),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmallEmphasized,
            color = contentColor,
        )
    }
}

data class InjectedLoadingEnvironment(
    val loaderName: String,
    val hookBridgeName: String,
    /** 框架上报的 API 版本；-1 表示取不到。 */
    val apiVersion: Int = -1,
    /** 框架自报名；空串表示取不到。 */
    val frameworkName: String = "",
    /** API 版本是否满足 module.prop 的 minApiVersion。 */
    val apiCompatible: Boolean = true,
)

/**
 * 从宿主进程活体 XposedInit 读加载环境，供注入菜单显示加载器 / 桥接 / API 版本。
 *
 * 每一步反射单独 runCatching：getFrameworkName 或 getApiVersion 任一失败，不应
 * 让整个环境探测返回 null（旧实现共用一个 runCatching，一处抛异常卡片就显示「未提供」）。
 */
fun readInjectedLoadingEnvironment(): InjectedLoadingEnvironment? {
    val self = readInjectedModuleSelf() ?: return null
    val type = self.javaClass
    val apiVersion = runCatching {
        type.methods.first { it.name == "getApiVersion" && it.parameterCount == 0 }
            .invoke(self) as? Int
    }.getOrNull() ?: -1
    val frameworkName = runCatching {
        type.methods.first { it.name == "getFrameworkName" && it.parameterCount == 0 }
            .invoke(self) as? String
    }.getOrNull().orEmpty()
    val fw = frameworkName.ifBlank { "Xposed" }
    val apiText = if (apiVersion >= 0) "API $apiVersion" else "API ?"
    return InjectedLoadingEnvironment(
        loaderName = "libxposed $apiText · $fw",
        hookBridgeName = "libxposed $apiText",
        apiVersion = apiVersion,
        frameworkName = frameworkName,
        apiCompatible = apiVersion < 0 || apiVersion >= HookStatus.MIN_API,
    )
}

