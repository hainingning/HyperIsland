package io.github.hyperisland.xposed.hook

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.github.hyperisland.xposed.hook.FocusTemplateLifetime.Presence
import io.github.hyperisland.xposed.hook.FocusTemplateLifetime.Ticket
import io.github.hyperisland.xposed.hook.FocusTemplateVerificationGate.Token
import io.github.hyperisland.xposed.utils.HookUtils
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors

/**
 * Retires V3 builders only after NotifCollection and NMS both confirm final removal.
 * Native factory work runs outside the metadata lock. Exact old holder identities finish
 * their native lifecycle after leaving the Window; hiding/reparenting a View is not removal.
 */
object FocusTemplateCacheHook : BaseHook() {
    private const val FACTORY = "miui.systemui.notification.focus.templateV3.TemplateFactoryV3"
    private const val CONTROLLER = "miui.systemui.notification.focus.FocusNotificationController"
    private const val COLLECTION = "com.android.systemui.statusbar.notification.collection.NotifCollection"
    private const val CONTENT = "com.android.systemui.plugins.miui.notification.FocusNotificationContent"
    private const val VERIFIED_PLUGIN = "17.1.3.76.0"
    private const val QUERY_DEADLINE_MS = 1000L
    private const val MAX_PENDING_CLEANUP = 1024
    private const val MAX_WORK_ALIASES = 32768
    private val cacheNames = listOf(
        "builderMap", "builderTinyMap", "builderDecoMap", "builderDecoLandMap", "templateMap",
    )
    private val creationNames = setOf(
        "createStandardTemplateView", "createTinyTemplateView",
        "createDecoTemplateView", "createDecoLandTemplateView",
    )
    private val lifetime = FocusTemplateLifetime()
    private val coordinator = FocusTemplateWorkCoordinator(lifetime)
    private val gate = FocusTemplateVerificationGate<FocusTemplateCompletionPause.Pause>(lifetime)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceExecutor = Executors.newSingleThreadExecutor { work ->
        Thread(work, "HyperIsland-V3-verification").apply { isDaemon = true }
    }
    private lateinit var cleanupDispatch: FocusTemplateCleanupDispatch
    // Only the main looper accesses these queues.
    private val pendingCleanup = FocusTemplateCleanupQueue(MAX_PENDING_CLEANUP)
    private var cleanupRetryTask: Runnable? = null
    private var cleanupRetryAt: Long? = null
    private val observedFailures = mutableSetOf<String>()
    private val requests = LinkedHashSet<Token>()
    private val deadlines = mutableMapOf<Token, Runnable>()
    private var queryInFlight = false
    // lifetime.lock protects these weak maps/sets.
    private val factories = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    private val accesses = mutableMapOf<Class<*>, FactoryAccess>()
    private val attempted = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val templateTickets = WeakIdentityMap<Ticket>()
    private val asyncTickets = WeakIdentityMap<Ticket>()
    private val currentWork = ThreadLocal<Ticket?>()
    @Volatile private var collection = WeakReference<Any>(null)
    @Volatile private var notificationListener = WeakReference<NotificationListenerService>(null)
    @Volatile private var getEntry: Method? = null
    @Volatile private var collectionReady = false
    @Volatile private var disabled = false
    private lateinit var holderCleanup: FocusTemplateHolderCleanup

    private class FactoryAccess(
        val fields: List<Field>,
        val bridge: FocusTemplateCompletionPause,
    ) {
        @Volatile var ready = false
    }

    override fun getTag() = "HyperIsland[FocusTemplateCache]"

    override fun onInit(module: XposedModule, param: PackageLoadedParam) {
        cleanupDispatch = FocusTemplateCleanupDispatch(
            hasWork = { pendingCleanup.hasEntries },
            post = { action, continuation ->
                if (continuation) mainHandler.postDelayed(action, 16L) else mainHandler.post(action)
            },
            slice = { requested -> drainCleanup(module, requested) },
            failed = { observeFailure(module, "cache cleanup dispatch", IllegalStateException()) },
        )
        holderCleanup = FocusTemplateHolderCleanup(module, mainHandler) { where, failure ->
            observeFailure(module, where, failure)
        }
        installCollection(module, param.defaultClassLoader)
        installRemovalListener(module, param.defaultClassLoader)
        installPlugin(module, param.defaultClassLoader)
        HookUtils.hookDynamicClassLoaders(module, ClassLoader.getSystemClassLoader()) {
            installPlugin(module, it)
        }
    }

