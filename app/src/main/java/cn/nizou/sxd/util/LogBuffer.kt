package cn.nizou.sxd.util

import java.util.ArrayDeque

/**
 * 内存环形日志缓冲（单例）。[logI] 每写一条日志就 push 进来，实时日志悬浮窗每帧拉取
 * [snapshot] / [snapshotText] 渲染。容量封顶 [CAPACITY]，超过后丢弃最旧。
 * 线程安全：写入来自宿主/模块任意线程，读取来自悬浮窗主线程刷新，统一加锁。
 */
object LogBuffer {

    const val CAPACITY = 500

    private val logs = ArrayDeque<String>()

    @Synchronized
    fun add(line: String) {
        logs.addLast(line)
        while (logs.size > CAPACITY) {
            logs.removeFirst()
        }
    }

    @Synchronized
    fun snapshot(): List<String> = logs.toList()

    @Synchronized
    fun snapshotText(): String {
        if (logs.isEmpty()) return "(暂无日志)"
        val sb = StringBuilder()
        for (line in logs) {
            sb.append(line).append('\n')
        }
        return sb.toString()
    }

    @Synchronized
    fun clear() = logs.clear()
}

/**
 * 「真自定义分数」专属**运行日志**（独立于全局 LogBuffer）。
 *
 * ## 为什么单独一个
 *
 * 用户诉求（2026-10-01）：「真自定义分数应该有个运行日志框，就和逆向系老挂一样」。
 * 刷分过程是多轮循环 + 频控退避，混在全量日志里根本看不清「第几轮、卡在哪一步」。
 * 这里收**刷分链路自己的关键事件**（开始/出题/提交/入账/频控/结束），
 * 供设置页实时滚动显示；同时也镜像进全局 [LogBuffer]（文件日志不受影响）。
 *
 * 容量比全局小（[CAPACITY]），因为只需要看最近几轮。
 */
object ScoreLog {
    const val CAPACITY = 300

    private val logs = ArrayDeque<String>()

    @Synchronized
    fun add(line: String) {
        logs.addLast(line)
        while (logs.size > CAPACITY) logs.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<String> = logs.toList()

    @Synchronized
    fun clear() = logs.clear()
}
