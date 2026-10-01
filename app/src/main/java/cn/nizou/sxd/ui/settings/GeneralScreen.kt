package cn.nizou.sxd.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.nizou.sxd.ui.components.DropDownMenuWidget
import cn.nizou.sxd.ui.components.DropdownOption
import cn.nizou.sxd.ui.components.M3BackButton
import cn.nizou.sxd.ui.components.M3ListScaffold
import cn.nizou.sxd.ui.components.SegmentedColumn
import cn.nizou.sxd.ui.components.SwitchWidget
import cn.nizou.sxd.util.ProvinceRegionPrefs
import cn.nizou.sxd.util.SettingsPrefs
import cn.nizou.sxd.util.StringRes

/** 通用设置页：识别 / 昵称通用开关。 */
@Composable
fun GeneralScreen(res: StringRes, onBack: () -> Unit) {
    var alwaysTrue by remember {
        mutableStateOf(SettingsPrefs.readBoolean(res, res.KEY_ALWAYS_TRUE_ANSWER, true))
    }
    var ignoreNicknameRestriction by remember {
        mutableStateOf(SettingsPrefs.readBoolean(res, res.KEY_IGNORE_NICKNAME_RESTRICTION, true))
    }
    var province by remember { mutableStateOf(ProvinceRegionPrefs.selectedName) }

    M3ListScaffold(
        title = "通用",
        navigationIcon = { M3BackButton(onClick = onBack) }
    ) {
        item {
            SegmentedColumn(title = "通用") {
                SwitchWidget(
                    title = "一切输入视为正确答案",
                    description = "手写输入识别结果永远为正确答案",
                    checked = alwaysTrue,
                    onCheckedChange = {
                        alwaysTrue = it
                        SettingsPrefs.writeBoolean(res, res.KEY_ALWAYS_TRUE_ANSWER, it)
                    }
                )
                SwitchWidget(
                    title = "无视名字限制",
                    description = "昵称长度与字符/格式限制全部放开（任意长度、任意非空白字符）",
                    checked = ignoreNicknameRestriction,
                    onCheckedChange = {
                        ignoreNicknameRestriction = it
                        SettingsPrefs.writeBoolean(res, res.KEY_IGNORE_NICKNAME_RESTRICTION, it)
                    }
                )
                // 禁止 npatch 内置：强制开启，不可关闭（无开关，仅说明）
                SwitchWidget(
                    title = "禁止 npatch 内置（强制）",
                    description = "只允许 npatch 注入/修补。检测到宿主是 npatch「内置版」（manifest 里被声明成 " +
                        "xposed 模块 xposedmodule=true，或宿主 APK 内带 assets/npatch/modules/ 下的模块 apk）时，" +
                        "直接中止宿主进程。该功能强制开启，无法关闭。",
                    checked = true,
                    onCheckedChange = { /* 强制开启，忽略任何改动 */ },
                )
            }
        }
        item {
            SegmentedColumn(title = "试卷地区") {
                DropDownMenuWidget(
                    title = "改地区",
                    description = "改写试卷列表请求的 provinceId；全国表示不改写",
                    value = province,
                    options = ProvinceRegionPrefs.provinces.map { DropdownOption(it.name, it.name) },
                    onValueChange = { selected ->
                        province = selected
                        ProvinceRegionPrefs.select(selected)
                    },
                )
            }
        }
        item { Box(Modifier.padding(24.dp)) {} }
    }
}
