package io.github.hyperisland.xposed.hook

import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import io.github.hyperisland.xposed.logWarn
import io.github.hyperisland.xposed.utils.HookUtils
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap

/** A nonzero brightness rise on a native return/resume; existing glow stays native. */
internal object StableIslandGlowFadeHook {
    private const val CONTENT = "miui.systemui.dynamicisland.window.content.DynamicIslandContentView"
    private const val GLOW = "miui.systemui.dynamicisland.view.DynamicGlowEffectView"
    private const val LIGHT = "com.mi.widget.view.LightBgView"
    private const val SHADER = "com.mi.widget.shader.LightBgShader"
    private const val DELEGATE = "miui.systemui.dynamicisland.anim.DynamicIslandAnimationDelegate"
    private const val ANIMATION = "miui.systemui.dynamicisland.anim.DynamicIslandAnimationController"
    private const val STATE = "miui.systemui.dynamicisland.event.DynamicIslandState"
    private const val MAX_CONTROLLERS = 32
    private const val MAX_PENDING_MILLIS = 2000L
    private val hooked = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val controllers = WeakHashMap<View, Controller>()
    private var failureReported = false
    private var sampledEpisodes = 0
    private var admissionSamples = 0
    private var swipeEpoch = 0L
    private var swipeDepth = 0

