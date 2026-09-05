import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.lsplugin.resopt)
}

fun gitShortHash(): String = providers.exec {
    commandLine("git", "rev-parse", "--short=8", "HEAD")
}.standardOutput.asText.get().trim()

// 版本状态文件（仓库根 version.properties）：CI 每次成功构建后 +1 提交回 main。
// 构建时可用 -PversionCode=.. -PversionName=.. 覆盖（ci.yml 自增 / release.yml tag 版本）。
val versionProps = Properties().apply {
    val f = file("version.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "cn.nizou.sxd"
    compileSdk = 37

    signingConfigs {
        val jks = file("../keystore.jks")
        if (jks.exists()) {
            register("release") {
                storeFile = jks
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "cn.nizou.sxd"
        minSdk = 33
        targetSdk = 37
        versionCode = (project.findProperty("versionCode") as String? ?: versionProps.getProperty("versionCode", "20")).toInt()
        versionName = project.findProperty("versionName") as String? ?: versionProps.getProperty("versionName", "1.7.3")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 构建时间（对齐 WeKit BuildConfig.BUILD_TIMESTAMP，首页设备信息区显示）
        buildConfigField("long", "BUILD_TIMESTAMP", "${System.currentTimeMillis()}L")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release") ?: getByName("debug").signingConfig
            versionNameSuffix = runCatching { "-${gitShortHash()}" }.getOrNull() ?: ""
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    androidResources {
        // resopt 保留包 ID：默认 0x23（模块注入宿主时，模块资源不与宿主 0x7f 冲突）。
        // 如需打「可独立启动、使用标准 0x7f 包 ID」的变体，可传
        //   -PresoptPackageId=0x7f   或   -PresoptPackageId=  （空=不启用保留包 ID）
        // 独立启动资源解析失败的排查：0x7f 为应用自身标准包 ID，最稳。
        val resoptPackageId = (project.findProperty("resoptPackageId") as String? ?: "0x23")
        if (resoptPackageId.isNotBlank()) {
            additionalParameters += arrayOf(
                "--allow-reserved-package-id",
                "--package-id",
                resoptPackageId
            )
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        // JVM 21：miuix-nav 0.9.4-rc01 内联字节码是 JVM 21（wekit 同款 JDK 21）
        jvmTarget.set(JvmTarget.JVM_21)
        // 对齐 wekit：全局 OptIn Material3 Expressive API（MaterialExpressiveTheme/MotionScheme 等）
        freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
        freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)

    // --- DexKit（版本适配：按方法参数类型/字符串引用定位混淆类与方法；打包进 APK） ---
    implementation(libs.dexkit)

    // --- Compose Material3 ---
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.ui.tooling.preview)

    // --- miuix (LiquidGlass 悬浮底栏，照抄 wekit 0.9.4-rc01) ---
    implementation(libs.miuix.blur)
    implementation(libs.miuix.shader)
    implementation(libs.miuix.nav)
    // --- MaterialSymbols 图标（wekit 同款，悬浮底栏 tab 图标） ---
    implementation(libs.composablehorizons.material.symbols.outlined)
    implementation(libs.composablehorizons.material.symbols.filled)

    // --- material-kolor（wekit 同款动态配色：9 种 PaletteStyle + ColorSpec 2021/2025） ---
    implementation(libs.materialkolor)

    // --- kotlinx-serialization（@Serializable 路由，miuix-nav rememberNavBackStack saver 用） ---
    implementation(libs.kotlinx.serialization.json)
}
