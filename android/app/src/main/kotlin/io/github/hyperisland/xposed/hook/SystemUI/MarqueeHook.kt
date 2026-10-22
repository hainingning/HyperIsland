package io.github.hyperisland.xposed.hook

import android.service.notification.StatusBarNotification
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import io.github.hyperisland.xposed.ConfigManager
import io.github.hyperisland.xposed.log
import io.github.hyperisland.xposed.logError
import io.github.hyperisland.xposed.islanddispatch.definition.IslandDispatchContract
import io.github.hyperisland.xposed.utils.HookUtils
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.util.WeakHashMap
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Hook SystemUI 中超级岛大岛视图的 TextView，实现自定义跑马灯（文字横向滚动）效果。
 */
object MarqueeHook : BaseHook() {

    private const val TAG = "HyperIsland[Marquee]"
    private const val SELF_PKG = "io.github.hyperisland"
    private const val SYSTEM_UI_PKG = "com.android.systemui"
    private const val DIRECT_PROXY_CHANNEL = IslandDispatchContract.CHANNEL_ID

    override fun getTag() = TAG

    private inline fun trace(message: () -> String) {
        log { "marquee-trace ${message()}" }
    }

    override fun onConfigChanged() {
        cachedSpeed = null
        hookedClassLoaders.clear()
        cachedWhitelist = null
        stopAllMarquees()
    }

    private val scrollerMap = WeakHashMap<TextView, MarqueeController>()
    private val observedViews = WeakHashMap<TextView, TextViewListeners>()
    private val islandMarqueeState = WeakHashMap<ViewGroup, Boolean>()
    private val islandAutoHideSessions = WeakHashMap<ViewGroup, IslandAutoHideSession>()
    private val islandUpdateTokens = java.util.Collections.synchronizedMap(
        WeakHashMap<ViewGroup, Any>(),
    )
    private val forcedSingleLineMaxLines = WeakHashMap<TextView, Int>()
    private val targetNotifications = java.util.Collections.synchronizedMap(
        WeakHashMap<View, StatusBarNotification>(),
    )

    private data class IslandAutoHideSession(
        val islandKey: String,
        val targetLoops: Int,
        val overrideTimeout: Boolean,
        val timeoutMs: Long,
        val notification: StatusBarNotification?,
        val startedAtMs: Long = SystemClock.elapsedRealtime(),
        val scrollingViews: WeakHashMap<TextView, Int> = WeakHashMap(),
        var fallbackRunnable: Runnable? = null,
        var fallbackGeneration: Long = 0L,
        var dismissed: Boolean = false,
    )

    @Volatile private var cachedSpeed: Int? = null

    private data class TextViewListeners(
        val layoutListeners: ArrayList<View.OnLayoutChangeListener>,
        val attachStateListener: View.OnAttachStateChangeListener,
        val textWatcher: android.text.TextWatcher
    )

    private fun normalizeText(text: String): String {
        return text
            .replace(Regex("[\\n\\r\\t\\u00A0\\u200B\\uFEFF]+"), " ")
            .replace(Regex(" +"), " ")
            .trim()
    }

    private fun findBigIslandView(view: View): ViewGroup? {
        var p = view.parent
        while (p is ViewGroup) {
            if (islandMarqueeState.containsKey(p)) return p
            p = p.parent
        }
        return null
    }

    private fun isMarqueeEnabledFor(textView: TextView): Boolean {
        val island = findBigIslandView(textView)
        return island?.let { islandMarqueeState[it] } ?: false
    }

    private fun getMarqueeSpeed(): Int {
        cachedSpeed?.let { return it }
        return ConfigManager.getInt("pref_marquee_speed", 100).coerceIn(20, 500)
            .also { cachedSpeed = it }
    }

    private fun stopAllMarquees() {
        val textViews = observedViews.keys.toList()
        textViews.forEach { unobserveTextView(it) }
        scrollerMap.values.forEach { it.stop() }
        scrollerMap.clear()
        forcedSingleLineMaxLines.clear()
        islandMarqueeState.clear()
        islandUpdateTokens.clear()
        targetNotifications.clear()
        islandAutoHideSessions.forEach { (island, session) ->
            session.fallbackRunnable?.let(island::removeCallbacks)
        }
        islandAutoHideSessions.clear()
    }

    private fun resetIslandMarqueeState(island: ViewGroup) {
        islandAutoHideSessions.remove(island)?.let { session ->
            cancelFallback(island, session)
        }
        scrollerMap.keys.toList()
            .filter { findBigIslandView(it) === island }
            .forEach { stopMarquee(it) }
    }

    fun onNotificationRemoved(notification: StatusBarNotification) {
        val islands = targetNotifications.entries
            .filter { sameNotification(it.value, notification) }
            .mapNotNull { it.key as? ViewGroup }
        islands.forEach { island ->
            islandUpdateTokens.remove(island)
            resetIslandMarqueeState(island)
            targetNotifications.remove(island)
        }
    }

