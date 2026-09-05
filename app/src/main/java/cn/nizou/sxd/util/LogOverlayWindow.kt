package cn.nizou.sxd.util

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import cn.nizou.sxd.MODULE_PREFS_NAME
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * Simian-style host-attached real-time log window.
 * It follows the resumed host Activity, supports dragging and a 58dp floating ball,
 * and keeps rendering new records after the visible buffer is cleared.
 */
object LogOverlayWindow {
    const val PREFS_KEY_OVERLAY = "log_overlay_enabled"
    const val HOST_PACKAGE = "com.fenbi.android.leo"
    private const val REFRESH_MS = 500L
    private const val PANEL_COLOR = 0xED102021.toInt()
    private const val ACCENT = 0xFF006A66.toInt()

    private val overlays = WeakHashMap<Activity, View>()
    private val installedApps = WeakHashMap<Application, Boolean>()
    private val outputs = mutableListOf<WeakReference<TextView>>()
    private var hostApplication: WeakReference<Application>? = null
    private var resumedActivity: WeakReference<Activity>? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (!enabled) return
            val snapshot = LogBuffer.snapshotText()
            cleanOutputs()
            outputs.forEach { reference ->
                reference.get()?.let { output ->
                    if (output.text.toString() != snapshot) {
                        output.text = snapshot
                        val scroll = output.parent as? ScrollView
                        scroll?.post { scroll.fullScroll(View.FOCUS_DOWN) }
                    }
                }
            }
            mainHandler.postDelayed(this, REFRESH_MS)
        }
    }

    @Volatile private var enabled = false
    @Volatile private var minimized = false

    val isShowing get() = overlays.isNotEmpty()
    fun isHostProcess(context: Context) = context.applicationContext.packageName == HOST_PACKAGE
    fun isEnabled(context: Context) = context.applicationContext
        .getSharedPreferences(MODULE_PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(PREFS_KEY_OVERLAY, false)

    /** Registers exactly once during host attach and reconnects the panel on every Activity resume. */
    fun install(application: Application) {
        if (application.packageName != HOST_PACKAGE) return
        hostApplication = WeakReference(application)
        enabled = isEnabled(application)
        if (installedApps.put(application, true) != null) return
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumedActivity = WeakReference(activity)
                if (enabled) {
                    // Post after the resumed DecorView is attached; this also reattaches after back navigation.
                    activity.window.decorView.post { attachTo(activity) }
                }
            }
            override fun onActivityDestroyed(activity: Activity) {
                overlays.remove(activity)?.let { (it.parent as? ViewGroup)?.removeView(it) }
                cleanOutputs()
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) {
                if (enabled) activity.window.decorView.post { attachTo(activity) }
            }
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        })
    }

    @Synchronized
    fun setEnabled(context: Context, enable: Boolean): Boolean {
        if (!isHostProcess(context)) return false
        enabled = enable
        context.applicationContext.getSharedPreferences(MODULE_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(PREFS_KEY_OVERLAY, enable).apply()
        if (enable) {
            val activity = findActivity(context) ?: resumedActivity?.get() ?: return false
            attachTo(activity)
        } else {
            removeAllOverlays()
        }
        return true
    }

    private fun attachTo(activity: Activity) = runOnMain {
        if (!enabled || activity.isFinishing || activity.isDestroyed || overlays.containsKey(activity)) return@runOnMain
        overlays.keys.filter { it !== activity }.toList().forEach { old ->
            overlays.remove(old)?.let { (it.parent as? ViewGroup)?.removeView(it) }
        }
        val root = activity.window.decorView as? ViewGroup ?: return@runOnMain
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            elevation = dp(activity, 12).toFloat()
        }
        overlays[activity] = panel
        root.addView(panel)
        if (minimized) configureCollapsed(activity, panel, root, preservePosition = false)
        else configureExpanded(activity, panel, root, preservePosition = false)
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.post(refreshRunnable)
    }

    @Synchronized
    fun hide() {
        enabled = false
        minimized = false
        hostApplication?.get()?.getSharedPreferences(MODULE_PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()?.putBoolean(PREFS_KEY_OVERLAY, false)?.apply()
        removeAllOverlays()
    }

    private fun removeAllOverlays() {
        mainHandler.removeCallbacks(refreshRunnable)
        runOnMain {
            overlays.values.toList().forEach { (it.parent as? ViewGroup)?.removeView(it) }
            overlays.clear()
            outputs.clear()
        }
    }

    private fun configureExpanded(activity: Activity, panel: LinearLayout, root: ViewGroup, preservePosition: Boolean) {
        val oldX = panel.x
        val oldY = panel.y
        panel.removeAllViews()
        panel.orientation = LinearLayout.VERTICAL
        panel.gravity = Gravity.NO_GRAVITY
        panel.background = rounded(PANEL_COLOR, dp(activity, 16).toFloat())
        val output = TextView(activity).apply {
            setTextColor(0xFFD8F7ED.toInt())
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(dp(activity, 12), dp(activity, 8), dp(activity, 12), dp(activity, 10))
            text = LogBuffer.snapshotText()
        }
        outputs.add(WeakReference(output))
        val scroll = ScrollView(activity).apply { isFillViewport = true; addView(output) }
        panel.addView(createHeader(activity, panel, root), LinearLayout.LayoutParams(-1, dp(activity, 42)))
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        panel.layoutParams = FrameLayout.LayoutParams(
            minOf(dp(activity, 310), activity.resources.displayMetrics.widthPixels - dp(activity, 24)),
            dp(activity, 230),
            Gravity.TOP or Gravity.END,
        ).apply { if (!preservePosition) { topMargin = dp(activity, 88); marginEnd = dp(activity, 12) } }
        if (preservePosition) restorePosition(panel, root, oldX, oldY)
        makeDraggable(panel.getChildAt(0), panel, root)
    }

    private fun configureCollapsed(activity: Activity, panel: LinearLayout, root: ViewGroup, preservePosition: Boolean) {
        val oldX = panel.x
        val oldY = panel.y
        panel.removeAllViews()
        cleanOutputs()
        panel.gravity = Gravity.CENTER
        panel.background = rounded(ACCENT, dp(activity, 40).toFloat())
        panel.addView(TextView(activity).apply {
            text = "日志"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(-1, -1))
        panel.layoutParams = FrameLayout.LayoutParams(dp(activity, 58), dp(activity, 58), Gravity.TOP or Gravity.END).apply {
            if (!preservePosition) { topMargin = dp(activity, 110); marginEnd = dp(activity, 12) }
        }
        if (preservePosition) restorePosition(panel, root, oldX, oldY)
        makeDraggable(panel, panel, root) {
            minimized = false
            configureExpanded(activity, panel, root, preservePosition = true)
        }
    }

    private fun createHeader(activity: Activity, panel: LinearLayout, root: ViewGroup): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(activity, 12), 0, dp(activity, 5), 0)
        background = rounded(ACCENT, dp(activity, 16).toFloat())
        addView(TextView(activity).apply {
            text = "实时日志"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(headerButton(activity, "清空") { LogBuffer.clear() })
        addView(headerButton(activity, "—") {
            minimized = true
            configureCollapsed(activity, panel, root, preservePosition = true)
        })
        addView(headerButton(activity, "×") { hide() })
    }

    private fun headerButton(context: Context, label: String, action: () -> Unit) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        textSize = if (label == "×") 22f else 11f
        setPadding(dp(context, 8), 0, dp(context, 8), 0)
        setOnClickListener { action() }
    }

    private fun makeDraggable(handle: View, panel: View, root: ViewGroup, onTap: (() -> Unit)? = null) {
        var downX = 0f
        var downY = 0f
        var startX = 0f
        var startY = 0f
        var moved = false
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; startX = panel.x; startY = panel.y; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    panel.x = (startX + dx).coerceIn(0f, (root.width - panel.width).coerceAtLeast(0).toFloat())
                    panel.y = (startY + dy).coerceIn(0f, (root.height - panel.height).coerceAtLeast(0).toFloat())
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) onTap?.invoke(); true }
                else -> true
            }
        }
    }

    private fun restorePosition(panel: View, root: ViewGroup, x: Float, y: Float) = panel.post {
        panel.x = x.coerceIn(0f, (root.width - panel.width).coerceAtLeast(0).toFloat())
        panel.y = y.coerceIn(0f, (root.height - panel.height).coerceAtLeast(0).toFloat())
    }
    private fun cleanOutputs() { outputs.removeAll { it.get() == null || it.get()?.parent == null } }
    private fun findActivity(context: Context): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }
    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }
}
