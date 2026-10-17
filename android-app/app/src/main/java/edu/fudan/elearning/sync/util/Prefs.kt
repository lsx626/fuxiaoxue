package edu.fudan.elearning.sync.util

import android.content.Context
import android.content.SharedPreferences

/** 应用配置存取（非敏感项）。 */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("fudan_sync", Context.MODE_PRIVATE)

    var username: String
        get() = sp.getString("username", "") ?: ""
        set(value) = sp.edit().putString("username", value).apply()

    var syncIntervalMinutes: Int
        get() = sp.getInt("sync_interval_minutes", 15)
        set(value) = sp.edit().putInt("sync_interval_minutes", value).apply()

    var onlyFavorites: Boolean
        get() = sp.getBoolean("only_favorites", false)
        set(value) = sp.edit().putBoolean("only_favorites", value).apply()

    var backgroundSyncEnabled: Boolean
        get() = sp.getBoolean("background_sync_enabled", true)
        set(value) = sp.edit().putBoolean("background_sync_enabled", value).apply()

    var themeMode: String
        get() = sp.getString("theme_mode", "system") ?: "system"
        set(value) = sp.edit().putString("theme_mode", value).apply()

    var notificationPrompted: Boolean
        get() = sp.getBoolean("notification_prompted", false)
        set(value) = sp.edit().putBoolean("notification_prompted", value).apply()

    var backgroundError: String
        get() = sp.getString("background_error", "") ?: ""
        set(value) = sp.edit().putString("background_error", value).apply()

    var loggedIn: Boolean
        get() = sp.getBoolean("logged_in", false)
        set(value) = sp.edit().putBoolean("logged_in", value).apply()

    var lastSyncAt: Long
        get() = sp.getLong("last_sync_at", 0)
        set(value) = sp.edit().putLong("last_sync_at", value).apply()

    /**
     * 某课程**未启用**的内容来源（Canvas 对关闭的标签页返回 404）。
     *
     * 记下来后下次同步直接跳过，避免每轮都白发一轮请求（既慢又刷错误提示）。 用户在 Canvas 上重新开启该功能后，用「全量同步」可清空这些标记。
     */
    fun disabledSources(courseId: Long): Set<String> =
        sp.getStringSet(keyForCourse(courseId), emptySet()) ?: emptySet()

    fun markSourceDisabled(courseId: Long, source: String) {
        val current = disabledSources(courseId).toMutableSet()
        if (current.add(source)) {
            sp.edit().putStringSet(keyForCourse(courseId), current).apply()
        }
    }

    fun clearDisabledSources() {
        sp.edit()
            .apply { sp.all.keys.filter { it.startsWith(DISABLED_PREFIX) }.forEach { remove(it) } }
            .apply()
    }

    private fun keyForCourse(courseId: Long) = "$DISABLED_PREFIX$courseId"

    private companion object {
        const val DISABLED_PREFIX = "disabled_sources_"
    }

    fun clear() {
        sp.edit().clear().apply()
    }
}