    private fun stamp(sbn: StatusBarNotification) = FocusTemplateLifetime.Stamp(
        sbn.key, sbn.postTime, sbn.uid, sbn.id, sbn.tag,
    )

    private fun ticket(sbn: StatusBarNotification?) = sbn?.let {
        if (disabled || lifetime.overflowed) lifetime.lookupKnown(it, stamp(it))
        else lifetime.lookup(it, stamp(it))
    }

    private fun knownStale(lease: Ticket?) =
        collectionReady && lease != null && lifetime.isStale(lease)

    private fun field(type: Class<*>, name: String): Field {
        var cursor: Class<*>? = type
        while (cursor != null) {
            val candidate = cursor
            runCatching { candidate.getDeclaredField(name) }.getOrNull()?.let {
                it.isAccessible = true
                return it
            }
            cursor = candidate.superclass
        }
        error("Missing ${type.name}.$name")
    }

    private fun observeFailure(module: XposedModule, where: String, error: Throwable) {
        // Never log notification contents or reflective messages that could contain them.
        val first = synchronized(observedFailures) {
            observedFailures.size < 64 && observedFailures.add(where)
        }
        // Cleanup failure is operational evidence, including when user debug logging is off.
        if (first) logWarn(module, "$where unavailable (${error.javaClass.simpleName})")
    }

    private fun resumeLater(module: XposedModule, waiters: List<FocusTemplateCompletionPause.Pause>) {
        if (waiters.isEmpty()) return
        // Always enqueue: the parked invokeSuspend must first return its suspended marker.
        if (!mainHandler.post {
                waiters.forEach { waiter ->
                    runCatching { waiter.resume() }
                        .onFailure { observeFailure(module, "completion resume", it) }
                }
            }) observeFailure(module, "completion dispatch", IllegalStateException())
    }

    private fun disableProtection(module: XposedModule) {
        val changed = synchronized(lifetime.lock) {
            if (disabled) false else { disabled = true; true }
        }
        if (!changed) return
        resumeLater(module, gate.cancelAll())
        mainHandler.post {
            deadlines.keys.toList().forEach(::clearRequest)
            pendingCleanup.clear()
            armCleanupRetry(module)
        }
        logFailureOnce(module, "capacity") { "V3 tracking unavailable; native behavior retained" }
    }

    private fun trackingAvailable(module: XposedModule): Boolean {
        if (lifetime.overflowed) disableProtection(module)
        return collectionReady && !disabled
    }

    private fun onPost(module: XposedModule, lease: Ticket?) {
        if (lease == null) disableProtection(module)
        else if (trackingAvailable(module)) {
            resumeLater(module, gate.onPost(lease))
            requestCleanup(module) // Discard superseded retries, including exhausted entries.
        }
    }

    /** Caller holds lifetime.lock. Never evict an old alias and later bind it to a newer ticket. */
    private fun remember(
        module: XposedModule, map: WeakIdentityMap<Ticket>, source: Any?, lease: Ticket?,
    ) {
        if (!trackingAvailable(module) || source == null || lease == null) return
        if (map[source] == null && map.size >= MAX_WORK_ALIASES) disableProtection(module)
        else map[source] = lease
    }

    private fun enterFactory(
        module: XposedModule, factory: Any, lease: Ticket?, nativeRemoval: Boolean = false,
    ): FocusTemplateWorkCoordinator.Writer? = synchronized(lifetime.lock) {
        if (disabled) return@synchronized null
        if (factories.size >= 64 && factory !in factories) {
            disableProtection(module)
            return@synchronized null
        }
        val writer = if (nativeRemoval) coordinator.enterMutation(factory)
        else coordinator.enterWriter(factory, lease)
        // Admission and owner registration are atomic, including a first native remove/clean.
        // A rejected stale writer must not leave an owner with no coordinator scope.
        writer?.also { factories.add(factory) }
    }