    private fun sameNotification(
        first: StatusBarNotification,
        second: StatusBarNotification,
    ): Boolean {
        return first.key == second.key &&
            first.postTime == second.postTime &&
            first.uid == second.uid &&
            first.id == second.id &&
            first.tag == second.tag
    }

    fun startMarquee(textView: TextView) {
        val fullText = textView.text?.toString() ?: ""
        val cleanText = normalizeText(fullText)
        if (cleanText.isEmpty()) {
            stopMarquee(textView)
            return
        }
        if (textView.maxLines != 1) {
            if (!forcedSingleLineMaxLines.containsKey(textView)) {
                forcedSingleLineMaxLines[textView] = textView.maxLines
            }
            textView.setSingleLine(true)
        }
        if (fullText != cleanText) {
            textView.text = cleanText
        }
        val measuredW = textView.paint.measureText(cleanText)
        val visibleW = resolveVisibleWidth(textView)
        val availableW = visibleW - textView.paddingLeft - textView.paddingRight
        val needMarquee = measuredW > availableW

        if (needMarquee && visibleW > 0) {
            val speed = getMarqueeSpeed()
            val controller = scrollerMap.getOrPut(textView) { MarqueeController(textView, speed) }
            controller.speedPxPerSec = speed
            controller.start(measuredW)
        } else {
            stopMarquee(textView)
        }
    }

    fun stopMarquee(textView: TextView) {
        val controller = scrollerMap.remove(textView)
        controller?.stop()
        val originalMaxLines = forcedSingleLineMaxLines.remove(textView)
        if (originalMaxLines != null) {
            textView.setSingleLine(false)
            textView.maxLines = originalMaxLines
        }
        val fullText = textView.text?.toString() ?: ""
        val cleanText = normalizeText(fullText)
        if (fullText != cleanText) {
            textView.text = cleanText
        }
        unregisterScrollingView(textView)
    }

    private fun resolveVisibleWidth(view: View): Int {
        var visibleW = if (view.width > 0) view.width else Int.MAX_VALUE
        var p = view.parent
        while (p is ViewGroup) {
            if (p.width > 0 && p.width < visibleW) visibleW = p.width
            p = p.parent
        }
        return if (visibleW == Int.MAX_VALUE) 0 else visibleW
    }

    fun traverseAndApplyMarquee(
        bigIslandView: ViewGroup,
        enabled: Boolean,
        autoHideLoops: Int = 0,
        overrideTimeout: Boolean = false,
        originalTimeoutSecs: Int = 5,
    ) {
        val loops = if (enabled) autoHideLoops.coerceIn(0, 2) else 0
        val effectiveOverride = enabled && overrideTimeout && loops > 0
        // 开启覆盖超时（1_override/2_override）时，系统岛超时已被改成
        // Int.MAX_VALUE（见 NotificationHook / ToastUiInterceptHook），会话是
        // 岛消失的唯一责任人：无论大岛此刻有没有可滚动文本（短文本放不下
        // 滚动、纯图标、文本只在展开视图里、或展开态下视图尚未布局），
        // 都必须保留会话——没有滚动视图时由 fallback 定时器按原超时兜底，
        // 绝不能因为 scrollingViews 暂时为空就删掉会话，否则岛会常驻。
        // 只有未覆盖超时（系统超时仍正常）且确实没有文本时才不建会话。
        val hasText = enabled && hasMarqueeText(bigIslandView)
        val sessionLoops = if (loops > 0 && !hasText && !effectiveOverride) 0 else loops
//        trace { "apply enabled=$enabled loops=$loops override=$effectiveOverride text=$hasText " +
//                "island=${System.identityHashCode(bigIslandView)}" }
        islandMarqueeState[bigIslandView] = enabled
        configureAutoHideSession(
            bigIslandView,
            sessionLoops,
            effectiveOverride,
            originalTimeoutSecs,
        )
        traverseInternal(bigIslandView, enabled)
    }

