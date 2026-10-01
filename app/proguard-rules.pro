# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-keep class cn.nizou.sxd.XposedInit

# --- ActivityProxy 借壳引擎（HostSettingsActivity 反射实例化 + Instrumentation 子类） ---
-keepnames class cn.nizou.sxd.ui.host.HostSettingsActivity
-keep class cn.nizou.sxd.ui.host.HostSettingsActivity { public <init>(); }
-keep class cn.nizou.sxd.util.ActivityProxy { *; }
-keep class cn.nizou.sxd.util.ActivityProxy$* { *; }

# --- libxposed API 102 官方规则 ---
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
-keepnames class cn.nizou.sxd.util.XposedHelpers

# --- ★★ XposedInit.Companion 必须保持原名（2026-10-01）---
#
# 修复「注入菜单显示：加载环境 未提供 / 未注入宿主」。
#
# 上面那条 libxposed 官方规则带 `allowobfuscation` —— 它只 keep 了 XposedInit
# **类名与构造器**，**没有** keep 它的 companion：
#   - companion 类名（`XposedInit$Companion`）
#   - `self` 字段
#   - `getSelf()` 方法
# 全都会被 R8 改名，于是 `readInjectedModuleSelf()` 按名字反射必然失败 → 卡片
# 一直显示「未注入宿主」。这正是「早些时候是好的、后来修坏了」的回归点。
#
# 这条规则**必须放在 libxposed 官方规则之后** —— 否则官方那条的
# `allowobfuscation` 会把这里 keep 的 companion 成员又放开混淆。
# （util/InjectedModuleAccess.kt 里另有一层「按类型兜底」的代码保险。）
-keep class cn.nizou.sxd.XposedInit$Companion { *; }
-keepclassmembers class cn.nizou.sxd.XposedInit$Companion {
    <fields>;
    <methods>;
}
# self 字段本身（部分 R8 版本会把 companion 静态字段内联到 XposedInit）
-keepclassmembers class cn.nizou.sxd.XposedInit {
    static <fields>;
    public static ** self;
}

# --- Compose Material3 混淆规则（宿主注入面板 + 独立 MainActivity 都需要） ---
-dontwarn androidx.compose.**
-keep class androidx.compose.ui.platform.AndroidComposeView { *; }
-keep class androidx.compose.runtime.** { *; }
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
-keep class cn.nizou.sxd.ui.** { *; }
-keepclassmembers class cn.nizou.sxd.ui.** {
    @androidx.compose.runtime.Composable <methods>;
}