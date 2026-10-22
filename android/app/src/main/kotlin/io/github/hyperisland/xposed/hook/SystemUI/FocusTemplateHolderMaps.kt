package io.github.hyperisland.xposed.hook

import java.lang.reflect.Field
import java.lang.reflect.Method

/** The runtime and JVM fixtures use these same reflective map operations. */
internal object FocusTemplateHolderMaps {
    fun visit(
        adapter: Any,
        maps: List<Field>,
        holderType: Class<*>,
        detachMethods: Set<Method>,
        accept: (Field, String, Any) -> Unit,
    ) {
        maps.forEach { field ->
            val map = field.get(adapter) as Map<*, *>
            map.forEach { (key, holder) ->
                check(key is String) { "Unknown holder slot key type" }
                // The verified plugin keeps null placeholders for modules without a View.
                if (holder != null) {
                    check(holderType.isInstance(holder)) { "Unknown holder slot value type" }
                    check(holder.javaClass.getMethod("onDetach") in detachMethods)
                    accept(field, key, holder)
                }
            }
        }
    }

    fun hasLiveHolders(adapter: Any, maps: List<Field>): Boolean =
        maps.any { (it.get(adapter) as Map<*, *>).values.any { value -> value != null } }

    /** Idempotent metadata cleanup, only after the complete native onDetach succeeded. */
    fun removeCompletedSlot(
        adapter: Any,
        maps: List<Field>,
        dataMap: Field,
        slot: Field,
        key: String,
        holder: Any,
    ) {
        @Suppress("UNCHECKED_CAST")
        val map = slot.get(adapter) as MutableMap<Any?, Any?>
        if (map[key] === holder) map.remove(key)
        if (maps.all { (it.get(adapter) as Map<*, *>)[key] == null }) {
            @Suppress("UNCHECKED_CAST")
            val data = dataMap.get(adapter) as MutableMap<Any?, Any?>
            data.remove(key)
        }
    }
}
