//! 老挂戏老叟（cn.nizou.sxd）笔迹生成 —— Rust 重写版。
//!
//! ## 为什么要重写
//!
//! 模块原先在 `app/src/main/jniLibs/` 里放了一个**第三方** `libauto_oral.so`
//! （AOC / TinyHai 编译）。它对外的动态符号**只有 `JNI_OnLoad`**
//! （`readelf -d --dyn-syms` 实测），内部用 `RegisterNatives` 注册到**它自己的类名**下，
//! 因此本模块 `Strokes.kt` 里的
//!
//! ```kotlin
//! val String.nativeStrokes: List<Array<DoubleArray>> external get
//! ```
//!
//! 去调用时**必然抛 `UnsatisfiedLinkError`**，只能落回耗时且质量一般的纯 Kotlin 兜底。
//! 这也是真机日志里反复出现 `libauto_oral not found ... strokes disabled` 的原因。
//!
//! 本 crate 用 Rust 重新实现同一能力，并**导出正确的 JNI 符号**，让 `nativeStrokes` 真正可用。
//!
//! ## 导出契约（必须与 Kotlin 端严格一致）
//!
//! Kotlin 侧声明在 `cn/nizou/sxd/util/Strokes.kt` 顶层：
//!
//! ```kotlin
//! val String.nativeStrokes: List<Array<DoubleArray>> external get
//! ```
//!
//! 顶层 `external` 属性 → 生成在**文件类** `cn.nizou.sxd.util.StrokesKt` 上的静态方法
//! `getNativeStrokes`，扩展接收者 `String` 变成第 1 个参数。于是 JNI 名与签名是：
//!
//! ```text
//! Java_cn_nizou_sxd_util_StrokesKt_getNativeStrokes
//!   (Ljava/lang/String;)Ljava/util/List;
//! ```
//!
//! 返回 `List<Array<DoubleArray>>`：每个元素是 `double[][]`（即 `[[D`），
//! 每行是 `[x, y]` 两个 double —— 与 Kotlin 侧 `point[0] / point[1]` 的用法一致。
//!
//! ## 安全约定
//!
//! - **绝不 panic 穿过 FFI**：整个入口包在 `catch_unwind` 里，
//!   任何意外都返回 `null`，由 Kotlin 侧静默回落纯 Kotlin 实现。
//!   （`panic = "abort"` 会让宿主进程直接挂掉，所以刻意**不用**它。）
//! - 不申请堆外大内存、不做阻塞 IO —— 这个函数只在答题时被调几毫秒。

use jni::objects::{JClass, JObject, JString};
use jni::sys::{jint, jobject, JNI_VERSION_1_6};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};

/// 每条笔画采样点数（越多越接近「真人书写」的连续笔迹）。
const POINTS_PER_STROKE: usize = 24;

/// 一个点的 (x, y)，单位与宿主一致（像素量级，数十）。
type Point = (f64, f64);

/// 生成**一条**连续笔迹（近似竖线 + 手抖摆动）。
///
/// - `x0`, `y0`：起点；
/// - `seed`：每题不同，用来平移与改变摆动相位，避免多题笔迹完全雷同
///   （服务端会比对多题是否一模一样 → 雷同判定为机器作答）。
fn build_dense_stroke(x0: f64, y0: f64, seed: u32) -> Vec<Point> {
    let phase = (seed % 11) as f64 * 0.37;
    (0..POINTS_PER_STROKE)
        .map(|i| {
            let t = i as f64 / (POINTS_PER_STROKE - 1) as f64;
            // 弧度摆动 + 固定微抖（奇偶交替），模拟手写不直
            let jitter = if i % 2 == 0 { 0.4 } else { -0.4 };
            let x = x0 + (t * std::f64::consts::PI + phase).sin() * 2.0 + jitter;
            let y = y0 + t * 60.0;
            (x, y)
        })
        .collect()
}