    private fun hasMarqueeText(view: View): Boolean {
        if (view is TextView) {
            return !isInExpandedView(view) && normalizeText(view.text?.toString().orEmpty()).isNotEmpty()
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (hasMarqueeText(view.getChildAt(i))) return true
            }
        }
        return false
    }

    private fun configureAutoHideSession(
        island: ViewGroup,
        targetLoops: Int,
        overrideTimeout: Boolean,
        originalTimeoutSecs: Int,
    ) {
        val islandKey = targetIslandKey[island].orEmpty()
        val current = islandAutoHideSessions[island]
        if (targetLoops <= 0) {
            current?.let {
                cancelFallback(island, it)
            }
            islandAutoHideSessions.remove(island)
            return
        }
        current?.let {
            cancelFallback(island, it)
        }
        val session = IslandAutoHideSession(
            islandKey = islandKey,
            targetLoops = targetLoops,
            overrideTimeout = overrideTimeout,
            timeoutMs = originalTimeoutSecs.coerceAtLeast(1) * 1000L,
            notification = targetNotifications[island],
        )
        islandAutoHideSessions[island] = session
        trace { "create session island=${System.identityHashCode(island)} loops=$targetLoops override=$overrideTimeout timeout=${session.timeoutMs}" }
        scheduleFallbackIfNeeded(island, session)
    }

    private fun cancelFallback(island: ViewGroup, session: IslandAutoHideSession) {
        session.fallbackRunnable?.let(island::removeCallbacks)
        session.fallbackRunnable = null
        session.fallbackGeneration++
    }

    private fun registerScrollingView(textView: TextView) {
        val island = findBigIslandView(textView) ?: return
        val session = islandAutoHideSessions[island] ?: return
        session.scrollingViews[textView] = 0
        trace { "register view=${System.identityHashCode(textView)} island=${System.identityHashCode(island)}" }
        cancelFallback(island, session)
    }

    private fun unregisterScrollingView(textView: TextView) {
        val island = findBigIslandView(textView)
        if (island != null) {
            val session = islandAutoHideSessions[island]
            if (session != null && session.scrollingViews.remove(textView) != null) {
                trace { "unregister view=${System.identityHashCode(textView)} island=${System.identityHashCode(island)}" }
                scheduleFallbackIfNeeded(island, session)
            }
            return
        }
        // View detached 后已经没有父链，无法通过 findBigIslandView 找到原岛。
        // 从所有会话中清理，避免 override timeout 永远等待一个已移除的 TextView。
        islandAutoHideSessions.forEach { (candidateIsland, session) ->
            if (session.scrollingViews.remove(textView) != null) {
                trace { "unregister detached view=${System.identityHashCode(textView)} island=${System.identityHashCode(candidateIsland)}" }
                scheduleFallbackIfNeeded(candidateIsland, session)
            }
        }
    }

    private fun onLoopCompleted(textView: TextView, completedLoops: Int) {
        val island = findBigIslandView(textView) ?: return
        val session = islandAutoHideSessions[island] ?: return
        if (!session.scrollingViews.containsKey(textView)) return
        session.scrollingViews[textView] = completedLoops
        val shouldDismiss = if (session.overrideTimeout) {
            session.scrollingViews.isNotEmpty() &&
                session.scrollingViews.values.all { it >= session.targetLoops }
        } else {
            completedLoops >= session.targetLoops
        }
        if (shouldDismiss) {
            trace { "loop dismiss island=${System.identityHashCode(island)} loops=$completedLoops" }
            dismissIsland(island, session)
        }
    }

    private fun scheduleFallbackIfNeeded(island: ViewGroup, session: IslandAutoHideSession) {
        if (!session.overrideTimeout || session.dismissed || session.scrollingViews.isNotEmpty() ||
            session.fallbackRunnable != null) return
        val elapsed = SystemClock.elapsedRealtime() - session.startedAtMs
        val generation = session.fallbackGeneration
        val islandRef = WeakReference(island)
        val runnable = Runnable {
            if (session.fallbackGeneration != generation) return@Runnable
            session.fallbackRunnable = null
            val activeIsland = islandRef.get() ?: return@Runnable
            if (islandAutoHideSessions[activeIsland] !== session) return@Runnable
            trace { "fallback fire island=${System.identityHashCode(activeIsland)} views=${session.scrollingViews.size}" }
            if (session.scrollingViews.isEmpty()) dismissIsland(activeIsland, session)
        }
        session.fallbackRunnable = runnable
        island.postDelayed(runnable, (session.timeoutMs - elapsed).coerceAtLeast(0L))
    }

    private fun dismissIsland(island: ViewGroup, session: IslandAutoHideSession) {
        if (session.dismissed) return
        val key = targetIslandKey[island] ?: session.islandKey.takeIf { it.isNotBlank() }
        if (key == null) {
            trace { "dismiss aborted: no island key island=${System.identityHashCode(island)}" }
            return
        }
        session.dismissed = true
        cancelFallback(island, session)
        ActiveIslandDismissHook.dismiss(key, session.notification)
    }

    private fun isInExpandedView(view: View): Boolean {
        var p: View? = view
        while (p != null) {
            val className = p.javaClass.simpleName
            if (className.contains("DynamicIslandExpandedView") || 
                className.contains("ExpandedView")) {
                return true
            }
            p = if (p.parent is View) p.parent as View else null
        }
        return false
    }

    private fun traverseInternal(view: View, enabled: Boolean) {
        if (view is TextView) {
            if (isInExpandedView(view)) return
            if (enabled) {
                observeTextView(view)
                startMarquee(view)
            } else {
                unobserveTextView(view)
                stopMarquee(view)
            }
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                traverseInternal(view.getChildAt(i), enabled)
            }
        }
    }

    private fun observeTextView(view: TextView) {
        if (observedViews.containsKey(view)) return

        val listeners = ArrayList<View.OnLayoutChangeListener>()
        val layoutListener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val tv = v as TextView
            if (isInExpandedView(tv)) return@OnLayoutChangeListener
            tv.post {
                if (isMarqueeEnabledFor(tv)) startMarquee(tv)
                else stopMarquee(tv)
            }
        }
        listeners.add(layoutListener)
        view.addOnLayoutChangeListener(layoutListener)

        val textWatcher = object : android.text.TextWatcher {
            private val viewRef = WeakReference(view)

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val textView = viewRef.get() ?: return
                if (isInExpandedView(textView)) return
                textView.post {
                    val attachedView = viewRef.get() ?: return@post
                    if (isMarqueeEnabledFor(attachedView)) startMarquee(attachedView)
                    else stopMarquee(attachedView)
                }
            }
        }
        view.addTextChangedListener(textWatcher)
        val attachStateListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}

            override fun onViewDetachedFromWindow(v: View) {
                (v as? TextView)?.let {
                    unobserveTextView(it)
                    stopMarquee(it)
                }
            }
        }
        view.addOnAttachStateChangeListener(attachStateListener)
        observedViews[view] = TextViewListeners(listeners, attachStateListener, textWatcher)
    }

    private fun unobserveTextView(view: TextView) {
        val listeners = observedViews.remove(view) ?: return
        listeners.layoutListeners.forEach { view.removeOnLayoutChangeListener(it) }
        view.removeOnAttachStateChangeListener(listeners.attachStateListener)
        view.removeTextChangedListener(listeners.textWatcher)
    }

    private val hookedClassLoaders = ConcurrentHashMap.newKeySet<Int>()
    private val targetPkg = java.util.Collections.synchronizedMap(WeakHashMap<View, String>())
    private val targetChannel = java.util.Collections.synchronizedMap(WeakHashMap<View, String>())
    private val targetIslandKey = java.util.Collections.synchronizedMap(WeakHashMap<View, String>())
    @Volatile private var cachedWhitelist: Map<String, Set<String>>? = null
    private val directProxyExpireAt =
        java.util.Collections.synchronizedMap(mutableMapOf<String, Long>())

    fun markDirectProxyPosted(pkg: String, channelId: String) {
        if (pkg.isBlank()) return
        val safeChannel = channelId.ifBlank { "toast" }
        val key = "$pkg#$safeChannel"
        directProxyExpireAt[key] = SystemClock.elapsedRealtime() + 10_000L
    }

    private fun takeRecentDirectProxySource(): Pair<String, String>? {
        val now = SystemClock.elapsedRealtime()
        val iterator = directProxyExpireAt.entries.iterator()
        var candidate: Pair<String, String>? = null
        var bestExpire = 0L
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value <= now) {
                iterator.remove()
                continue
            }
            if (entry.value > bestExpire) {
                val parts = entry.key.split('#', limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank()) {
                    candidate = parts[0] to parts[1]
                    bestExpire = entry.value
                }
            }
        }
        return candidate
    }

    private fun loadWhitelist(): Map<String, Set<String>> {
        cachedWhitelist?.let { return it }
        val csv = ConfigManager.getString("pref_generic_whitelist")
        val map = csv.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .associate { pkg ->
                val channelCsv = ConfigManager.getString("pref_channels_$pkg")
                val channels = if (channelCsv.isBlank()) emptySet()
                else channelCsv.split(",").filter { it.isNotBlank() }.toSet()
                pkg to channels
            }
        if (map.isNotEmpty()) cachedWhitelist = map
        return map
    }

    override fun onInit(module: XposedModule, param: PackageLoadedParam) {
        hookDynamicClassLoaders(module)
    }

    private fun hookContentViewClasses(module: XposedModule, classLoader: ClassLoader) {
        // 按 ClassLoader identityHashCode 去重
        if (!hookedClassLoaders.add(System.identityHashCode(classLoader))) return
        val classNames = arrayOf(
            "miui.systemui.dynamicisland.window.content.DynamicIslandContentView"
        )
        for (className in classNames) {
            try {
                val clazz = classLoader.loadClass(className)
                val updateMethod = clazz.declaredMethods.firstOrNull { it.name == "updateBigIslandView" }
                if (updateMethod != null) {
                    module.hook(updateMethod).intercept { chain ->
                        val islandView = chain.thisObject as? ViewGroup ?: return@intercept chain.proceed()
                        // A suspended native update re-enters with a null data argument.
                        // Recover only its own still-current continuation before proceed
                        // consumes the resume label. Stale resumes must not stop a new island.
                        val updateData = MarqueeUpdateData.resolve(
                            islandView, chain.args.getOrNull(0), chain.args.lastOrNull(),
                        )
                        if (!updateData.shouldApply) return@intercept chain.proceed()
                        val updateToken = Any()
                        islandUpdateTokens[islandView] = updateToken
                        resetIslandMarqueeState(islandView)
                        targetIslandKey[islandView]?.let(ActiveIslandDismissHook::invalidate)
                        val result = chain.proceed()
                        try {
                            if (islandUpdateTokens[islandView] !== updateToken ||
                                !updateData.canApplyAfter(islandView)
                            ) {
                                trace { "ignore stale island update island=${System.identityHashCode(islandView)}" }
                                return@intercept result
                            }
                            val islandData = updateData.islandData
                            var pkgName = ""
                            var channelId = ""
                            var islandKey = ""
                            var isOngoing: Boolean? = null
                            try {
                                if (islandData != null) {
                                    islandKey = islandData.javaClass.getMethod("getKey")
                                        .invoke(islandData) as? String ?: ""
                                    val getExtrasMethod = islandData.javaClass.getMethod("getExtras")
                                    val extras = getExtrasMethod.invoke(islandData) as? android.os.Bundle
                                    val sbn = if (android.os.Build.VERSION.SDK_INT >= 33) {
                                        extras?.getParcelable("miui.sbn", StatusBarNotification::class.java)
                                    } else {
                                        @Suppress("DEPRECATION")
                                        extras?.getParcelable("miui.sbn") as? StatusBarNotification
                                    }
                                    isOngoing = sbn?.isOngoing
                                    pkgName = sbn?.packageName ?: ""
                                    channelId = sbn?.notification?.channelId ?: ""
                                    val sourcePkg = sbn?.notification?.extras
                                        ?.getString("hyperisland_source_pkg")
                                        ?.trim()
                                        .orEmpty()
                                    val sourceChannel = sbn?.notification?.extras
                                        ?.getString("hyperisland_source_channel")
                                        ?.trim()
                                        .orEmpty()
                                    if (sourcePkg.isNotEmpty()) {
                                        pkgName = sourcePkg
                                        channelId = sourceChannel.ifEmpty {
                                            if (channelId.isNotEmpty()) channelId else "toast"
                                        }
                                    } else if (
                                        (pkgName == SELF_PKG || pkgName == SYSTEM_UI_PKG) &&
                                            channelId == DIRECT_PROXY_CHANNEL
                                    ) {
                                        val recent = takeRecentDirectProxySource()
                                        if (recent != null) {
                                            pkgName = recent.first
                                            channelId = recent.second
                                        }
                                    }
                                    if (sbn != null) targetNotifications[islandView] = sbn
                                    targetPkg[islandView] = pkgName
                                    targetChannel[islandView] = channelId
                                    if (islandKey.isNotEmpty()) {
                                        val notificationKey = sbn?.key
                                        targetIslandKey[islandView] = notificationKey ?: islandKey
                                    }
                                }
                            } catch (_: Exception) {}
                            if (pkgName.isEmpty()) {
                                pkgName = targetPkg[islandView] ?: ""
                            }
                            if (channelId.isEmpty()) {
                                channelId = targetChannel[islandView] ?: ""
                            }
                            if (pkgName.isEmpty()) return@intercept result
                            
                            val isToastSource = channelId == "toast"
                            if (!isToastSource) {
                                val whitelist = loadWhitelist()
                                val allowedChannels = whitelist[pkgName]
                                if (allowedChannels == null) {
                                    traverseAndApplyMarquee(islandView, false)
                                    return@intercept result
                                }

                                if (allowedChannels.isNotEmpty() && channelId.isEmpty()) {
                                    traverseAndApplyMarquee(islandView, false)
                                    return@intercept result
                                }

                                if (allowedChannels.isNotEmpty() && channelId.isNotEmpty() && channelId !in allowedChannels) {
                                    traverseAndApplyMarquee(islandView, false)
                                    return@intercept result
                                }
                            }

                            val marqueeRaw = if (isToastSource) {
                                ConfigManager.getString("pref_toast_marquee_$pkgName", "default")
                            } else {
                                val marqueeKey = "pref_channel_marquee_${pkgName}_${channelId}"
                                ConfigManager.getString(marqueeKey, "default")
                            }
                            val defaultMarquee = ConfigManager.getBoolean("pref_default_marquee", false)
                            val countTemplate = !isToastSource && ConfigManager.getString(
                                "pref_channel_template_${pkgName}_${channelId}",
                                "notification_island",
                            ) == "notification_count_island"
                            // 数量岛使用固定数字图标，不参与跑马灯；否则默认跑马灯的
                            // auto-hide 会接管岛的生命周期，导致代发岛无法按正常超时消失。
                            val enabled = if (countTemplate) {
                                false
                            } else {
                                when (marqueeRaw) {
                                    "on" -> true
                                    "off" -> false
                                    else -> defaultMarquee
                                }
                            }
                            val effectiveAutoHide = if (countTemplate) {
                                "off"
                            } else if (isToastSource) {
                                val autoHideRaw = ConfigManager.getString(
                                    "pref_toast_marquee_auto_hide_$pkgName",
                                    "default"
                                )
                                val defaultAutoHideRaw = ConfigManager.getString(
                                    "pref_default_marquee_auto_hide",
                                    "off"
                                )
                                val effectiveAutoHide = if (autoHideRaw == "default") {
                                    defaultAutoHideRaw
                                } else {
                                    autoHideRaw
                                }
                                effectiveAutoHide
                            } else {
                                val autoHideRaw = ConfigManager.getString(
                                    "pref_channel_marquee_auto_hide_${pkgName}_${channelId}",
                                    "default"
                                )
                                val defaultAutoHideRaw = ConfigManager.getString(
                                    "pref_default_marquee_auto_hide",
                                    "off"
                                )
                                val effectiveAutoHide = if (autoHideRaw == "default") {
                                    defaultAutoHideRaw
                                } else {
                                    autoHideRaw
                                }
                                effectiveAutoHide
                            }
                            val policy = IslandMarqueePolicy.resolve(
                                enabled = enabled,
                                explicitlyEnabled = marqueeRaw == "on",
                                ongoing = isOngoing,
                                autoHide = effectiveAutoHide,
                            )
                            val originalTimeoutSecs = if (isToastSource) {
                                val rawTimeout = ConfigManager.getString(
                                    "pref_toast_timeout_$pkgName",
                                    "default",
                                )
                                if (rawTimeout == "default") {
                                    ConfigManager.getInt("pref_default_timeout", 5)
                                        .coerceAtLeast(1)
                                } else {
                                    rawTimeout.toIntOrNull()?.coerceAtLeast(1) ?: 5
                                }
                            } else {
                                val rawChannelTimeout = ConfigManager.getString(
                                    "pref_channel_timeout_${pkgName}_${channelId}",
                                    "default",
                                )
                                if (rawChannelTimeout == "default") {
                                    ConfigManager.getInt("pref_default_timeout", 5)
                                        .coerceAtLeast(1)
                                } else {
                                    rawChannelTimeout.toIntOrNull()?.coerceAtLeast(1) ?: 5
                                }
                            }
                            
                            if (!policy.enabled) {
                                traverseAndApplyMarquee(islandView, false)
                                return@intercept result
                            }
                            
                            //log("Marquee triggered for package: $pkgName")
                            traverseAndApplyMarquee(
                                islandView,
                                true,
                                policy.autoHideLoops,
                                policy.overrideTimeout,
                                originalTimeoutSecs,
                            )
                        } catch (e: Exception) {
                            logError("Error in updateBigIslandView hook: ${e.message}")
                        }
                        result
                    }
                    module.log { "Hooked updateBigIslandView on $className (classLoader=${System.identityHashCode(classLoader)})" }
                }
            } catch (_: Exception) {}
        }
    }

    private fun hookDynamicClassLoaders(module: XposedModule) {
        HookUtils.hookDynamicClassLoaders(module, ClassLoader.getSystemClassLoader()) { cl ->
            hookContentViewClasses(module, cl)
        }
    }

    class MarqueeController(
        view: TextView,
        var speedPxPerSec: Int = 100,
        private val delayMs: Int = 1500
    ) : Choreographer.FrameCallback, ViewTreeObserver.OnPreDrawListener {

        private companion object {
            const val PAUSE_AT_END_MS = 1000
        }

        private var currentScrollX = 0f
        private var distanceScrolled = 0f
        private val scrollRange = MarqueeScrollRange()
        private val viewport = MarqueeViewport()
        private val readiness = MarqueeReadiness()
        private val usesTimerTextEffect = view.javaClass.name == "miuix.colorful.texteffect.TimerTextEffectView"
        private var edgeShadeRef: WeakReference<View>? = null
        private var measuredTextWidth = 0f
        private var isRunning = false
        private var startTimeNanos = 0L
        private var lastFrameTimeNanos = 0L
        private val choreographer = Choreographer.getInstance()
        private val viewRef = WeakReference(view)
        private var state = 0
        private var currentText = ""
        private var completedLoops = 0
        private var pauseScheduled = false
        private var drawTreeRef = WeakReference<ViewTreeObserver>(null)
        private var generation = 0L

        fun start(textWidth: Float) {
            val view = viewRef.get() ?: return
            measuredTextWidth = textWidth
            edgeShadeRef = if (usesTimerTextEffect) findEdgeShade(view)?.let(::WeakReference) else null
            val textNow = normalizeText(view.text.toString())
            if (isRunning && currentText == textNow) {
                if (!readiness.waiting) registerScrollingView(view)
                return
            }
            registerScrollingView(view)
            currentText = textNow
            generation++
            isRunning = true
            pauseScheduled = false
            removeDrawListener()
            readiness.start()
            distanceScrolled = 0f
            currentScrollX = 0f
            if (updateScrollRange(view)) {
                currentScrollX = scrollRange.start
                view.scrollTo(currentScrollX.roundToInt(), 0)
            }
            state = 0
            completedLoops = 0
            startTimeNanos = 0
            choreographer.removeFrameCallback(this)
            choreographer.postFrameCallback(this)
        }

        fun stop() {
            generation++
            isRunning = false
            pauseScheduled = false
            edgeShadeRef = null
            readiness.stop()
            choreographer.removeFrameCallback(this)
            removeDrawListener()
            viewRef.get()?.let { view ->
                view.scrollTo(0, 0)
            }
        }

        private fun removeDrawListener() {
            drawTreeRef.get()?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            drawTreeRef.clear()
        }

        private fun observeDraw(view: TextView) {
            val tree = view.viewTreeObserver
            if (drawTreeRef.get() === tree) return
            removeDrawListener()
            if (tree.isAlive) {
                tree.addOnPreDrawListener(this)
                drawTreeRef = WeakReference(tree)
            }
        }

        private fun schedulePause(view: TextView, durationMs: Long) {
            // Frame timestamps and nanoTime share the monotonic clock. Account for a
            // late UI dispatch: the relative wake must retain the original deadline.
            val remainingNanos = durationMs * 1_000_000L - (System.nanoTime() - startTimeNanos)
            val delayMillis = ((remainingNanos + 999_999L) / 1_000_000L).coerceAtLeast(0L)
            pauseScheduled = true
            // One delayed wake; native traversals may still update geometry or hide the view.
            observeDraw(view)
            choreographer.removeFrameCallback(this)
            choreographer.postFrameCallbackDelayed(this, delayMillis)
        }

        private fun findEdgeShade(view: TextView): View? {
            // The verified MIUI template puts this overlay next to the text
            // container. Search only these two levels, never another module.
            var parent = view.parent as? ViewGroup
            repeat(2) {
                val group = parent ?: return null
                if (islandMarqueeState.containsKey(group)) return null
                for (i in 0 until group.childCount) {
                    val child = group.getChildAt(i)
                    if (child.id != View.NO_ID && runCatching {
                        child.resources.getResourceEntryName(child.id) == "island_text_shade"
                    }.getOrDefault(false)) return child
                }
                parent = group.parent as? ViewGroup
            }
            return null
        }

        private fun awaitReadyDraw(view: TextView) {
            pauseScheduled = false
            choreographer.removeFrameCallback(this)
            unregisterScrollingView(view)
            // Native text scaling may make the whole line fit. Do not leave
            // the old scroll offset clipping its first character while waiting.
            view.scrollTo(0, 0)
            readiness.awaitDraw()
            observeDraw(view)
        }

        override fun onPreDraw(): Boolean {
            val view = viewRef.get()
            if (view == null) {
                stop()
                return true
            }
            if (!isRunning || !view.isAttachedToWindow || !isMarqueeEnabledFor(view)) {
                stop()
                return true
            }
            val ready = isVisibleForMarquee(view) && updateScrollRange(view)
            if (pauseScheduled) {
                if (!ready) {
                    awaitReadyDraw(view)
                } else {
                    // Keep the same endpoint while native width/scale changes during a pause.
                    currentScrollX = if (state == 2) scrollRange.end else scrollRange.start
                    view.scrollTo(currentScrollX.roundToInt(), 0)
                }
                return true
            }
            if (readiness.onDraw(true, true, ready)) {
                removeDrawListener()
                registerScrollingView(view)
                distanceScrolled = 0f
                currentScrollX = scrollRange.start
                view.scrollTo(currentScrollX.roundToInt(), 0)
                state = 0
                startTimeNanos = 0L
                choreographer.postFrameCallback(this)
            }
            return true
        }

        private fun isVisibleForMarquee(view: TextView): Boolean {
            if (view.windowVisibility != View.VISIBLE) return false
            var current: View? = view
            // Visibility includes ancestors outside the island; geometry intentionally
            // does not. A fake-view transition can hide an otherwise valid viewport.
            while (current != null) {
                if (current.visibility != View.VISIBLE || !(current.alpha > 0f)) return false
                current = current.parent as? View
            }
            return true
        }

        private fun updateScrollRange(view: TextView): Boolean {
            val layout = view.layout ?: return false
            if (layout.lineCount == 0 || view.width <= 0) return false
            viewport.reset(view.width, view.compoundPaddingLeft, view.compoundPaddingRight)
            var child: View = view
            var parent = child.parent
            val shade = edgeShadeRef?.get()
            while (parent is ViewGroup) {
                if (parent.width <= 0) return false
                if (!viewport.parent(child.left, parent.scrollX, parent.width,
                    parent.paddingLeft, parent.paddingRight, parent.clipChildren, parent.clipToPadding,
                    child.translationX, child.scaleX, child.pivotX)) return false
                if (shade != null && shade.parent === parent && shade.visibility == View.VISIBLE &&
                    shade.alpha > 0f && shade.width > 0 && shade.scaleX > 0f) {
                    val shadeLeft = shade.left + shade.translationX + shade.pivotX * (1f - shade.scaleX) - parent.scrollX
                    viewport.insetForEdgeShade(shadeLeft, shadeLeft + shade.width * shade.scaleX,
                        parent.width, view.resources.displayMetrics.density)
                }
                // The outer island transition changes screen coordinates, not the
                // local text viewport. Never feed its transient global clip back in.
                if (islandMarqueeState.containsKey(parent)) break
                child = parent
                parent = child.parent
            }
            if (usesTimerTextEffect) {
                // MIUI's replacement span draws the complete text, while Layout's
                // line bounds can describe only its placeholder. Use the same full
                // text Paint measurement that admitted this view to marquee.
                val alignment = when (Gravity.getAbsoluteGravity(view.gravity, view.layoutDirection) and Gravity.HORIZONTAL_GRAVITY_MASK) {
                    Gravity.RIGHT -> 1f
                    Gravity.CENTER_HORIZONTAL -> 0.5f
                    else -> 0f
                }
                return scrollRange.updateMeasured(measuredTextWidth, layout.width.toFloat(),
                    view.compoundPaddingLeft.toFloat(), alignment,
                    viewport.left, viewport.right, layout.getParagraphDirection(0) < 0)
            }
            return scrollRange.update(
                view.compoundPaddingLeft + layout.getLineLeft(0),
                view.compoundPaddingLeft + layout.getLineRight(0),
                viewport.left, viewport.right, layout.getParagraphDirection(0) < 0,
            )
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (!isRunning) return
            val frameGeneration = generation
            pauseScheduled = false
            removeDrawListener()
            val view = viewRef.get()
            if (view == null) {
                stop()
                return
            }
            // View 已离开窗口（岛消失/被回收/切换到展开态），立即停止并解除注册，
            // 避免覆盖超时会话永远等待一个不会再产生循环回调的视图。
            if (!view.isAttachedToWindow) {
                unregisterScrollingView(view)
                stop()
                return
            }
            if (startTimeNanos == 0L) {
                startTimeNanos = frameTimeNanos
                lastFrameTimeNanos = frameTimeNanos
            }
            if (!isVisibleForMarquee(view) || !updateScrollRange(view)) {
                // A native transition can temporarily hide the real view or replace
                // its Layout. Resume on a real draw after layout, without a timer or
                // continuously posting frames while invisible. Detach/stop removes it.
                awaitReadyDraw(view)
                return
            }

            val elapsedMs = (frameTimeNanos - startTimeNanos) / 1_000_000
            when (state) {
                0 -> {
                    distanceScrolled = 0f
                    if (elapsedMs >= delayMs) {
                        state = 1
                        lastFrameTimeNanos = frameTimeNanos
                    }
                }
                1 -> {
                    distanceScrolled += speedPxPerSec * ((frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000f)
                    if (distanceScrolled >= scrollRange.distance) {
                        distanceScrolled = scrollRange.distance
                        state = 2
                        startTimeNanos = frameTimeNanos
                    }
                }
                2 -> if (elapsedMs > PAUSE_AT_END_MS) {
                    completedLoops += 1
                    onLoopCompleted(view, completedLoops)
                    if (!isRunning || generation != frameGeneration) return
                    val island = findBigIslandView(view)
                    val session = island?.let { islandAutoHideSessions[it] }
                    if (session?.dismissed == true) {
                        stop()
                        return
                    }
                    distanceScrolled = 0f
                    state = 0
                    startTimeNanos = frameTimeNanos
                }
            }
            if (state == 2) distanceScrolled = scrollRange.distance
            currentScrollX = scrollRange.position(distanceScrolled)
            view.scrollTo(currentScrollX.roundToInt(), 0)
            lastFrameTimeNanos = frameTimeNanos
            if (!isRunning || generation != frameGeneration) return
            when (state) {
                0 -> schedulePause(view, delayMs.toLong())
                // Preserve the existing strictly-greater-than-1000ms end-pause condition.
                2 -> schedulePause(view, PAUSE_AT_END_MS + 1L)
                else -> choreographer.postFrameCallback(this)
            }
        }
    }
}
