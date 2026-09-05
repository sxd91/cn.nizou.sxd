package cn.nizou.sxd.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.nizou.sxd.BuildConfig
import cn.nizou.sxd.HOST_PACKAGE_NAME
import cn.nizou.sxd.ui.components.ModuleServiceStatusCard
import cn.nizou.sxd.util.ConfigActions
import cn.nizou.sxd.util.StringRes
import cn.nizou.sxd.util.openGithub
import cn.nizou.sxd.util.openSettingsInHostApp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.File_download
import com.composables.icons.materialsymbols.outlined.File_upload
import com.composables.icons.materialsymbols.outlined.Forum
import com.composables.icons.materialsymbols.outlined.Public
import com.composables.icons.materialsymbols.outlined.Rocket_launch
import com.composables.icons.materialsymbols.outlined.Settings

/**
 * 模块本体（独立 App）根界面 —— 简单启动器页。
 *
 * 架构对齐 WeKit `MainActivity.AppContent`：模块本体**不再**是 pager 功能页，只做
 * 激活入口 / 拉起宿主 / 拉起模块设置 / GitHub 链接 四件事，不含任何模块内部功能。
 * 模块的全部功能只保留在注入宿主的面板（[cn.nizou.sxd.ui.host.SettingsPanel] /
 * [MainPagerScreen]）。
 *
 * 结构 = CenterAlignedTopAppBar(标题=模块名+版本) + 激活卡片(绿已激活/红未激活) +
 * 「打开小猿口算」卡片(拉起宿主) + 「打开模块设置」卡片(拉起宿主设置入口) +
 * GitHub 链接卡片。
 *
 * @param res StringRes 实例（模块自身资源）。
 * @param onFinish 关闭整个本体（系统返回键退栈到根时触发）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleMainScreen(
    res: StringRes,
    onFinish: () -> Unit,
) {
    val context = LocalContext.current

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "老挂戏老叟",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "v${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 激活卡片（绿=已注入宿主，红=未激活）
            ModuleServiceStatusCard()

            // 打开宿主（小猿口算）
            LauncherCard(
                icon = MaterialSymbols.Outlined.Rocket_launch,
                title = "打开小猿口算",
                description = "启动宿主 App 以生效模块功能",
                onClick = {
                    val launch = context.packageManager
                        .getLaunchIntentForPackage(HOST_PACKAGE_NAME)
                    if (launch != null) {
                        context.startActivity(launch)
                    } else {
                        Toast.makeText(
                            context,
                            "未安装小猿口算",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )

            // 打开模块设置（拉起宿主内注入的设置面板入口，KEY_START_SETTINGS）
            LauncherCard(
                icon = MaterialSymbols.Outlined.Settings,
                title = "打开模块设置",
                description = "在小猿口算内打开注入的设置面板",
                onClick = { context.openSettingsInHostApp() }
            )

            // 导出配置（真实配置在宿主私有目录，经 root 读取 + 官方文件选取工具保存 JSON）
            LauncherCard(
                icon = MaterialSymbols.Outlined.File_upload,
                title = "导出配置",
                description = "将全部设置保存为 JSON 文件",
                onClick = { ConfigActions.export(context) }
            )

            // 导入配置（覆盖当前全部设置，导入后重启小猿口算生效）
            LauncherCard(
                icon = MaterialSymbols.Outlined.File_download,
                title = "导入配置",
                description = "从 JSON 文件恢复设置",
                onClick = { ConfigActions.importFromDocument(context) }
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            // GitHub 链接
            LauncherCard(
                icon = MaterialSymbols.Outlined.Public,
                title = "GitHub",
                description = "github.com/sxd91/nizou",
                onClick = { context.openGithub() }
            )

            // QQ 交流群（点击复制群号）
            LauncherCard(
                icon = MaterialSymbols.Outlined.Forum,
                title = "QQ 交流群",
                description = "群号 994173459，点击复制",
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("QQ群", "994173459"))
                    Toast.makeText(context, "QQ 群号已复制：994173459", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}

/** 启动器页的可点击卡片（照 WeKit ElevatedCard 结构，用 Surface+clickable 规避实验 API）。 */
@Composable
private fun LauncherCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
