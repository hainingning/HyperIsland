package io.github.hyperisland.xposed.hook

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

/**
 * Weak keys compared by identity, including objects with structural or mutable
 * equals/hashCode implementations. Values must not strongly retain their keys.
 * Callers sharing this map between threads must hold their own common lock.
 */
internal class WeakIdentityMap<V> {
    private class Key(
        value: Any,
        queue: ReferenceQueue<Any>? = null,
    ) : WeakReference<Any>(value, queue) {
        private val identityHash = System.identityHashCode(value)

        override fun hashCode(): Int = identityHash

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Key) return false
            val value = get() ?: return false
            return value === other.get()
        }
    }

    private val collected = ReferenceQueue<Any>()
    private val entries = HashMap<Key, V>()

    operator fun get(key: Any?): V? {
        drain()
        return if (key == null) null else entries[Key(key)]
    }

    operator fun set(key: Any, value: V) {
        drain()
        entries[Key(key, collected)] = value
    }

    fun remove(key: Any?): V? {
        drain()
        return if (key == null) null else entries.remove(Key(key))
    }

    val size: Int
        get() {
            drain()
            return entries.size
        }

    fun drain() {
        while (true) {
            val key = collected.poll() as? Key ?: return
            entries.remove(key)
        }
    }
}
