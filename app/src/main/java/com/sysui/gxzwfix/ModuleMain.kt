package com.sysui.gxzwfix

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.util.concurrent.ConcurrentHashMap

/**
 * by github@aikavvak12una
 */
class ModuleMain : XposedModule() {

    companion object {
        const val TAG = "SysuiGxzwFix"
        const val TARGET_PACKAGE = "com.android.systemui"

        // 功能一：nsslLockYPosition 的 combine flow 类
        const val NSSL_COMBINE_CLASS =
            "com.android.keyguard.panel.KeyguardPanelViewController\$nsslLockYPosition_delegate\$lambda\$106\$\$inlined\$combine\$1"
        const val FLOWS_FIELD = "\$flows\$inlined"

        // 功能二：GXZW 指纹图标 View
        const val GXZW_ICON_VIEW = "com.miui.keyguard.biometrics.fod.MiuiGxzwIconView"
        const val LONG_PRESS_MS = 300L

        // 功能二运行时状态
        private val MAIN_HANDLER = Handler(Looper.getMainLooper())
        private val longPressTasks = ConcurrentHashMap<Any, Runnable>()
        private val longPressFired = ConcurrentHashMap.newKeySet<Any>()

        private fun getField(obj: Any, name: String): Any? {
            var cls: Class<*>? = obj.javaClass
            while (cls != null) {
                try {
                    val f = cls.getDeclaredField(name)
                    f.isAccessible = true
                    return f.get(obj)
                } catch (_: NoSuchFieldException) {
                    cls = cls.superclass
                }
            }
            return null
        }

        private fun setField(obj: Any, name: String, value: Any?) {
            var cls: Class<*>? = obj.javaClass
            while (cls != null) {
                try {
                    val f = cls.getDeclaredField(name)
                    f.isAccessible = true
                    f.set(obj, value)
                    return
                } catch (_: NoSuchFieldException) {
                    cls = cls.superclass
                }
            }
        }

        private fun callMethod(obj: Any, name: String, vararg args: Any?) {
            val argTypes = args.map {
                when (it) {
                    is Boolean -> Boolean::class.javaPrimitiveType
                    is Int -> Int::class.javaPrimitiveType
                    is Float -> Float::class.javaPrimitiveType
                    is Long -> Long::class.javaPrimitiveType
                    is Double -> Double::class.javaPrimitiveType
                    else -> it?.javaClass
                }
            }.toTypedArray()
            val m = obj.javaClass.getMethod(name, *argTypes)
            m.isAccessible = true
            m.invoke(obj, *args)
        }
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "onModuleLoaded: process=${param.processName}")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName != TARGET_PACKAGE) return
        log(Log.INFO, TAG, "onPackageLoaded: pkg=${param.packageName}")
        installHooks(param.defaultClassLoader)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != TARGET_PACKAGE) return
        installHooks(param.classLoader)
    }

    // API 102 热重载：允许模块 dex 更新后热加载，无需重启 SystemUI
    override fun onHotReloading(param: HotReloadingParam): Boolean {
        log(Log.INFO, TAG, "onHotReloading: allow hot reload")
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        log(Log.INFO, TAG, "onHotReloaded: removing ${param.oldHookHandles.size} old hooks")
        param.oldHookHandles.forEach { it.unhook() }
        longPressTasks.clear()
        longPressFired.clear()
    }

    private fun installHooks(cl: ClassLoader) {
        installNsslFixHook(cl)
        installTouchSplitHook(cl)
    }

    /** 功能一：nsslLockYPosition 布局链走"无 GXZW"分支（通知不被顶）。 */
    private fun installNsslFixHook(cl: ClassLoader) {
        try {
            val combineClass = Class.forName(NSSL_COMBINE_CLASS, false, cl)
            val flowCollectorClass = Class.forName("kotlinx.coroutines.flow.FlowCollector", false, cl)
            val continuationClass = Class.forName("kotlin.coroutines.Continuation", false, cl)
            val collect = combineClass.getMethod("collect", flowCollectorClass, continuationClass)

            val stateFlowKtClass = Class.forName("kotlinx.coroutines.flow.StateFlowKt", false, cl)
            val factory = stateFlowKtClass.getMethod("MutableStateFlow", Any::class.java)
            val falseFlow = factory.invoke(null, java.lang.Boolean.FALSE)

            hook(collect).intercept { chain ->
                try {
                    val flows = getField(chain.thisObject, FLOWS_FIELD) as? Array<*>
                    if (flows != null && flows.size >= 7 && flows[5] !== falseFlow) {
                        java.lang.reflect.Array.set(flows, 5, falseFlow) // fingerApplyForKeyguard
                        java.lang.reflect.Array.set(flows, 6, falseFlow) // hasEnrolledTemplatesFlow
                        log(Log.INFO, TAG, "nsslLockYPosition flows patched: idx5/6 -> false")
                    }
                } catch (t: Throwable) {
                    log(Log.ERROR, TAG, "nsslLockYPosition flow patch failed (original kept)", t)
                }
                chain.proceed()
                null
            }
            log(Log.INFO, TAG, "hook installed: nsslLockYPosition combine collect -> false")
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "nsslLockYPosition hook install failed (fail closed)", t)
        }
    }




    private fun installTouchSplitHook(cl: ClassLoader) {
        try {
            val iconViewClass = Class.forName(GXZW_ICON_VIEW, false, cl)

            //    完全跳过该窗口，触摸直达下层（堆叠通知可交互）
            val windowFrameClass = Class.forName(
                "com.miui.keyguard.biometrics.fod.GxzwWindowFrameLayout", false, cl
            )
            val addViewToWindow = windowFrameClass.getDeclaredMethod("addViewToWindow")
            hook(addViewToWindow).intercept { chain ->
                val self = chain.thisObject
                if (self != null) {
                    val lp = getField(self, "mLayoutParams") as? WindowManager.LayoutParams
                    if (lp != null && (lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0) {
                        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        log(
                            Log.INFO, TAG,
                            "gxzw window FLAG_NOT_TOUCHABLE set: ${self.javaClass.name}"
                        )
                    }
                }
                chain.proceed()
                null
            }

            // 2. onPointerEvent（内部类 MiuiGxzwIconView$2，全局手势监控）：
            //    DOWN 延迟 300ms 才走原逻辑（指纹）；300ms 内 UP = 点击（透传）；
            //    指纹触发后 MOVE/UP 放行原逻辑（QuickOpen 快捷面板保留）
            val listenerClass = Class.forName(GXZW_ICON_VIEW + "\$2", false, cl)
            val onPointerEvent = listenerClass.getMethod("onPointerEvent", MotionEvent::class.java)
            hook(onPointerEvent).intercept { chain ->
                val listener = chain.thisObject
                val self = listener?.let { getField(it, "this\$0") }
                val event = chain.args[0] as? MotionEvent
                if (self == null || event == null) {
                    return@intercept chain.proceed()
                }
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val region = getField(self, "mRegion") as? Rect
                        if (region != null && region.contains(event.x.toInt(), event.y.toInt())) {
                            setField(self, "isCatchDownEvent", true)
                            startLongPress(self) // 300ms 后调用 onTouchDown（指纹）
                            null // 跳过原逻辑：不立即触发指纹
                        } else {
                            chain.proceed() // 传感器区域外：原逻辑
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        setField(self, "isCatchDownEvent", false)
                        val wasFired = longPressFired.remove(self) != null
                        cancelLongPress(self)
                        // 放行原逻辑（onTouchUp 收尾）；点击时 mTouchDown 未置位则原逻辑直接返回
                        chain.proceed()
                        null
                    }
                    else -> {
                        if (longPressFired.contains(self)) {
                            // 指纹已触发：放行原逻辑（QuickOpen 快捷面板、动画等继续）
                            chain.proceed()
                        } else {
                            null // 300ms 内：不处理，触摸透传给通知（滚动/点击）
                        }
                    }
                }
            }

            log(Log.INFO, TAG, "hook installed: GXZW touch split (tap<" + LONG_PRESS_MS +
                "ms pass-through, long-press fingerprint + QuickOpen kept)")
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "touch split hook install failed (fail closed)", t)
        }
    }

    private fun startLongPress(self: Any) {
        cancelLongPress(self)
        val task = Runnable {
            try {
                log(Log.INFO, TAG, "long-press $LONG_PRESS_MS" +
                    "ms reached, calling original onTouchDown (fingerprint)")
                callMethod(self, "onTouchDown")
                longPressFired.add(self)
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "onTouchDown call failed", t)
            }
        }
        longPressTasks[self] = task
        MAIN_HANDLER.postDelayed(task, LONG_PRESS_MS)
    }

    private fun cancelLongPress(self: Any) {
        longPressTasks.remove(self)?.let { MAIN_HANDLER.removeCallbacks(it) }
    }
}
