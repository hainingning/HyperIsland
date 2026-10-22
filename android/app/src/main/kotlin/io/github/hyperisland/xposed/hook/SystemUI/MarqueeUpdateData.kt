package io.github.hyperisland.xposed.hook

/** Per-call metadata only; never retains a View or continuation between updates. */
internal class MarqueeUpdateData private constructor(
    val islandData: Any?,
    val shouldApply: Boolean,
    private val resumed: Boolean,
) {
    fun canApplyAfter(owner: Any): Boolean = shouldApply &&
        (!resumed || runCatching { currentData(owner) === islandData }.getOrDefault(false))

    companion object {
        private const val RESUME_CLASS =
            "miui.systemui.dynamicisland.window.content.DynamicIslandContentView\$updateBigIslandView\$1"
        private val IGNORE = MarqueeUpdateData(null, false, true)

        fun resolve(owner: Any, argumentData: Any?, continuation: Any?): MarqueeUpdateData {
            if (argumentData != null) return MarqueeUpdateData(argumentData, true, false)
            if (continuation?.javaClass?.name != RESUME_CLASS) {
                // A real null-data update remains a clear/unknown update. Do not
                // reuse the previous notification merely because the View survived.
                return MarqueeUpdateData(null, true, false)
            }
            return runCatching {
                // Kotlin's generated invokeSuspend passes null for argumentData;
                // the native state machine restores its actual data from L$1.
                val label = field(continuation, "label") as? Int ?: return IGNORE
                if (label != (Int.MIN_VALUE or 1) ||
                    field(continuation, "this\$0") !== owner ||
                    field(continuation, "L\$0") !== owner
                ) return IGNORE
                val savedData = field(continuation, "L\$1") ?: return IGNORE
                if (currentData(owner) !== savedData) return IGNORE
                MarqueeUpdateData(savedData, true, true)
            }.getOrDefault(IGNORE)
        }

        private fun field(instance: Any, name: String): Any? =
            instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)

        private fun currentData(owner: Any): Any? =
            owner.javaClass.getMethod("getCurrentIslandData").invoke(owner)
    }
}