    private fun installCollection(module: XposedModule, loader: ClassLoader) {
        runCatching {
            val type = Class.forName(COLLECTION, false, loader)
            val lookup = type.getDeclaredMethod("getEntry", String::class.java).apply {
                isAccessible = true
            }
            val posted = type.declaredMethods.single {
                it.name == "postNotification" && it.parameterCount == 2 &&
                    it.parameterTypes[0] == StatusBarNotification::class.java
            }
            val removed = type.declaredMethods.single {
                it.name == "tryRemoveNotification" && it.parameterCount == 1 &&
                    it.returnType == Boolean::class.javaPrimitiveType
            }
            val entrySbn = field(removed.parameterTypes[0], "mSbn")
            type.declaredConstructors.forEach { constructor ->
                module.hook(constructor).intercept { chain ->
                    val result = chain.proceed()
                    chain.thisObject?.let { collection = WeakReference(it) }
                    result
                }
            }
            module.hook(posted).intercept { chain ->
                val sbn = chain.args[0] as? StatusBarNotification
                chain.thisObject?.let { collection = WeakReference(it) }
                val lease = synchronized(lifetime.lock) {
                    if (trackingAvailable(module) && sbn != null &&
                        (lifetime.hasCurrent(sbn.key) || ticket(sbn) != null)
                    ) lifetime.observeCollection(sbn, stamp(sbn)) else null
                }
                trackingAvailable(module)
                val result = chain.proceed()
                if (lease != null && sbn != null && !lifetime.isStale(lease)) {
                    runCatching {
                        val entry = lookup.invoke(chain.thisObject, sbn.key)
                        val wrapped = entry?.let(entrySbn::get) as? StatusBarNotification
                        synchronized(lifetime.lock) {
                            if (wrapped != null && stamp(wrapped) == lease.stamp &&
                                !lifetime.isStale(lease)
                            ) lifetime.associate(wrapped, lease)
                        }
                    }.onFailure { observeFailure(module, "entry association", it) }
                    trackingAvailable(module)
                }
                result
            }
            module.hook(removed).intercept { chain ->
                chain.thisObject?.let { collection = WeakReference(it) }
                // Capture before callbacks release/reuse this entry. Native work is outside lock.
                val lease = runCatching {
                    ticket(entrySbn.get(chain.args[0]) as? StatusBarNotification)
                }.getOrNull()
                val result = chain.proceed()
                if (result == true && lease != null) submit(module, beginRemoval(module, lease, null))
                result
            }
            getEntry = lookup
            collectionReady = true
        }.onFailure { observeFailure(module, "notification lifetime hooks", it) }
    }

