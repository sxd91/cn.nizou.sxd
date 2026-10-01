package cn.nizou.sxd.util

/**
 * H5 页面 URL 规范化与页面类型判定（2026-10-01）。
 *
 * ## 为什么需要
 *
 * 老实现用**字面量 contains** 去匹配 WebView 加载的 URL，例如
 * `str.contains("leo-web-math-exercise/animation-oral.html")`。但真机上：
 *
 *  1. PK 页走**离线 webbundle**，缓存目录名带版本号后缀：
 *     `cache/webbundle/leo-web-oral-pk_1789638371210/pk.html`
 *     → `leo-web-oral-pk` 与 `pk.html` 之间**夹着 `_1789638371210`**，字面量永不命中；
 *  2. 新版答题页文件名从 `exercise.html` 换成了 `pk.html`（SPA，答题态走 hash）；
 *  3. URL 带 query（`?url=...`）或 hash（`#/`），`substringBefore("?")` 处理不干净。
 *
 * 结果：`loadUrl` 分支**从不命中** → `pkPageLoaded` 永不置位 → 注入与笔画提交全部静默失效。
 *
 * ## 做法
 *
 * 先 [normalizePage] 把 URL 归一化（去 query、去 hash、去 `_<10+位版本号>`、转小写），
 * 再用宽松关键字判定页面类型。判定只看**服务名 + 页面名**，不依赖版本号与顺序。
 */
object PageUrl {

    /** 版本号后缀：`_1789638371210`（10 位以上纯数字）。 */
    private val VERSION_SUFFIX = Regex("_[0-9]{10,}")

    /**
     * 归一化：去 query、去 hash、去掉 bundle 版本号后缀、转小写。
     *
     * @return 形如 `.../bh5/leo-web-oral-pk/pk.html` 的小写字符串。
     */
    fun normalizePage(url: String): String {
        val noFrag = url.substringBefore('#')
        val noQuery = noFrag.substringBefore('?')
        return VERSION_SUFFIX.replace(noQuery, "").lowercase()
    }

    /** URL 里的 hash 路由（`#/exercise` → `/exercise`），已转小写。 */
    fun hashRoute(url: String): String =
        url.substringAfter('#', "").substringBefore('?').lowercase()

    /** 归一化路径 + hash 路由，合并成一个可搜索串。 */
    private fun haystack(url: String): String = normalizePage(url) + "#" + hashRoute(url)

    /** 是否为 PK 答题页（3.94-3.13x 的 `exercise.html`，3.140+ 的 `pk.html`/`animation-oral.html`）。 */
    fun isPkExercise(url: String): Boolean {
        val s = haystack(url)
        if (s.contains("animation-oral")) return true
        if (s.contains("english-words")) return false // 英语词卡是另一条链路
        // ★ 真机踩坑：`pk.html` 是 PK 的**外壳页**（含主页/匹配/答题等所有 SPA 状态）。
        // 单看文件名会把 pk.html 误判成答题页，在主页就 scheduleStroke（真机日志已复现）。
        // 只有 hash 路由里出现 exercise 才算答题态。
        if (s.contains("/pk.html")) return hashRoute(url).contains("exercise")
        return s.contains("exercise") &&
            (s.contains("oral-pk") || s.contains("math-exercise"))
    }

    /** 是否为英语词卡页。 */
    fun isEnglishWords(url: String): Boolean = haystack(url).contains("english-words")

    /** 是否为 PK 结算/结果页。 */
    fun isPkResult(url: String): Boolean = haystack(url).contains("result.html")

    /** 是否为荣誉榜（开心收下 / 继续）页。 */
    fun isHonorRoll(url: String): Boolean = haystack(url).contains("motivation-honor-roll")

    /** 是否属于 PK 相关服务（用于「离开 PK 链路」的负判定）。 */
    fun isPkRelated(url: String): Boolean {
        val s = haystack(url)
        return s.contains("leo-web-oral-pk") || s.contains("leo-web-math-exercise") ||
            s.contains("animation-oral") || s.contains("motivation-honor-roll")
    }
}