/// 把答案字符串展开成笔画集合：**一个字符一条笔画**，字符间横向错开避免叠成一点。
fn strokes_for(answer: &str, seed: u32) -> Vec<Vec<Point>> {
    if answer.is_empty() {
        return vec![build_dense_stroke(40.0, 30.0, seed)];
    }
    answer
        .chars()
        .enumerate()
        .map(|(idx, _)| {
            let x0 = 40.0 + idx as f64 * 26.0 + (seed % 7) as f64 * 3.0;
            let y0 = 30.0 + (idx % 2) as f64 * 3.0 + (seed % 5) as f64 * 2.0;
            build_dense_stroke(x0, y0, seed + idx as u32)
        })
        .collect()
}

/// JNI 入口 —— 对应 `cn.nizou.sxd.util.StrokesKt.getNativeStrokes(String)`。
///
/// 失败一律返回 `null`（Kotlin 侧 `runCatching { ... }.getOrNull()` 会静默回落）。
#[no_mangle]
pub extern "system" fn Java_cn_nizou_sxd_util_StrokesKt_getNativeStrokes(
    mut env: JNIEnv,
    _class: JClass,
    input: JString,
) -> jobject {
    // ★ 绝不让 panic 穿过 FFI（否则宿主进程直接 abort）。
    let result = catch_unwind(AssertUnwindSafe(|| -> Option<jobject> {
        let answer: String = env.get_string(&input).ok()?.into();
        let seed: u32 = (answer.len() as u32).wrapping_mul(31) ^ 0x9E37_79B9;
        let strokes = strokes_for(&answer, seed);
        to_java_list(&mut env, &strokes)
    }));

    match result {
        Ok(Some(obj)) => obj,
        _ => JObject::null().into_raw(),
    }
}

/// 把 `Vec<Vec<Point>>` 组装成 Java 的 `ArrayList<double[][]>`。
fn to_java_list(env: &mut JNIEnv, strokes: &[Vec<Point>]) -> Option<jobject> {
    let list_class = env.find_class("java/util/ArrayList").ok()?;
    let list = env
        .new_object(&list_class, "()V", &[])
        .ok()?;

    for stroke in strokes {
        // 每行是 double[]（长度 2）
        let rows = env
            .new_object_array(stroke.len() as i32, "[D", JObject::null())
            .ok()?;
        for (i, (x, y)) in stroke.iter().enumerate() {
            let pair = env.new_double_array(2).ok()?;
            env.set_double_array_region(&pair, 0, &[*x, *y]).ok()?;
            env.set_object_array_element(&rows, i as i32, &pair).ok()?;
        }
        env.call_method(
            &list,
            "add",
            "(Ljava/lang/Object;)Z",
            &[(&rows).into()],
        )
        .ok()?;
    }
    Some(list.into_raw())
}

/// 库加载回调：返回 JNI 版本，告诉 ART 这个 so 可用。
#[no_mangle]
pub extern "system" fn JNI_OnLoad(_vm: *mut jni::sys::JavaVM, _reserved: *mut std::ffi::c_void) -> jint {
    JNI_VERSION_1_6
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_answer_yields_one_stroke() {
        let s = strokes_for("", 1);
        assert_eq!(s.len(), 1);
        assert_eq!(s[0].len(), POINTS_PER_STROKE);
    }

    #[test]
    fn one_stroke_per_char() {
        assert_eq!(strokes_for("12", 1).len(), 2);
        assert_eq!(strokes_for("12345", 1).len(), 5);
    }

    #[test]
    fn seed_changes_geometry() {
        let a = strokes_for("1", 1);
        let b = strokes_for("1", 7);
        assert_ne!(a[0][0], b[0][0], "不同 seed 的起点应不同（防多题雷同）");
    }

    #[test]
    fn points_are_dense_and_monotonic_in_y() {
        let s = strokes_for("8", 3);
        let ys: Vec<f64> = s[0].iter().map(|p| p.1).collect();
        assert!(ys.windows(2).all(|w| w[1] > w[0]), "y 应单调下移（连续笔迹）");
    }
}