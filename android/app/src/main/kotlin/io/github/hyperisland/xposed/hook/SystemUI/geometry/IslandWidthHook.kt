package io.github.hyperisland.xposed.hook.SystemUI.geometry

import android.content.res.Resources
import android.os.Bundle
import android.service.notification.StatusBarNotification
import io.github.hyperisland.xposed.ConfigManager
import io.github.hyperisland.xposed.hook.BaseHook
import io.github.hyperisland.xposed.utils.HookUtils
import io.github.hyperisland.xposed.utils.ResourceDimenHook
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModule

object BigIslandMinWidthHook : BaseHook() {

    private const val TAG = "HyperIsland[IslandWidthHook]"
    private const val KEY_MAX_WIDTH = "pref_big_island_max_width"
    private const val KEY_MIN_WIDTH = "pref_big_island_min_width"
    private const val KEY_SMALL_WIDTH = "pref_small_island_width"
    private const val KEY_SMALL_OFFSET = "pref_small_island_horizontal_offset"
    private const val ISLAND_SPACE_RESOURCE = "island_space"
    private const val BASE_CONTENT_VIEW_CLASS =
        "miui.systemui.dynamicisland.window.content.DynamicIslandBaseContentView"
    private const val PHONE_HELPER_CLASS =
        "miui.systemui.dynamicisland.window.content.helpers.DynamicIslandContentViewPhoneHelper"

    override fun getTag() = TAG

    private var hookedLegacyCalculateMaxWidthWithSmall = false
    private var hookedModernCalculateMaxWidthWithSmall = false
    private var hookedWidthGetter = false
    private var hookedCalculation = false
    private val calculationScope = IslandWidthScope()
    private val registrationLock = Any()
    private var dynamicClassLoaderCallbackRegistered = false

    private fun dpToPx(dp: Int): Float {
        val density = Resources.getSystem().displayMetrics.density
        return dp * density
    }

    override fun onInit(module: XposedModule, param: PackageLoadedParam) {
        registerDimensionResources(module)
        hookDynamicClassLoaders(module)
    }

    private fun registerDimensionResources(module: XposedModule) {
        ResourceDimenHook.registerDpOrSystem(
            module,
            "big_island_min_width",
            KEY_MIN_WIDTH,
            0,
            1..500,
        )
        ResourceDimenHook.registerDpOrSystem(
            module,
            "big_island_min_width_pad",
            KEY_MIN_WIDTH,
            0,
            1..500,
        )
        ResourceDimenHook.registerDpOrSystem(
            module,
            "small_island_width",
            KEY_SMALL_WIDTH,
            34,
            1..100,
        )
        ResourceDimenHook.registerDpOffset(
            module,
            ISLAND_SPACE_RESOURCE,
            KEY_SMALL_OFFSET,
            0,
            -10..50,
        )
    }

    private fun hookContentViewClasses(module: XposedModule, classLoader: ClassLoader) {
        synchronized(registrationLock) {
            hookBaseContentView(module, classLoader)
            hookWidthCalculation(module, classLoader)
            hookModernMaxWidthWithSmall(module, classLoader)
        }
    }

    private fun widthForView(view: Any?, access: IslandWidthAccess?): Int {
        val global = ConfigManager.getInt(KEY_MAX_WIDTH, 0)
        if (view == null) return global.coerceIn(0, 1000)
        return runCatching {
            val extras = if (access != null) access.extras(view) as? Bundle else {
                val data = view.javaClass.getMethod("getCurrentIslandData").invoke(view)
                    ?: return@runCatching global.coerceIn(0, 1000)
                data.javaClass.getMethod("getExtras").invoke(data) as? Bundle
            }
            val sbn = extras?.getParcelable("miui.sbn", StatusBarNotification::class.java)
                ?: return@runCatching global.coerceIn(0, 1000)
            fun sourceExtra(key: String): String? = sbn.notification.extras.getString(key)
                ?.trim()?.takeIf { it.isNotEmpty() } ?: extras.getString(key)
            val source = IslandWidthPolicy.source(
                sbn.packageName, sbn.notification.channelId,
                sourceExtra("hyperisland_source_pkg"),
                sourceExtra("hyperisland_source_channel"),
                sourceExtra("hyperisland_channel_id"),
            )
            IslandWidthPolicy.resolve(
                ConfigManager.getChannelIslandWidth(source.packageName, source.channelId),
                ConfigManager.getString("pref_notification_max_width_${source.packageName}", "default"),
                global,
            )
        }.getOrDefault(global.coerceIn(0, 1000))
    }

    private fun scopedWidthForView(view: Any?, access: IslandWidthAccess?): Int =
        calculationScope.currentFor(view) ?: widthForView(view, access)