    private fun installPlugin(module: XposedModule, loader: ClassLoader) {
        val factory = runCatching { Class.forName(FACTORY, false, loader) }.getOrNull() ?: return
        // Matching names alone cannot establish coroutine/lifecycle compatibility on another ROM.
        val version = runCatching {
            @Suppress("DEPRECATION")
            HookUtils.getContext(loader)?.packageManager
                ?.getPackageInfo("miui.systemui.plugin", 0)?.versionName
        }.getOrNull() ?: return
        if (version != VERIFIED_PLUGIN) {
            logFailureOnce(module, "plugin version") { "V3 cleanup disabled for unverified plugin version" }
            return
        }
        synchronized(lifetime.lock) {
            if (!attempted.add(factory) || accesses.size >= 16) return
        }
        runCatching {
            val runtimeLoader = factory.classLoader ?: loader
            val bridge = checkNotNull(FocusTemplateCompletionPause.prepare(runtimeLoader, version))
            check(holderCleanup.prepare(runtimeLoader))
            val fields = cacheNames.map { field(factory, it) }
            check(fields.all { Map::class.java.isAssignableFrom(it.type) })
            val controller = Class.forName(CONTROLLER, false, runtimeLoader)
            val posted = controller.declaredMethods.single {
                it.name == "onNotificationPosted" && it.parameterCount >= 1 &&
                    it.parameterTypes[0] == StatusBarNotification::class.java
            }
            val create = factory.declaredMethods.single {
                it.name == "createTemplate" && it.parameterCount == 7 &&
                    it.parameterTypes[3] == StatusBarNotification::class.java &&
                    it.returnType == Void.TYPE
            }
            val leaves = creationNames.map { name ->
                factory.declaredMethods.single {
                    it.name == name && it.parameterCount in 4..5 &&
                        it.parameterTypes[1] == StatusBarNotification::class.java &&
                        it.parameterTypes[3].name == CONTENT && it.returnType == Void.TYPE
                }
            }
            val reset = factory.declaredMethods.single {
                it.name == "resetErrorNotification" && it.parameterCount == 4 &&
                    it.parameterTypes[0].name == CONTENT &&
                    it.parameterTypes[1] == StatusBarNotification::class.java &&
                    it.returnType == Any::class.java
            }.apply { isAccessible = true }
            val access = FactoryAccess(fields, bridge)
            synchronized(lifetime.lock) { accesses[factory] = access }
            module.hook(posted).intercept { chain ->
                val sbn = chain.args[0] as? StatusBarNotification
                val tracked = sbn != null && synchronized(lifetime.lock) {
                    lifetime.hasCurrent(sbn.key) || ticket(sbn) != null
                }
                val lease = if (tracked && trackingAvailable(module) && access.ready) {
                    lifetime.begin(sbn, stamp(sbn)).also { onPost(module, it) }
                } else ticket(sbn)
                withWork(lease) { chain.proceed() }
            }
            module.hook(create).intercept { chain ->
                val instance = chain.thisObject ?: return@intercept chain.proceed()
                if (!access.ready) return@intercept chain.proceed()
                val sbn = chain.args[3] as? StatusBarNotification
                val known = ticket(sbn)
                if (knownStale(known)) return@intercept null
                if (!trackingAvailable(module)) return@intercept withWork(known) { chain.proceed() }
                val lease = synchronized(lifetime.lock) {
                    if (sbn == null) null
                    else known ?: lifetime.begin(sbn, stamp(sbn))
                }
                if (sbn != null) onPost(module, lease)
                if (!trackingAvailable(module)) return@intercept withWork(lease) { chain.proceed() }
                val writer = enterFactory(module, instance, lease)
                if (writer == null) {
                    return@intercept if (disabled && !knownStale(lease)) {
                        withWork(lease) { chain.proceed() }
                    } else null
                }
                try {
                    withWork(lease) { chain.proceed() }
                } finally {
                    if (coordinator.exit(writer)) requestCleanup(module)
                }
            }
            leaves.forEach { method ->
                method.isAccessible = true
                module.hook(method).intercept { chain ->
                    val instance = chain.thisObject ?: return@intercept chain.proceed()
                    if (!access.ready) return@intercept chain.proceed()
                    val lease = synchronized(lifetime.lock) {
                        templateTickets[chain.args[0]] ?: currentWork.get()
                            ?: ticket(chain.args[1] as? StatusBarNotification)
                    }
                    if (knownStale(lease)) return@intercept null
                    if (!trackingAvailable(module)) return@intercept withWork(lease) { chain.proceed() }
                    val writer = enterFactory(module, instance, lease)
                    if (writer == null) {
                        return@intercept if (disabled && !knownStale(lease)) {
                            withWork(lease) { chain.proceed() }
                        } else null
                    }
                    try {
                        withWork(lease) { chain.proceed() }
                    } finally {
                        if (coordinator.exit(writer)) requestCleanup(module)
                    }
                }
            }
            module.hook(reset).intercept { chain ->
                val instance = chain.thisObject ?: return@intercept chain.proceed()
                if (!access.ready) return@intercept chain.proceed()
                val lease = currentWork.get() ?: ticket(chain.args[1] as? StatusBarNotification)
                if (knownStale(lease)) return@intercept null
                if (!trackingAvailable(module)) return@intercept withWork(lease) { chain.proceed() }
                val writer = enterFactory(module, instance, lease)
                if (writer == null) {
                    return@intercept if (disabled && !knownStale(lease)) {
                        withWork(lease) { chain.proceed() }
                    } else null
                }
                // This verified reset is synchronous despite its suspend-shaped signature.
                try {
                    withWork(lease) { chain.proceed() }
                } finally {
                    if (coordinator.exit(writer)) requestCleanup(module)
                }
            }
            factory.declaredMethods.filter { it.name == "removeTemplate" || it.name == "clean" }
                .forEach { method ->
                    module.hook(method).intercept { chain ->
                        val instance = chain.thisObject ?: return@intercept chain.proceed()
                        if (!access.ready || !trackingAvailable(module)) {
                            return@intercept chain.proceed()
                        }
                        val writer = enterFactory(module, instance, null, nativeRemoval = true)
                            ?: return@intercept chain.proceed()
                        try {
                            chain.proceed()
                        } finally {
                            if (coordinator.exit(writer)) requestCleanup(module)
                        }
                    }
                }
            installWorkTracking(module, runtimeLoader, access)
            access.ready = true
            log(module) { "V3 template lifetime protection installed for verified plugin" }
        }.onFailure { observeFailure(module, "V3 compatibility", it) }
    }