    fun install(module: XposedModule, loader: ClassLoader) {
        if (!HookUtils.isIslandLoaderReady(loader)) return
        val access = runCatching { Access(loader) }.getOrNull() ?: return
        synchronized(hooked) { if (!hooked.add(access.contentClass)) return }
        try {
            access.glowEvents.forEach { method ->
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    safe(module) { bind(chain.thisObject as? View, access) }
                    result
                }
            }
            module.hook(access.attached).intercept { chain ->
                val result = chain.proceed()
                safe(module) { bind(access.big.invoke(chain.thisObject) as? View, access) }
                result
            }
            module.hook(access.detached).intercept { chain ->
                safe(module) { controllers[chain.thisObject]?.dispose() }
                chain.proceed()
            }
            module.hook(access.visibility).intercept { chain ->
                val result = chain.proceed()
                if (chain.args[0] == false) safe(module) { controllers[chain.thisObject]?.hidden() }
                result
            }
            module.hook(access.alpha).intercept { chain ->
                val result = chain.proceed()
                if ((chain.args[0] as? Float)?.let { it <= 0f } == true) {
                    safe(module) { controllers[chain.thisObject]?.hidden() }
                }
                result
            }
            module.hook(access.stateChange).intercept { chain ->
                var controller: Controller? = null
                var event: Transition? = null
                safe(module) {
                    val content = chain.args[0] as? View ?: return@safe
                    controller = bind(access.big.invoke(content) as? View, access)
                    event = controller?.prepareTransition()
                }
                val result = try { chain.proceed() } catch (error: Throwable) {
                    safe(module) { controller?.cancel() }
                    throw error
                }
                safe(module) { event?.let { controller?.finishTransition(it) } }
                result
            }
            module.hook(access.swipe).intercept { chain ->
                // A swipe can involve two existing contents; release both, including a pending target.
                val main = Looper.myLooper() == Looper.getMainLooper()
                if (main) {
                    swipeEpoch++
                    swipeDepth++
                    safe(module) { controllers.values.toList().forEach { it.cancelForSwipe() } }
                }
                try { chain.proceed() } finally { if (main) swipeDepth-- }
            }
            module.hook(access.resume).intercept { chain ->
                var controller: Controller? = null
                var event: Transition? = null
                safe(module) {
                    val content = chain.args[0] as? View ?: return@safe
                    controller = bind(access.big.invoke(content) as? View, access)
                    event = controller?.prepareResume(chain.args[1] == true)
                }
                val result = try { chain.proceed() } catch (error: Throwable) {
                    safe(module) { controller?.cancel() }
                    throw error
                }
                safe(module) { event?.let { controller?.finishTransition(it) } }
                result
            }
            module.log(Log.INFO, "HyperIsland[StableGlowFade]", "bound: native return/resume, 0.5 to 1 over 900ms; swipe retains native glow")
        } catch (error: Throwable) { report(module, error) }
    }

    private inline fun safe(module: XposedModule, action: () -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        try { action() } catch (error: Throwable) { report(module, error) }
    }

    private fun report(module: XposedModule, error: Throwable) {
        if (failureReported) return
        failureReported = true
        module.logWarn("HyperIsland[StableGlowFade]", "native glow retained: ${error.javaClass.simpleName}")
    }

    private fun admission(stage: String) {
        if (admissionSamples >= 12) return
        admissionSamples++
        Log.i("HyperIslandStableGlowFade", "admission: $stage at=${SystemClock.uptimeMillis()}")
    }

    private fun bind(glow: View?, access: Access): Controller? {
        if (glow == null) return null
        var ancestor: View? = glow
        var depth = 0
        while (ancestor != null && !access.contentClass.isInstance(ancestor) && depth++ < 32) {
            ancestor = ancestor.parent as? View
        }
        val content = ancestor?.takeIf { access.contentClass.isInstance(it) } ?: return null
        if (access.big.invoke(content) !== glow || !content.isAttachedToWindow) return null
        controllers[content]?.let { it.replaceGlow(glow); return it }
        if (controllers.size >= MAX_CONTROLLERS) return null
        val controller = Controller(content, glow, access)
        controllers[content] = controller
        try { controller.observe() } catch (error: Throwable) {
            controller.dispose()
            throw error
        }
        return controller
    }

    private class Access(loader: ClassLoader) {
        val contentClass = loader.loadClass(CONTENT)
        private val glowClass = loader.loadClass(GLOW)
        private val lightClass = loader.loadClass(LIGHT)
        private val shaderClass = loader.loadClass(SHADER)
        private val stateClass = loader.loadClass(STATE)
        val state = contentClass.getMethod("getState")
        val lastState = contentClass.getMethod("getLastState")
        val data = contentClass.getMethod("getCurrentIslandData")
        val windowState = contentClass.getMethod("getWindowState")
        val properties = data.returnType.getMethod("getProperties")
        val tempHidden = windowState.returnType.getMethod("isTempHidden", java.lang.Integer::class.java)
        val big = contentClass.getMethod("getBigIslandView")
        val upper = glowClass.getMethod("getMGlowEffectUpperView")
        val lower = glowClass.getMethod("getMGlowEffectBottomView")
        val container = glowClass.getMethod("getMContainer")
        val enabled = field(glowClass, "enabledGlowEffect")
        val running = field(lightClass, "mRunning")
        val shader = field(lightClass, "mShader")
        val foreground = shaderClass.getMethod("getFgView")
        val background = shaderClass.getMethod("getView")
        val attached = contentClass.getDeclaredMethod("onAttachedToWindow")
        val detached = contentClass.getDeclaredMethod("onDetachedFromWindow")
        val visibility = contentClass.getDeclaredMethod("onVisibilityAggregated", Boolean::class.javaPrimitiveType)
        val alpha = contentClass.getDeclaredMethod("setAlpha", Float::class.javaPrimitiveType)
        val stateChange = loader.loadClass(ANIMATION).getDeclaredMethod("onStateChange", contentClass)
        val swipe = loader.loadClass(DELEGATE).getDeclaredMethod("onSwipe", contentClass, stateClass, stateClass,
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
        val resume = loader.loadClass(DELEGATE).getDeclaredMethod("tempHiddenToBigIslandAnimation", contentClass,
            Boolean::class.javaPrimitiveType)
        val glowEvents = glowClass.declaredMethods.filter {
            (it.name.substringBefore('$') == "initGlowEffect" && it.parameterCount == 2) ||
                (it.name.substringBefore('$') == "startGlowEffect" && it.parameterCount == 0)
        }.also { require(it.size == 2) }

        private fun field(type: Class<*>, name: String): Field =
            type.getDeclaredField(name).apply { isAccessible = true }
    }

    // Kept only on the interceptor stack, never in a global map or deferred callback.
    private class Transition(val token: Any, val state: Any, val data: Any?, val enteringApp: Boolean,
        val returning: Boolean, val swipe: Long, val resume: Boolean = false)

    private class Controller(content: View, glow: View, private val access: Access) :
        ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
        private val contentRef = WeakReference(content)
        private var glowRef = WeakReference(glow)
        private var treeRef = WeakReference<ViewTreeObserver>(null)
        private var upperRef = WeakReference<View>(null)
        private var lowerRef = WeakReference<View>(null)
        private var expectedState = WeakReference<Any>(null)
        private var expectedData = WeakReference<Any>(null)
        private var alpha: StableIslandGlowFadeAlpha<View>? = null
        private val fade = StableIslandGlowFade()
        private var eventToken: Any? = null
        private var frameBeforeCommit = false
        private var returnConsumed = false
        private var inAppExpanded = false
        private var pendingSince = 0L
        private var disposed = false
        private var sampledEpisode = false
        private var lastNativeHidden: Boolean? = null
        private var hiddenPermit = false
        private var hiddenData = WeakReference<Any>(null)
        private var visiblePreDraw = false
        private var permitSwipe = 0L

        fun observe() {
            val content = contentRef.get() ?: return
            content.addOnAttachStateChangeListener(this)
            val tree = content.viewTreeObserver
            if (!tree.isAlive) { dispose(); return }
            tree.addOnPreDrawListener(this)
            treeRef = WeakReference(tree)
            // Read the actual native flag at binding; Java visibility is not a hidden baseline.
            observeTempHidden(content, access.data.invoke(content))
        }

        fun replaceGlow(glow: View) {
            if (glowRef.get() === glow) return
            cancel()
            glowRef = WeakReference(glow)
            // The same content keeps its consumed return cycle across native child reconstruction.
        }

        fun prepareTransition(): Transition? {
            if (disposed) return null
            val content = contentRef.get() ?: return null
            val current = access.state.invoke(content) ?: return null
            val last = access.lastState.invoke(content)
            val data = access.data.invoke(content)
            observeTempHidden(content, data)
            val entering = current.javaClass.simpleName == "AppExpanded"
            // The state has already left AppExpanded even if native animation later throws.
            if (!entering) inAppExpanded = false
            val returning = last?.javaClass?.simpleName == "AppExpanded" && current.javaClass.simpleName == "BigIsland"
            // Native Big -> Big refreshes do not restart or shorten an already admitted rise.
            if (!returning && !entering && !fade.complete && lastNativeHidden == false &&
                expectedState.get() === current && expectedData.get() === data) return null
            if (returning && returnConsumed) {
                if (expectedState.get() !== current || expectedData.get() !== data) cancel()
                return null
            }
            val wasVisible = if (returning) visiblePreDraw || visibleGlow() else false
            cancel()
            if (returning) returnConsumed = true
            val token = Any()
            eventToken = token
            frameBeforeCommit = false
            return Transition(token, current, data, entering,
                returning && !wasVisible && data != null && swipeDepth == 0, swipeEpoch)
        }

        private fun nativeTempHidden(content: View, data: Any?): Boolean? {
            if (data == null) return null
            return runCatching {
                val window = access.windowState.invoke(content) ?: return@runCatching null
                access.tempHidden.invoke(window, access.properties.invoke(data)) as? Boolean
            }.getOrNull()
        }

        private fun observeTempHidden(content: View, data: Any?) {
            val hidden = nativeTempHidden(content, data)
            if (hidden == null) {
                lastNativeHidden = null
                hiddenPermit = false
                hiddenData.clear()
                return
            }
            if (hidden && lastNativeHidden == false) {
                hiddenPermit = swipeDepth == 0
                hiddenData = WeakReference(data)
                visiblePreDraw = false
            }
            lastNativeHidden = hidden
        }

        fun prepareResume(noAnim: Boolean): Transition? {
            if (disposed) return null
            val content = contentRef.get() ?: return null
            val current = access.state.invoke(content) ?: return null
            val data = access.data.invoke(content)
            val hidden = nativeTempHidden(content, data)
            val permitted = hiddenPermit && hiddenData.get() === data
            // A native restore consumes this hidden cycle even when no animation is requested.
            hiddenPermit = false
            hiddenData.clear()
            lastNativeHidden = hidden
            if (!noAnim && hidden == false && !fade.complete && swipeDepth == 0 &&
                permitSwipe == swipeEpoch && expectedState.get() === current && expectedData.get() === data) {
                admission("skip-duplicate-resume")
                return null
            }
            cancel()
            val skip = when {
                noAnim -> "skip-no-animation"
                swipeDepth != 0 -> "skip-swipe"
                hidden != false -> "skip-hidden-unknown"
                !permitted -> "skip-no-hidden-cycle"
                visiblePreDraw -> "skip-already-visible"
                current.javaClass.simpleName != "BigIsland" || data == null -> "skip-state"
                else -> null
            }
            if (skip != null) { admission(skip); return null }
            val token = Any()
            eventToken = token
            frameBeforeCommit = false
            return Transition(token, current, data, false, true, swipeEpoch, true)
        }

        fun finishTransition(event: Transition) {
            if (disposed || eventToken !== event.token) return
            eventToken = null
            val content = contentRef.get() ?: return
            if (access.state.invoke(content) !== event.state || access.data.invoke(content) !== event.data ||
                !content.isAttachedToWindow || event.swipe != swipeEpoch || swipeDepth != 0) return cancel()
            if (event.enteringApp && !inAppExpanded) returnConsumed = false
            inAppExpanded = event.enteringApp
            if (!event.returning || frameBeforeCommit) return
            expectedState = WeakReference(event.state)
            expectedData = WeakReference(event.data)
            pendingSince = SystemClock.uptimeMillis()
            permitSwipe = event.swipe
            fade.arm()
            admission(if (event.resume) "resume-armed" else "return-armed")
        }

        fun hidden() {
            // Before the first visible frame no alpha is owned; hiding does not create a new permit.
            if (fade.started) cancel()
        }

        fun cancelForSwipe() {
            hiddenPermit = false
            hiddenData.clear()
            if (!fade.complete || eventToken != null) returnConsumed = true
            cancel()
        }

        fun cancel() {
            alpha?.close()
            alpha = null
            upperRef.clear()
            lowerRef.clear()
            expectedState.clear()
            expectedData.clear()
            fade.cancel()
            eventToken = null
            sampledEpisode = false
        }

        fun dispose() {
            if (disposed) return
            disposed = true
            cancel()
            val content = contentRef.get()
            runCatching { treeRef.get()?.takeIf { it.isAlive }?.removeOnPreDrawListener(this) }
            runCatching { content?.removeOnAttachStateChangeListener(this) }
            if (content != null && controllers[content] === this) controllers.remove(content)
            treeRef.clear(); contentRef.clear(); glowRef.clear()
            hiddenData.clear()
        }

        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) = dispose()
        override fun onPreDraw(): Boolean {
            if (disposed) return true
            try { update() } catch (_: Throwable) { cancel() }
            return true
        }

        private fun visibleGlow(): Boolean {
            val content = contentRef.get() ?: return false
            val glow = glowRef.get() ?: return false
            val container = access.container.invoke(glow) as? View ?: return false
            val upper = access.upper.invoke(glow) as? View ?: return false
            val lower = access.lower.invoke(glow) as? View ?: return false
            return access.enabled.get(glow) == true && access.running.get(container) == true &&
                visible(content) && visible(glow) && visible(upper) && visible(lower) &&
                upper.width > 0 && upper.height > 0 && lower.width > 0 && lower.height > 0
        }

        private fun update() {
            // Only a completed, actually drawn episode is idle. An unarmed controller must
            // still remember native visible frames so a later return cannot dim them.
            // New native events/cancellation clear started before we observe the next cycle;
            // a pre-draw inside an original callback must still veto a late after-hook arm.
            if (fade.started && fade.complete && visiblePreDraw && eventToken == null) return
            val isVisible = visibleGlow()
            visiblePreDraw = isVisible
            if (eventToken != null && isVisible) frameBeforeCommit = true
            if (fade.complete) return
            val content = contentRef.get() ?: return dispose()
            val glow = glowRef.get() ?: return dispose()
            if (!content.isAttachedToWindow) return dispose()
            if (access.big.invoke(content) !== glow || access.state.invoke(content) !== expectedState.get() ||
                expectedData.get() == null || access.data.invoke(content) !== expectedData.get() ||
                permitSwipe != swipeEpoch) return cancel()
            val now = SystemClock.uptimeMillis()
            // Expiring an unused permit never changes opacity: no property is owned while waiting.
            if (fade.pending && now - pendingSince >= MAX_PENDING_MILLIS) return cancel()
            if (!isVisible) { hidden(); return }
            val container = access.container.invoke(glow) as? View ?: return cancel()
            val upper = access.upper.invoke(glow) as? View ?: return cancel()
            val lower = access.lower.invoke(glow) as? View ?: return cancel()
            val shader = access.shader.get(container) ?: return cancel()
            if (upper === lower || access.foreground.invoke(shader) !== upper || access.background.invoke(shader) !== lower) return cancel()
            if (fade.started && (upperRef.get() !== upper || lowerRef.get() !== lower)) return cancel()
            if (alpha == null) {
                alpha = StableIslandGlowFadeAlpha(upper, lower, { it.transitionAlpha }, { view, value -> view.transitionAlpha = value })
                upperRef = WeakReference(upper)
                lowerRef = WeakReference(lower)
            }
            val first = fade.pending
            val factor = fade.sample(now)
            if (alpha?.apply(factor) != true) return cancel()
            if (first && sampledEpisodes < 3) {
                sampledEpisodes++
                sampledEpisode = true
                Log.i("HyperIslandStableGlowFade", "begin: alpha=0.5 duration=900ms native return/resume")
            }
            if (fade.complete) {
                alpha?.close(); alpha = null
                upperRef.clear(); lowerRef.clear()
                if (sampledEpisode) Log.i("HyperIslandStableGlowFade", "complete: alpha=1 native colors retained")
                sampledEpisode = false
            } else {
                content.postInvalidateOnAnimation()
            }
        }
    }

    /** Ignore our independent multiplier; native visibility and alpha remain authoritative. */
    private fun visible(view: View): Boolean {
        if (!view.isAttachedToWindow || view.windowVisibility != View.VISIBLE || !view.isShown) return false
        var current: View? = view
        var depth = 0
        while (current != null && depth++ < 32) {
            if (current.visibility != View.VISIBLE || !current.alpha.isFinite() || current.alpha <= 0f) return false
            current = current.parent as? View
        }
        return current == null
    }
}
