package cn.nizou.sxd.util

/**
 * PK 秒结算 7 patch（移植自 ExElectron/Xiaoyuan_Kousuan_2026 的 MITM addon `mitm_hack_real3.py`）。
 *
 * MITM 方案是对 PK 前端 JS bundle 做**文本级正则替换**（进页即改源码，不受 Vue3 closure 限制）。
 * 模块内没有 MITM，但 RetrofitHook 拦截了宿主全部 okhttp 响应——对 PK 相关 `.js` bundle 响应
 * 做同样替换，等于「模块内建 MITM」。
 *
 * 7 个 patch（对应 mitm_hack_real3.patch_judge）：
 *  1. 判题恒真：`answerPaperResult.answer === G.CORRECT` → `true`
 *  2. 跳题 0ms：`...("finishExercise"):...("canSlideToNextQuestion")},200)` → `},0)`
 *  3. FAULT 分支短路：`answer === G.FAULT && setTimeout` → `false&&setTimeout`
 *  4. 音效静音：`new Audio(...)` → `{play:function(){return null}}`
 *  5. CSS 动画 0s：`transition: xxx` → `transition:all 0s ease`
 *  6. **OCR 回调恒真**（核心）：recognize 回调直接 `fn({answer:G.CORRECT,recognizeResult:""})`，
 *     前端永远判对 → 推进判题链（这是「画线后不推进」的根因修复）
 *  7. watch 触发放宽：`if(xxx.answerPaperResult.pathPoints)` → `if(!0)`
 *
 * 返回 (新JS, 命中次数)；cnt==0 表示 bundle 不匹配（版本漂移），调用方应原样放行。
 */
object PkBundlePatcher {

    private val MUTE = "{play:function(){return null}}"

    fun patch(js: String): Pair<String, Int> {
        var s = js
        var cnt = 0

        // 1) 判题恒真
        val p1 = Regex("""(\b\w+\.answerPaperResult\.answer\s*={2,3}\s*G\.CORRECT\b|\banswerPaperResult\.answer\s*={2,3}\s*G\.CORRECT\b)""")
        cnt += p1.findAll(s).count()
        s = p1.replace(s, "true")

        // 2) 跳题 0ms（finishExercise → canSlideToNextQuestion 后 200ms → 0ms；兜底只匹配 canSlideToNextQuestion）
        val p2 = Regex("""(\b\w+\("finishExercise"\)\s*:\s*\w+\("canSlideToNextQuestion"\)\s*\},)\s*200\)""")
        val n2 = p2.findAll(s).count()
        if (n2 > 0) {
            cnt += n2 * 10
            s = p2.replace(s) { it.groupValues[1] + "0)" }
        } else {
            val p2fb = Regex("""(\b\w+\("canSlideToNextQuestion"\)\s*\},)\s*200\)""")
            val n2fb = p2fb.findAll(s).count()
            if (n2fb > 0) {
                cnt += n2fb * 10
                s = p2fb.replace(s) { it.groupValues[1] + "0)" }
            }
        }

        // 3) FAULT 分支短路
        val p3 = Regex("""(\b\w+\.answerPaperResult\.answer\s*={2,3}\s*G\.FAULT\s*&&\s*setTimeout)""")
        cnt += p3.findAll(s).count() * 10
        s = p3.replace(s, "false&&setTimeout")

        // 4) 音效静音
        val p4 = Regex("""new\s+Audio\([^)]+\)""")
        cnt += p4.findAll(s).count() * 10
        s = p4.replace(s, MUTE)

        // 5) CSS 动画 0s
        val p5 = Regex("""transition\s*:\s*([^;"'"}]+)""")
        cnt += p5.findAll(s).count() * 10
        s = p5.replace(s) { m ->
            val v = m.groupValues[1]
            if (v.contains("0s") || v.contains("none")) m.value else "transition:all 0s ease"
        }

        // 6) OCR 回调恒真（核心）：形如 `X(n, fn=>{var a; r.value = fn || "?"` 的 recognize 回调
        //    替换为立即回调 G.CORRECT（不再等 native 识别结果）
        val p6 = Regex("""(\b\w+)\(n,\s*([a-zA-Z_$][a-zA-Z0-9_$]*)=>\{var\s+([a-zA-Z_$][a-zA-Z0-9_$]*);r\.value=\2\|\|"\?",""")
        cnt += p6.findAll(s).count() * 10
        s = p6.replace(s) { m ->
            val fnParam = m.groupValues[2]
            "((p,fn)=>{fn({answer:G.CORRECT,recognizeResult:\"\"})})(n,${fnParam}=>{var ${m.groupValues[3]};r.value=${fnParam}||\"?\","
        }

        // 7) watch 触发放宽（无 pathPoints 也能跳题）
        val p7 = Regex("""if\(\w+\.answerPaperResult\.pathPoints\)""")
        cnt += p7.findAll(s).count() * 10
        s = p7.replace(s, "if(!0)")

        // 8) 去开场动画（READY/GO）：把 readyGoEnd 触发后的长等待链拆成几乎立即执行
        //    参考 XiaoYuanKouSuan response_handler.py。重写后即使前端未播 READY/GO 也能快速进入可提交状态。
        val p8 = Regex("\\\"readyGoEnd\\\"\\)\\},.{1,8}\\)\\},.{1,8}\\)\\},.{1,8}\\)\\}")
        val n8 = p8.findAll(s).count()
        if (n8 > 0) {
            cnt += n8 * 10
            s = p8.replace(s, "\"readyGoEnd\")}),20)}),20)}),20)})")
        }

        return s to cnt
    }
}