    private inline fun <T> withWork(lease: Ticket?, action: () -> T): T {
        val previous = currentWork.get()
        currentWork.set(lease)
        return try {
            action()
        } finally {
            if (previous == null) currentWork.remove() else currentWork.set(previous)
        }
    }

    private fun installWorkTracking(module: XposedModule, loader: ClassLoader, access: FactoryAccess) {
        // Mutex-owning workers ALWAYS run, including stale/canceled resumes: native finally unlocks.
        listOf("$FACTORY\$createTemplate\$1", "$FACTORY\$createTemplate\$1\$1").forEach { name ->
            val type = Class.forName(name, false, loader)
            val template = field(type, "\$template")
            val source = field(type, "\$sbn")
            type.declaredConstructors.forEach { constructor ->
                module.hook(constructor).intercept { chain ->
                    val result = chain.proceed()
                    synchronized(lifetime.lock) {
                        chain.thisObject?.let { work ->
                            val value = template.get(work)
                            val lease = templateTickets[value] ?: currentWork.get()
                                ?: ticket(source.get(work) as? StatusBarNotification)
                            remember(module, templateTickets, value, lease)
                        }
                    }
                    result
                }
            }
            module.hook(type.getDeclaredMethod("invokeSuspend", Any::class.java)).intercept { chain ->
                val lease = synchronized(lifetime.lock) {
                    chain.thisObject?.let { templateTickets[template.get(it)] }
                }
                withWork(lease) { chain.proceed() }
            }
        }
        listOf(
            "$FACTORY\$createTemplate\$1\$1\$1\$1",
            "$CONTROLLER\$inflateCallBack\$1\$onInflateSuccess\$1",
            "$CONTROLLER\$inflateCallBack\$1\$onInflateFailed\$1",
        ).forEach { installCompletionTracking(module, loader, it, access) }
    }

    private fun installCompletionTracking(
        module: XposedModule, loader: ClassLoader, name: String, access: FactoryAccess,
    ) {
        val type = Class.forName(name, false, loader)
        val label = field(type, "label")
        type.declaredConstructors.forEach { constructor ->
            module.hook(constructor).intercept { chain ->
                val result = chain.proceed()
                synchronized(lifetime.lock) {
                    val work = chain.thisObject
                    val lease = currentWork.get()
                    remember(module, asyncTickets, work, lease)
                }
                result
            }
        }
        type.declaredMethods.filter { it.name == "create" }.forEach { create ->
            module.hook(create).intercept { chain ->
                val result = chain.proceed()
                synchronized(lifetime.lock) {
                    val lease = asyncTickets[chain.thisObject]
                    remember(module, asyncTickets, result, lease)
                }
                result
            }
        }
        module.hook(type.getDeclaredMethod("invokeSuspend", Any::class.java)).intercept { chain ->
            val work = chain.thisObject
            val input = chain.args[0]
            val lease = synchronized(lifetime.lock) { asyncTickets[work] }
            // Result.Failure must reach native throwOnFailure, including cancellation resumptions.
            if (access.ready && collectionReady && lease != null &&
                !access.bridge.isFailure(input) && label.getInt(work) == 0
            ) {
                if (lifetime.isStale(lease)) return@intercept input
                if (trackingAvailable(module) &&
                    Looper.myLooper() == mainHandler.looper && gate.hasPending(lease)
                ) {
                    val pause = access.bridge.create(work!!, input)
                    if (pause != null) {
                        val parked = gate.park(lease, pause)
                        resumeLater(module, parked.released)
                        if (parked.parked) {
                            pause.onCancellation { gate.forgetWaiter(lease, pause) }
                        } else {
                            // Complete unused continuation before getResult; don't skip original body.
                            pause.resume()
                        }
                        val result = try {
                            pause.getResult()
                        } catch (failure: Throwable) {
                            gate.forgetWaiter(lease, pause)
                            throw failure
                        }
                        if (result === access.bridge.suspendedMarker) return@intercept result
                        gate.forgetWaiter(lease, pause)
                        if (lifetime.isStale(lease)) return@intercept input
                    }
                }
            }
            withWork(lease) { chain.proceed() }
        }
    }

    private fun collectionPresence(module: XposedModule, key: String): Presence {
        check(Looper.myLooper() == mainHandler.looper)
        val owner = collection.get() ?: return Presence.UNKNOWN
        val lookup = getEntry ?: return Presence.UNKNOWN
        return runCatching {
            if (lookup.invoke(owner, key) == null) Presence.ABSENT else Presence.PRESENT
        }.getOrElse {
            observeFailure(module, "active notification lookup", it)
            Presence.UNKNOWN
        }
    }

