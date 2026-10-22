package io.github.hyperisland.xposed.hook.SystemUI.geometry

import java.lang.ref.WeakReference

/** null/default inherits; zero explicitly requests the system calculation. */
internal object IslandWidthPolicy {
    data class Source(val packageName: String, val channelId: String)

    fun source(
        notificationPackage: String,
        notificationChannel: String?,
        sourcePackage: String?,
        sourceChannel: String?,
        originalChannel: String?,
    ): Source {
        val pkg = sourcePackage?.trim().orEmpty().ifEmpty { notificationPackage }
        val channel = sourceChannel?.trim().orEmpty()
            .ifEmpty { originalChannel?.trim().orEmpty() }
            .ifEmpty { if (pkg == notificationPackage) notificationChannel.orEmpty() else "" }
        return Source(pkg, channel)
    }

    fun parse(raw: String?): Int? = raw?.trim()?.toIntOrNull()?.takeIf { it in 0..1000 }

    fun resolve(channel: String?, app: String?, global: Int): Int =
        parse(channel) ?: parse(app) ?: global.coerceIn(0, 1000)
}

/** Synchronous calculation scope; owner is weak and every exit restores/removes the frame. */
internal class IslandWidthScope {
    private class Frame(val width: Int, owner: Any?) {
        val owner = owner?.let { WeakReference(it) }
    }
    private val value = ThreadLocal<Frame>()
    fun current(): Int? = value.get()?.width
    fun currentFor(owner: Any?): Int? = value.get()?.takeIf {
        owner != null && it.owner?.get() === owner
    }?.width

    fun <T> withWidth(width: Int, owner: Any? = null, block: () -> T): T {
        val previous = value.get()
        value.set(Frame(width, owner))
        return try {
            block()
        } finally {
            if (previous == null) value.remove() else value.set(previous)
        }
    }
}