    private fun hookWidthCalculation(module: XposedModule, classLoader: ClassLoader) {
        if (hookedCalculation) return
        try {
            val base = classLoader.loadClass(BASE_CONTENT_VIEW_CLASS)
            val clazz = classLoader.loadClass(
                "miui.systemui.dynamicisland.window.content.DynamicIslandContentView",
            )
            val method = clazz.declaredMethods.firstOrNull {
                it.name == "calculateBigIslandWidth" && it.parameterTypes.isEmpty()
            } ?: return
            val maxWidth = base.getDeclaredField("maxWidth").apply { isAccessible = true }
            val access = runCatching { IslandWidthAccess(clazz) }.getOrNull()
            module.hook(method).intercept { chain ->
                val target = chain.thisObject ?: return@intercept chain.proceed()
                // Each native calculation starts fresh; only its same-owner getters reuse it.
                val width = widthForView(target, access)
                val original = maxWidth.getFloat(target)
                calculationScope.withWidth(width, target) {
                    // Cover direct field reads as well as getters. Restore even on failure.
                    // The system uses this result for both the real view and fake animation.
                    try {
                        if (width > 0) maxWidth.setFloat(target, dpToPx(width))
                        chain.proceed()
                    } finally {
                        maxWidth.setFloat(target, original)
                    }
                }
            }
            hookedCalculation = true
            log(module) { "hooked per-notification width calculation" }
        } catch (_: ClassNotFoundException) {
        } catch (e: Exception) {
            logError(module, "failed to hook width calculation: ${e.message}")
        }
    }

    private fun hookBaseContentView(module: XposedModule, classLoader: ClassLoader) {
        try {
            val clazz = classLoader.loadClass(BASE_CONTENT_VIEW_CLASS)
            val access = runCatching { IslandWidthAccess(clazz) }.getOrNull()

            // 旧版 SystemUI 将双岛宽度计算放在 BaseContentView 中。
            if (!hookedLegacyCalculateMaxWidthWithSmall) {
                val calculateMaxWidthWithSmallMethod = clazz.declaredMethods.firstOrNull {
                    it.name == "calculateMaxWidthWithSmall" &&
                        it.returnType == Float::class.javaPrimitiveType
                }
                if (calculateMaxWidthWithSmallMethod != null) {
                    module.hook(calculateMaxWidthWithSmallMethod).intercept { chain ->
                        val maxWidthDp = scopedWidthForView(chain.thisObject, access)
                        if (maxWidthDp <= 0) {
                            return@intercept chain.proceed()
                        }

                        val maxWidthPx = dpToPx(maxWidthDp.coerceIn(1, 1000))

                        return@intercept maxWidthPx
                    }
                    hookedLegacyCalculateMaxWidthWithSmall = true
                    log(module) { "hooked legacy calculateMaxWidthWithSmall on $BASE_CONTENT_VIEW_CLASS" }
                }
            }

            // Keep setMaxWidth's native baseline and its update side effects intact.
            // Both real and fake views inherit this getter; no previous-app width is stored.
            if (!hookedWidthGetter) {
                val getter = clazz.getDeclaredMethod("getMaxWidth")
                module.hook(getter).intercept { chain ->
                    val width = scopedWidthForView(chain.thisObject, access)
                    if (width > 0) dpToPx(width) else chain.proceed()
                }
                hookedWidthGetter = true
            }
        } catch (_: ClassNotFoundException) {
        } catch (e: Exception) {
            logError(module, "failed to hook $BASE_CONTENT_VIEW_CLASS: ${e.message}")
        }
    }

    private fun hookModernMaxWidthWithSmall(module: XposedModule, classLoader: ClassLoader) {
        if (hookedModernCalculateMaxWidthWithSmall) return
        try {
            val clazz = classLoader.loadClass(PHONE_HELPER_CLASS)
            val method = clazz.declaredMethods.firstOrNull {
                it.name == "calculateMaxWidthWithSmall" &&
                    it.returnType == Float::class.javaPrimitiveType &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0].name ==
                        "miui.systemui.dynamicisland.model.IslandContentViewCalculationParams"
            } ?: return
            val paramsClass = method.parameterTypes[0]
            val getSmallIslandViewWidth = paramsClass.getMethod("getSmallIslandViewWidth")
            val getSpace = paramsClass.getMethod("getSpace")

            module.hook(method).intercept { chain ->
                val maxWidthDp = calculationScope.current() ?: 0
                if (maxWidthDp <= 0) {
                    return@intercept chain.proceed()
                }

                val params = chain.args.getOrNull(0) ?: return@intercept chain.proceed()
                val smallWidth = (getSmallIslandViewWidth.invoke(params) as Number).toInt()
                val space = (getSpace.invoke(params) as Number).toInt()
                // 新版方法限制的是大岛、小岛和间距的组合宽度。
                val combinedMaxWidth =
                    dpToPx(maxWidthDp.coerceIn(1, 1000)) + smallWidth + space

                combinedMaxWidth
            }
            hookedModernCalculateMaxWidthWithSmall = true
            log(module) { "hooked modern calculateMaxWidthWithSmall on $PHONE_HELPER_CLASS" }
        } catch (_: ClassNotFoundException) {
        } catch (e: Exception) {
            logError(module, "failed to hook calculateMaxWidthWithSmall on $PHONE_HELPER_CLASS: ${e.message}")
        }
    }

    private fun hookDynamicClassLoaders(module: XposedModule) {
        synchronized(registrationLock) {
            if (dynamicClassLoaderCallbackRegistered) return
            dynamicClassLoaderCallbackRegistered = true
        }
        HookUtils.hookDynamicClassLoaders(module, ClassLoader.getSystemClassLoader()) { cl ->
            hookContentViewClasses(module, cl)
        }
    }
}
