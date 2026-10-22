package io.github.hyperisland.xposed.hook.SystemUI.geometry

import java.lang.reflect.Method

/** Metadata belongs to the already-hooked class, never to a View/data instance. */
internal class IslandWidthAccess(
    type: Class<*>,
    find: (Class<*>, String) -> Method = { owner, name -> owner.getMethod(name) },
) {
    private val currentData = find(type, "getCurrentIslandData")
    private val extras = runCatching { find(currentData.returnType, "getExtras") }.getOrNull()

    fun extras(view: Any): Any? {
        val data = currentData.invoke(view) ?: return null
        // Older/unverified declarations may erase the return type. Keep their former fallback.
        return (extras ?: data.javaClass.getMethod("getExtras")).invoke(data)
    }
}
