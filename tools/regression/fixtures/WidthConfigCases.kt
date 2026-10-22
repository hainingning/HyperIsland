package audit

import android.content.MemoryPreferences
import io.github.libxposed.api.XposedModule
import io.github.hyperisland.xposed.ConfigManager
import io.github.hyperisland.xposed.hook.SystemUI.geometry.IslandWidthPolicy

fun main() {
    val prefs = MemoryPreferences()
    val pkg = "app.example_with_underscore"
    val key = "flutter.pref_app_config_$pkg"
    val original = """{"notification":{"max_width":"320"},"channels":{"settings":{"chat":{"max_width":"0"},"alerts_main":{"max_width":"280"}}}}"""
    prefs.values[key] = original
    prefs.values["flutter.pref_generic_whitelist"] = pkg
    prefs.values["flutter.pref_big_island_max_width"] = 200L
    ConfigManager.init(XposedModule(prefs))
    fun width(channel: String, source: String = pkg) = IslandWidthPolicy.resolve(
        ConfigManager.getChannelIslandWidth(source, channel),
        ConfigManager.getString("pref_notification_max_width_$source", "default"),
        ConfigManager.getInt("pref_big_island_max_width", 0))
    fun result(name: String, expected: Int, actual: Int) {
        println("""{"case":"$name","expected":$expected,"actual":$actual,"passed":${actual == expected}}""")
    }
    result("whitelisted_system_channel", 0, width("chat"))
    result("whitelisted_custom_channel", 280, width("alerts_main"))
    result("missing_channel_inherits_app", 320, width("missing"))
    prefs.put("flutter.pref_generic_whitelist", "")
    result("removed_whitelist_system_channel", 0, width("chat"))
    result("removed_whitelist_custom_channel_with_underscores", 280, width("alerts_main"))
    result("removed_whitelist_missing_channel", 320, width("missing"))
    prefs.put("flutter.pref_generic_whitelist", "app.example,$pkg,$pkg.child")
    result("reenabled_whitelist_system_channel", 0, width("chat"))
    result("reenabled_whitelist_custom_channel", 280, width("alerts_main"))
    prefs.put("flutter.pref_generic_whitelist", "")
    prefs.put("flutter.pref_channel_max_width_${pkg}_missing", "999")
    result("json_missing_field_does_not_revive_legacy", 320, width("missing"))
    prefs.put(key, """{"notification":{"max_width":"300"},"channels":{"settings":{"chat":{"max_width":null}}}}""")
    prefs.put("flutter.pref_channel_max_width_${pkg}_chat", "999")
    result("json_null_inherits_app_and_invalidates_cache", 300, width("chat"))
    for (value in listOf("default", "-1", "1001", "nope")) {
        prefs.put(key, """{"notification":{"max_width":"300"},"channels":{"settings":{"chat":{"max_width":"$value"}}}}""")
        result("invalid_or_inherited_channel_$value", 300, width("chat"))
    }
    prefs.put(key, """{"channels":{"settings":{"chat":{"max_width":280}}}}""")
    result("json_numeric_width", 280, width("chat"))
    result("app_absent_inherits_global", 200, width("missing"))
    prefs.put("flutter.pref_channel_max_width_${pkg}_", "999")
    result("empty_channel_ignores_legacy_trailing_separator", 200, width(""))
    prefs.put(key, "{}")
    result("empty_json_ignores_legacy", 200, width("chat"))
    prefs.put(key, "invalid json")
    result("invalid_json_uses_legacy", 999, width("chat"))
    prefs.remove(key)
    result("absent_json_uses_legacy", 999, width("chat"))
    prefs.remove("flutter.pref_channel_max_width_${pkg}_chat")
    result("legacy_deleted_inherits_global", 200, width("chat"))
    prefs.put(key, original)
    result("restored_json_replaces_legacy_cache", 0, width("chat"))
    val proxy = IslandWidthPolicy.source("com.android.systemui", "proxy", pkg, "alerts_main", "wrong")
    result("proxy_uses_source_package_and_channel", 280, width(proxy.channelId, proxy.packageName))
    result("other_app_never_uses_previous_app_width", 200, width("alerts_main", "app.other"))
    prefs.put("flutter.pref_generic_whitelist", pkg)
    prefs.put(key, """{"toast":{"marquee":"on","marquee_auto_hide":"off","island_outer_glow":"on","island_outer_glow_color":"123"},"notification":{"island_outer_glow":"off","island_outer_glow_color":"456"},"channels":{"settings":{"alerts_main":{"marquee":"off","marquee_auto_hide":"on","island_outer_glow":"on","island_outer_glow_color":"789"}}}}""")
    fun stringResult(name: String, expected: String, lookup: String) =
        result(name, 1, if (ConfigManager.getString(lookup, "missing") == expected) 1 else 0)
    stringResult("toast_longer_marquee_prefix", "off", "pref_toast_marquee_auto_hide_$pkg")
    stringResult("toast_shorter_marquee_prefix", "on", "pref_toast_marquee_$pkg")
    stringResult("toast_longer_glow_prefix", "123", "pref_toast_island_outer_glow_color_$pkg")
    stringResult("notification_longer_glow_prefix", "456", "pref_media_island_outer_glow_color_$pkg")
    stringResult("notification_shorter_glow_prefix", "off", "pref_media_island_outer_glow_$pkg")
    stringResult("channel_longer_marquee_prefix", "on", "pref_channel_marquee_auto_hide_${pkg}_alerts_main")
    stringResult("channel_shorter_marquee_prefix", "off", "pref_channel_marquee_${pkg}_alerts_main")
    stringResult("channel_longer_glow_prefix", "789", "pref_channel_island_outer_glow_color_${pkg}_alerts_main")
}
