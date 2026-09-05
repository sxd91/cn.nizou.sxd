package cn.nizou.sxd

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet

/** Standalone-process Xposed service state; host-injected UI does not depend on this. */
class AutoOralApplication : Application(), XposedServiceHelper.OnServiceListener {
    companion object {
        @Volatile var service: XposedService? = null
            private set
        private val listeners = CopyOnWriteArraySet<(XposedService?) -> Unit>()

        fun addServiceListener(listener: (XposedService?) -> Unit) { listeners += listener; listener(service) }
        fun removeServiceListener(listener: (XposedService?) -> Unit) { listeners -= listener }
        private fun dispatch() { listeners.forEach { it(service) } }
    }

    override fun onCreate() { super.onCreate(); XposedServiceHelper.registerListener(this) }
    override fun onServiceBind(service: XposedService) { Companion.service = service; dispatch() }
    override fun onServiceDied(service: XposedService) { if (Companion.service === service) Companion.service = null; dispatch() }
}
