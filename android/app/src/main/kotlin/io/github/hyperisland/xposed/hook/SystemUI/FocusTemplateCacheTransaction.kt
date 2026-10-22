package io.github.hyperisland.xposed.hook

/** Short transaction under the factory writer lock; performs no native View lifecycle calls. */
internal object FocusTemplateCacheTransaction {
    fun <T : Any> unlink(
        maps: List<MutableMap<Any?, Any?>>,
        key: String,
        capture: (List<Any>) -> T?,
        retire: (T) -> Boolean,
        abandon: (T) -> Unit,
    ) {
        check(maps.size == 5)
        val saved = maps.map { it.containsKey(key) to it[key] }
        if (saved.none { it.first }) return
        val plan = capture(saved.take(4).mapNotNull { it.second })
            ?: error("Holder retirement snapshot unavailable")
        try {
            maps.forEach { it.remove(key) }
            check(retire(plan)) { "Holder retirement dispatch unavailable" }
        } catch (failure: Throwable) {
            // A rollback failure on one map must not skip restoration of the remaining maps.
            maps.zip(saved).forEach { (map, old) ->
                runCatching {
                    if (old.first) map[key] = old.second else map.remove(key)
                }.onFailure { if (it !== failure) failure.addSuppressed(it) }
            }
            runCatching { abandon(plan) }
                .onFailure { if (it !== failure) failure.addSuppressed(it) }
            throw failure
        }
    }
}
