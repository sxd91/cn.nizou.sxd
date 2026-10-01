package cn.nizou.sxd.util

/**
 * 读取**宿主进程内**的 `XposedInit` 实例（注入菜单显示加载环境用）。
 *
 * ## 为什么这么写 —— 修复「注入菜单显示：加载环境 未提供 / 未注入宿主」
 *
 * 模块开了 R8（`isMinifyEnabled = true`，见 `app/build.gradle.kts`）。
 * `XposedInit` 本身有 keep，但它的 **`companion object`（`XposedInit$Companion`）没有** ——
 * R8 会把 companion 里的 `self` 字段与 `getSelf()` 方法**改名**（如 `a` / `b`）。
 *
 * 旧实现只按名字找（`getMethod("getSelf")` → `getDeclaredField("self")`），
 * 一旦被改名就**必然全失败** → 返回 null → 卡片显示「未提供 / 未注入宿主」。
 * 这正是「早些时候是好的、某个时候修坏了」的回归点
 * （提交 `e32f2e5` 把「直接读 `XposedInit$Companion.self`」改成了这种按名字反射）。
 *
 * ## 现在的策略：**先按名字，再按类型兜底**
 *
 * 关键洞察：**R8 改得掉名字，改不掉类型**。所以除了按名字找，还按
 * 「返回 / 持有 `XposedModule` 子类」这个**类型特征**去找：
 *
 *  1. companion 的 `getSelf()`（若没被改名）；
 *  2. companion 的 `self` 字段（若没被改名）；
 *  3. ★ companion 上**任意 0 参实例方法**，其返回类型是 `XposedModule` 子类；
 *  4. ★ companion 上**任意字段**，其类型是 `XposedModule` 子类；
 *  5. `XposedInit` 自身的**静态字段**（更早版本的写法）。
 *
 * 第 3/4/5 步保证：即便 R8 把名字全改了，只要实例还在 companion/静态字段里，就能拿到。
 *
 * ## 另见
 *
 * `proguard-rules.pro` 末尾也补了 `XposedInit$Companion { *; }` 的 keep —— 双保险
 * （代码兜底 + 不让 R8 动它），任何一边失效另一边仍能工作。
 */
fun readInjectedModuleSelf(): Any? {
    // 模块独立进程里 XposedInit 根本不存在（libxposed 是 compileOnly 不打包），
    // Class.forName 抛 ClassNotFoundException → 返回 null（调用方各自兜底）。
    val outer = runCatching { Class.forName("cn.nizou.sxd.XposedInit") }.getOrNull() ?: return null
    val moduleBase = runCatching { Class.forName("io.github.libxposed.api.XposedModule") }.getOrNull()

    // ---- 1) companion object 路径 ----
    val companion = runCatching { outer.getField("Companion").get(null) }.getOrNull()
    if (companion != null) {
        val cc = companion.javaClass

        // 1a) getSelf()（未改名时）
        runCatching {
            cc.methods.firstOrNull { it.name == "getSelf" && it.parameterCount == 0 }
                ?.invoke(companion)
        }.getOrNull()?.let { return it }

        // 1b) self 字段（未改名时）
        runCatching {
            cc.getDeclaredField("self").apply { isAccessible = true }.get(companion)
        }.getOrNull()?.let { return it }

        if (moduleBase != null) {
            // 1c) ★ 按**返回类型**兜底：任意 0 参方法返回 XposedModule 子类
            runCatching {
                cc.methods
                    .filter { it.parameterCount == 0 && moduleBase.isAssignableFrom(it.returnType) }
                    .mapNotNull { m -> runCatching { m.invoke(companion) }.getOrNull() }
                    .firstOrNull()
            }.getOrNull()?.let { return it }

            // 1d) ★ 按**字段类型**兜底
            runCatching {
                cc.declaredFields
                    .filter { moduleBase.isAssignableFrom(it.type) }
                    .mapNotNull { f ->
                        runCatching { f.isAccessible = true; f.get(companion) }.getOrNull()
                    }
                    .firstOrNull()
            }.getOrNull()?.let { return it }
        }
    }

    // ---- 2) XposedInit 自身的静态字段（最早的写法；R8 若内联了 companion 仍可能命中）----
    if (moduleBase != null) {
        runCatching {
            outer.declaredFields
                .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .filter { moduleBase.isAssignableFrom(it.type) }
                .mapNotNull { f ->
                    runCatching { f.isAccessible = true; f.get(null) }.getOrNull()
                }
                .firstOrNull()
        }.getOrNull()?.let { return it }
    }

    return null
}