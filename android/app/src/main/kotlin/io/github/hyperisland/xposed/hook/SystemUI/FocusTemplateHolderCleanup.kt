package io.github.hyperisland.xposed.hook

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Completes the lifecycle of V3 holders whose exact builders have been retired.
 *
 * The caller must exclude factory writers, capture all four builder values, remove their exact
 * cache entries, and call retire in one main-thread transaction. No plugin method or View cleanup
 * runs in that transaction. An attached holder keeps its complete native lifecycle until it leaves
 * the Window. Tokens/listeners only keep weak references to adapters, holders, and Views.
 */
internal class FocusTemplateHolderCleanup(
    private val module: XposedModule,
    private val mainHandler: Handler,
    private val reportFailure: (String, Throwable) -> Unit,
) {
    private companion object {
        const val MODULE = "miui.systemui.notification.focus.moduleV3."
        const val TEMPLATE = "miui.systemui.notification.focus.templateV3."
        const val MAX_HOLDERS = 8192
        const val MAX_ADAPTERS = 4096
        // All declarations on the verified plugin. Observing only the base would report success
        // before NewImageText/Text subclasses finish their own work after super.onDetach().
        val detachClassNames = listOf(
            "ModuleViewHolder", "ModuleAnimationTextViewHolder", "ModuleButtonViewHolder",
            "ModuleDecoLandAnimationTextViewHolder", "ModuleDecoLandButtonViewHolder",
            "ModuleDecoLandNewImageTextViewHolder", "ModuleDecoLandProgressViewHolder",
            "ModuleDecoPortAnimationTextViewHolder", "ModuleDecoPortButtonViewHolder",
            "ModuleDecoPortNewImageTextViewHolder", "ModuleImageTextHighlightViewHolder",
            "ModuleNewImageTextViewHolder", "ModuleProgressViewHolder", "ModuleTextButtonViewHolder",
            "ModuleTextViewHolder", "ModuleTinyAnimationTextViewHolder", "ModuleTinyButtonViewHolder",
            "ModuleTinyNewImageTextViewHolder", "ModuleTinyProgressViewHolder", "ModuleTinyTextViewHolder",
        )
        val holderMapNames = listOf("holders", "tinyHolders", "decoHolders", "decoLandHolders")
        val builderAdapterNames = mapOf(
            "TemplateBuilderV3" to listOf(
                "islandAdapter", "islandFakeAdapter", "focusAdapter", "focusDarkAdapter",
                "focusModalAdapter", "focusModalDarkAdapter",
            ),
            "TemplateTinyBuilderV3" to listOf(
                "tinyAdapter", "tinyDarkAdapter", "tinyKeyguardAdapter", "tinyKeyguardDarkAdapter",
                "tinyModalAdapter", "tinyModalDarkAdapter",
            ),
            "TemplateDecoBuilderV3" to listOf("decoAdapter", "decoDarkAdapter"),
            "TemplateDecoLandBuilderV3" to listOf("decoLandAdapter", "decoLandDarkAdapter"),
        )
    }

    private class Access(
        val holderType: Class<*>,
        val adapterType: Class<*>,
        val builderFields: Map<Class<*>, List<Field>>,
        val holderMaps: List<Field>,
        val dataMap: Field,
        val view: Field,
        val rootView: Field,
        val onDetach: Method,
        val detachMethods: Set<Method>,
        val resultFailure: Class<*>,
    ) {
        @Volatile var ready = false
    }

    private class Slot(adapter: Any, val field: Field, val moduleType: String) {
        val adapter = WeakReference(adapter)
    }

    private class AdapterTarget(adapter: Any, val access: Access) {
        val adapter = WeakReference(adapter)
    }

    private class Target(holder: Any, val access: Access) {
        val holder = WeakReference(holder)
        val slots = mutableListOf<Slot>()
        val state = FocusTemplateHolderState()
        val slotRetry = FocusTemplateCleanupRetry()
        val watchedViews = mutableListOf<WeakReference<View>>()
        var listener: View.OnAttachStateChangeListener? = null
        var check: Runnable? = null
        var retired = false
    }

    internal interface Retirement

    private class Plan(
        val adapters: List<AdapterTarget>,
        val targets: List<Target>,
    ) : Retirement {
        var committed = false
        var abandoned = false
    }

    private val stateLock = Any()
    private val attempted = mutableSetOf<Class<*>>()
    private val accesses = mutableMapOf<Class<*>, Access>()
    private val retiredAdapters = WeakIdentityMap<Boolean>()
    private val managedHolders = WeakIdentityMap<Target>()
    private val cleanupBatch = FocusTemplateCleanupBatch(
        post = { action, continuation ->
            if (continuation) mainHandler.postDelayed(action, 16L) else mainHandler.post(action)
        },
        now = SystemClock::uptimeMillis,
        failed = { reportFailure("holder cleanup continuation", IllegalStateException()) },
    )

    init {
        check(mainHandler.looper == Looper.getMainLooper())
    }

    /**
     * Install only the two non-suspending adapter update leaves and the native completion observer.
     * Any incomplete runtime remains inactive; removeView jobs and all native onDetach calls proceed.
     */
    fun prepare(loader: ClassLoader): Boolean {
        return runCatching {
            val holderType = Class.forName("${MODULE}ModuleViewHolder", false, loader)
            synchronized(stateLock) {
                accesses[holderType]?.let { return it.ready }
                if (!attempted.add(holderType)) return false
            }
            val adapterType = Class.forName("${MODULE}ModuleViewHolderAdapter", false, loader)
            val builderFields = builderAdapterNames.map { (name, fields) ->
                val type = Class.forName("$TEMPLATE$name", false, loader)
                type to fields.map { field(type, it).also { f -> check(f.type == adapterType) } }
            }.toMap()
            val detachMethods = detachClassNames.map { name ->
                val type = Class.forName("$MODULE$name", false, loader)
                check(holderType.isAssignableFrom(type))
                type.getDeclaredMethod("onDetach").apply {
                    check(returnType == Void.TYPE)
                    isAccessible = true
                }
            }.toSet()
            val access = Access(
                holderType, adapterType, builderFields,
                holderMapNames.map { mapField(adapterType, it) },
                mapField(adapterType, "dataMap"),
                field(holderType, "view").also { check(View::class.java.isAssignableFrom(it.type)) },
                field(holderType, "rootView").also { check(View::class.java.isAssignableFrom(it.type)) },
                holderType.getDeclaredMethod("onDetach").apply {
                    check(returnType == Void.TYPE)
                    isAccessible = true
                },
                detachMethods,
                Class.forName("G0.j\$b", false, loader).also {
                    check(field(it, "a").type == Throwable::class.java)
                },
            )
            // These exact classes are label-zero leaves on the supported plugin, not Mutex workers.
            val updates = listOf("bindData", "updateView").map { name ->
                val type = Class.forName("${MODULE}ModuleViewHolderAdapter\$$name\$1", false, loader)
                val adapter = field(type, "this\$0").also { check(it.type == adapterType) }
                val label = field(type, "label").also { check(it.type == Int::class.javaPrimitiveType) }
                val invoke = type.getDeclaredMethod("invokeSuspend", Any::class.java).apply {
                    check(returnType == Any::class.java)
                    isAccessible = true
                }
                Triple(invoke, adapter, label)
            }
            updates.forEach { (invoke, adapter, label) ->
                module.hook(invoke).intercept { chain ->
                    val skip = runCatching {
                        access.ready && !access.resultFailure.isInstance(chain.args[0]) &&
                            label.getInt(chain.thisObject) == 0 &&
                            synchronized(stateLock) { retiredAdapters[adapter.get(chain.thisObject)] == true }
                    }.getOrElse {
                        reportFailure("retired holder update check", it)
                        false
                    }
                    // invoke(...).create(...).invokeSuspend receives the plugin's own Kotlin Unit.
                    if (skip) chain.args[0] else chain.proceed()
                }
            }
            access.detachMethods.forEach { detach ->
                module.hook(detach).intercept { chain ->
                    val target = if (access.ready) synchronized(stateLock) {
                        managedHolders[chain.thisObject]?.takeIf { it.retired }
                            ?.also { it.state.enterNativeDetach() }
                    } else null
                    var success = false
                    try {
                        chain.proceed().also { success = true }
                    } finally {
                        if (target != null) runCatching {
                            val completed = synchronized(stateLock) {
                                target.state.exitNativeDetach(success)
                            }
                            if (completed) postNativeCompletion(target)
                        }.onFailure { reportFailure("native holder completion", it) }
                    }
                }
            }
            synchronized(stateLock) {
                access.ready = true
                accesses[holderType] = access
                builderFields.keys.forEach { accesses[it] = access }
            }
            true
        }.getOrElse {
            reportFailure("holder cleanup runtime", it)
            false
        }
    }

    /** Reflection-only snapshot. Null means the caller must retain every original cache entry. */
    fun capture(builders: Collection<Any>): Retirement? {
        checkMainThread()
        return runCatching {
            val adapters = IdentityHashMap<Any, AdapterTarget>()
            val targets = IdentityHashMap<Any, Target>()
            builders.forEach { builder ->
                val access = synchronized(stateLock) { accesses[builder.javaClass] }
                    ?: error("Unknown V3 builder runtime")
                check(access.ready)
                access.builderFields.getValue(builder.javaClass).forEach adapterLoop@ { adapterField ->
                    val adapter = adapterField.get(builder) ?: return@adapterLoop
                    check(access.adapterType.isInstance(adapter))
                    if (adapters.containsKey(adapter)) return@adapterLoop
                    check(synchronized(stateLock) { retiredAdapters[adapter] != true })
                    adapters[adapter] = AdapterTarget(adapter, access)
                    FocusTemplateHolderMaps.visit(
                        adapter, access.holderMaps, access.holderType, access.detachMethods,
                    ) { mapField, moduleType, holder ->
                        val target = targets.getOrPut(holder) {
                            // Do not overwrite another retirement's listeners/slot ownership.
                            check(synchronized(stateLock) { managedHolders[holder] == null })
                            Target(holder, access)
                        }
                        target.slots += Slot(adapter, mapField, moduleType)
                    }
                }
            }
            synchronized(stateLock) {
                check(managedHolders.size + targets.size <= MAX_HOLDERS)
                check(retiredAdapters.size + adapters.size <= MAX_ADAPTERS)
            }
            Plan(adapters.values.toList(), targets.values.toList())
        }.getOrElse {
            reportFailure("holder cleanup snapshot", it)
            null
        }
    }

    /**
     * Freeze old adapter updates and enqueue cleanup. This never invokes onDetach synchronously.
     * If posting fails, false means the caller must restore the exact cache entries it just removed.
     */
    fun retire(retirement: Retirement): Boolean {
        checkMainThread()
        val plan = retirement as? Plan ?: return false
        if (plan.abandoned) return false
        if (plan.committed) return true
        synchronized(stateLock) {
            plan.adapters.forEach { it.adapter.get()?.let { adapter -> retiredAdapters[adapter] = true } }
            plan.targets.forEach {
                it.retired = true
                it.holder.get()?.let { holder -> managedHolders[holder] = it }
            }
            plan.committed = true
        }
        val tasks = plan.targets.map { target ->
                Runnable {
                    if (synchronized(stateLock) { target.state.requestCheck() }) runCheck(target)
                }
            } + plan.adapters.map { adapter ->
                Runnable {
                    runCatching {
                        adapter.adapter.get()?.let { releaseEmptyAdapter(it, adapter.access) }
                    }.onFailure { reportFailure("empty retired adapter", it) }
                }
            }
        if (cleanupBatch.submitAll(tasks)) return true
        synchronized(stateLock) {
            plan.adapters.forEach { it.adapter.get()?.let(retiredAdapters::remove) }
            plan.targets.forEach {
                it.retired = false
                it.state.finish()
                it.holder.get()?.let(managedHolders::remove)
            }
            plan.committed = false
            plan.abandoned = true
        }
        reportFailure("holder cleanup dispatch", IllegalStateException("Main handler rejected work"))
        return false
    }

    /** Valid before commit only; committed plans own the old objects independently of notification keys. */
    fun abandon(retirement: Retirement) {
        checkMainThread()
        val plan = retirement as? Plan ?: return
        if (!plan.committed) plan.abandoned = true
    }

    private fun requestCheck(target: Target) {
        checkMainThread()
        if (!synchronized(stateLock) { target.state.requestCheck() }) return
        val action = Runnable { runCheck(target) }
        target.check = action
        if (!cleanupBatch.submit(action)) {
            // No onDetach ran. Keep the slots and allow a later native call to finish.
            synchronized(stateLock) { target.state.failDetach() }
            removeListeners(target)
            reportFailure("holder detach dispatch", IllegalStateException("Main handler rejected work"))
        }
    }

    private fun runCheck(target: Target) {
        checkMainThread()
        target.check = null
        if (!synchronized(stateLock) { target.state.takeCheck() }) {
            if (synchronized(stateLock) { target.state.isFinished() }) finishTarget(target)
            else removeListeners(target)
            return
        }
        val holder = target.holder.get()
        if (holder == null) {
            synchronized(stateLock) { target.state.finish() }
            removeListeners(target)
            return
        }
        runCatching {
            val view = target.access.view.get(holder) as View?
            val root = target.access.rootView.get(holder) as View
            val canDetach = synchronized(stateLock) {
                target.state.beginDetach(view?.isAttachedToWindow == true, root.isAttachedToWindow)
            }
            if (!canDetach) {
                watch(target, listOfNotNull(view, root).distinctByIdentity())
                return
            }
            // Runs after the caller's metadata transaction, on main, and only for detached objects.
            // Normal reflection deliberately preserves each subclass's native cleanup.
            target.access.onDetach.invoke(holder)
            check(synchronized(stateLock) { target.state.isFinished() }) {
                "Complete holder lifecycle was not observed"
            }
            finishTarget(target)
        }.onFailure {
            // Leave old adapter slots intact so a pending native removeView can still finish cleanup.
            synchronized(stateLock) { target.state.failDetach() }
            removeListeners(target)
            reportFailure("holder lifecycle cleanup", it)
        }
    }

    private fun watch(target: Target, views: List<View>) {
        removeListeners(target)
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = requestCheck(target)
            override fun onViewDetachedFromWindow(view: View) = requestCheck(target)
        }
        target.listener = listener
        views.forEach {
            it.addOnAttachStateChangeListener(listener)
            target.watchedViews += WeakReference(it)
        }
    }

    private fun postNativeCompletion(target: Target) {
        // Only the outermost full native call schedules this. Keep UI work outside its short lock.
        if (!cleanupBatch.submit(Runnable { finishTarget(target) })) {
            reportFailure("native holder completion dispatch", IllegalStateException("Main handler rejected work"))
        }
    }

    private fun finishTarget(target: Target) {
        checkMainThread()
        if (!synchronized(stateLock) { target.state.isFinished() }) return
        if (!target.slotRetry.takeAttempt(SystemClock.uptimeMillis())) return
        removeListeners(target)
        val holder = target.holder.get() ?: return
        runCatching {
            // Preserve all native onDetach calls. Removing only these exact retired slots prevents
            // a later adapter.removeView job from calling onDetach a second time for our cleanup.
            target.slots.forEach { slot ->
                val adapter = slot.adapter.get() ?: return@forEach
                FocusTemplateHolderMaps.removeCompletedSlot(
                    adapter, target.access.holderMaps, target.access.dataMap,
                    slot.field, slot.moduleType, holder,
                )
                releaseEmptyAdapter(adapter, target.access)
            }
            synchronized(stateLock) { managedHolders.remove(holder) }
            target.slots.clear()
        }.onFailure {
            reportFailure("retired holder slots", it)
            // Retry only idempotent map operations, never a partially failed native onDetach.
            if (target.slotRetry.failed(SystemClock.uptimeMillis())) {
                val action = Runnable {
                    target.check = null
                    val cleanup = Runnable { target.check = null; finishTarget(target) }
                    target.check = cleanup
                    if (!cleanupBatch.submit(cleanup)) {
                        target.check = null
                        reportFailure("retired holder slots dispatch", IllegalStateException())
                    }
                }
                target.check = action
                val delay = (checkNotNull(target.slotRetry.nextAt) - SystemClock.uptimeMillis())
                    .coerceAtLeast(0L)
                if (!mainHandler.postDelayed(action, delay)) {
                    target.check = null
                    reportFailure("retired holder slots dispatch", IllegalStateException())
                }
            } else {
                reportFailure("retired holder slots retry exhausted", IllegalStateException())
            }
        }
    }

    private fun releaseEmptyAdapter(adapter: Any, access: Access) {
        if (!FocusTemplateHolderMaps.hasLiveHolders(adapter, access.holderMaps)) {
            // Late bind/update leaves can no longer find a holder. Their short-lived dataMap work
            // needs no tombstone after all exact holder slots have been released.
            synchronized(stateLock) { retiredAdapters.remove(adapter) }
        }
    }

    private fun removeListeners(target: Target) {
        target.check?.let { mainHandler.removeCallbacks(it); cleanupBatch.remove(it) }
        target.check = null
        val listener = target.listener
        if (listener != null) {
            target.watchedViews.forEach { it.get()?.removeOnAttachStateChangeListener(listener) }
        }
        target.watchedViews.clear()
        target.listener = null
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == mainHandler.looper) { "Holder cleanup requires the main looper" }
    }

    private fun field(type: Class<*>, name: String) =
        type.getDeclaredField(name).apply { isAccessible = true }

    private fun mapField(type: Class<*>, name: String) =
        field(type, name).also { check(Map::class.java.isAssignableFrom(it.type)) }

    private fun <T : Any> identitySet(): MutableSet<T> =
        Collections.newSetFromMap(IdentityHashMap<T, Boolean>())

    private fun <T : Any> List<T>.distinctByIdentity(): List<T> {
        val seen = identitySet<T>()
        return filter { seen.add(it) }
    }
}