    private fun beginRemoval(module: XposedModule, lease: Ticket, reason: Int?): Token? {
        if (!trackingAvailable(module)) return null
        val start = gate.begin(lease, reason)
        resumeLater(module, start.released)
        return start.token
    }

    private fun submit(module: XposedModule, token: Token?) {
        if (token == null) return
        // After native delivery, so already-queued collection work wins queue order.
        if (!mainHandler.post {
                if (!gate.isCurrent(token)) return@post
                if (!deadlines.containsKey(token)) {
                    val timeout = Runnable {
                        deadlines.remove(token)
                        requests.remove(token)
                        resumeLater(module, gate.cancel(token))
                    }
                    deadlines[token] = timeout
                    if (!mainHandler.postDelayed(timeout, QUERY_DEADLINE_MS)) {
                        deadlines.remove(token)
                        resumeLater(module, gate.cancel(token))
                        return@post
                    }
                    requests.add(token)
                }
                pumpVerification(module)
            }) resumeLater(module, gate.cancel(token))
    }

    private fun clearRequest(token: Token) {
        requests.remove(token)
        deadlines.remove(token)?.let(mainHandler::removeCallbacks)
    }

    private fun pumpVerification(module: XposedModule) {
        check(Looper.myLooper() == mainHandler.looper)
        deadlines.keys.toList().filterNot(gate::isCurrent).forEach(::clearRequest)
        if (!trackingAvailable(module)) {
            resumeLater(module, gate.cancelAll())
            deadlines.keys.toList().forEach(::clearRequest)
            return
        }
        if (queryInFlight) return
        val batch = ArrayList<Token>()
        for (token in requests.toList()) {
            requests.remove(token)
            if (!gate.isCurrent(token) ||
                collectionPresence(module, token.ticket.stamp.key) != Presence.ABSENT
            ) {
                clearRequest(token)
                resumeLater(module, gate.cancel(token))
            } else {
                batch.add(token)
                if (batch.size >= 64) break
            }
        }
        if (batch.isEmpty()) return
        queryInFlight = true
        try {
            serviceExecutor.execute {
                val keys = batch.map { it.ticket.stamp.key }.toTypedArray()
                // Binder runs on one worker, off main. A stuck service cannot spawn extra threads.
                val active = runCatching {
                    notificationListener.get()?.getActiveNotifications(keys)?.map { it.key }?.toSet()
                }.getOrElse {
                    observeFailure(module, "notification-service lookup", it)
                    null
                }
                mainHandler.post {
                    queryInFlight = false
                    batch.forEach { token ->
                        clearRequest(token)
                        if (trackingAvailable(module) && gate.isCurrent(token)) {
                            val service = when {
                                active == null -> Presence.UNKNOWN
                                token.ticket.stamp.key in active -> Presence.PRESENT
                                else -> Presence.ABSENT
                            }
                            val resolution = gate.resolve(
                                token, collectionPresence(module, token.ticket.stamp.key), service,
                            )
                            if (resolution.retired) scheduleCleanup(module, token.ticket)
                            resumeLater(module, resolution.released)
                        }
                    }
                    pumpVerification(module)
                }
            }
        } catch (failure: RuntimeException) {
            queryInFlight = false
            batch.forEach {
                clearRequest(it)
                resumeLater(module, gate.cancel(it))
            }
            observeFailure(module, "notification-service dispatch", failure)
        }
    }

    private fun installRemovalListener(module: XposedModule, loader: ClassLoader) {
        runCatching {
            val type = Class.forName(
                "com.android.systemui.statusbar.notification.MiuiNotificationListener", false, loader,
            )
            type.declaredConstructors.forEach { constructor ->
                module.hook(constructor).intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? NotificationListenerService)?.let {
                        notificationListener = WeakReference(it)
                    }
                    result
                }
            }
            type.declaredMethods.filter { it.name == "onNotificationPosted" }.forEach { posted ->
                module.hook(posted).intercept { chain ->
                    (chain.thisObject as? NotificationListenerService)?.let {
                        notificationListener = WeakReference(it)
                    }
                    chain.proceed()
                }
            }
            val method = type.declaredMethods.single {
                it.name == "onNotificationRemoved" && it.parameterCount == 3 &&
                    it.parameterTypes[0] == StatusBarNotification::class.java &&
                    it.parameterTypes[2] == Int::class.javaPrimitiveType
            }
            module.hook(method).intercept { chain ->
                (chain.thisObject as? NotificationListenerService)?.let {
                    notificationListener = WeakReference(it)
                }
                // Synthetic reason=0 is not NMS cancellation. Record the candidate before native
                // delivery so an old queued completion cannot repost while verification is pending.
                val reason = chain.args[2] as? Int ?: 0
                val lease = if (reason > 0) ticket(chain.args[0] as? StatusBarNotification) else null
                val token = lease?.let { beginRemoval(module, it, reason) }
                try {
                    chain.proceed()
                } finally {
                    submit(module, token)
                }
            }
        }.onFailure { observeFailure(module, "notification-service removal hook", it) }
    }

    private fun scheduleCleanup(module: XposedModule, lease: Ticket) {
        check(Looper.myLooper() == mainHandler.looper)
        val key = lease.stamp.key
        if (trackingAvailable(module) && lease.closed && lifetime.canClean(key)) {
            if (!pendingCleanup.enqueue(lease)) {
                disableProtection(module)
                return
            }
            requestCleanup(module)
        }
    }

    private fun requestCleanup(module: XposedModule) {
        // Writer exits may come from any thread. Coalesce notifications; never wait for writers.
        cleanupDispatch.request()
    }

    private fun drainCleanup(module: XposedModule, requested: Boolean): Boolean {
        check(Looper.myLooper() == mainHandler.looper)
        if (!pendingCleanup.hasEntries) return false
        val start = SystemClock.uptimeMillis()
        // Backoff/BUSY validation and empty sweeps need no factory snapshot at all.
        val owners by lazy {
            synchronized(lifetime.lock) {
                factories.mapNotNull { owner ->
                    accesses[owner.javaClass]?.takeIf { it.ready }?.let { owner to it }
                }
            }
        }
        val more = pendingCleanup.drainSlice(
            start, requested, 4, { SystemClock.uptimeMillis() - start >= 2L },
            isValid = { !disabled && it.closed && lifetime.canClean(it.stamp.key) },
            attempt = { lease ->
                val key = lease.stamp.key
                FocusTemplateCleanupQueue.attemptOwners(
                    collectionPresence(module, key), owners,
                    commit = { (owner, access) ->
                        coordinator.tryCommitCleanup(owner, lease) {
                            unlinkExact(owner, access, key)
                        }
                    },
                    onFailure = { observeFailure(module, "cache retirement", it) },
                )
            },
            onFailure = { observeFailure(module, "cache retirement", it) },
            onExhausted = {
                observeFailure(module, "cache retirement retry exhausted", IllegalStateException())
            },
        )
        // A due entry may still be in the unvisited tail. Its continuation will visit it;
        // do not repeatedly arm a zero-delay retry while that finite sweep is in progress.
        if (!more) armCleanupRetry(module)
        return more
    }

    /** One delayed callback for the entire queue; busy factories are retried only on writer exit. */
    private fun armCleanupRetry(module: XposedModule) {
        check(Looper.myLooper() == mainHandler.looper)
        val next = pendingCleanup.nextRetryAt
        if (next == cleanupRetryAt && cleanupRetryTask != null) return
        cleanupRetryTask?.let(mainHandler::removeCallbacks)
        cleanupRetryTask = null
        cleanupRetryAt = null
        if (next != null) {
            val action = Runnable {
                cleanupRetryTask = null
                cleanupRetryAt = null
                requestCleanup(module)
            }
            if (mainHandler.postDelayed(action, (next - SystemClock.uptimeMillis()).coerceAtLeast(0L))) {
                cleanupRetryTask = action
                cleanupRetryAt = next
            } else {
                // Keep the pending entry and its due time; a later event can dispatch it again.
                observeFailure(module, "cache retry dispatch", IllegalStateException())
            }
        }
    }

    /** Short main transaction under metadata lock; no native lifecycle or Binder calls. */
    private fun unlinkExact(owner: Any, access: FactoryAccess, key: String) {
        val maps = access.fields.map {
            @Suppress("UNCHECKED_CAST")
            (it.get(owner) as MutableMap<Any?, Any?>)
        }
        FocusTemplateCacheTransaction.unlink(
            maps, key, holderCleanup::capture, holderCleanup::retire, holderCleanup::abandon,
        )
    }
}
