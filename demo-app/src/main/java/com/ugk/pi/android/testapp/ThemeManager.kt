package com.ugk.pi.android.testapp

import android.content.Context
import android.content.res.Configuration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 界面主题模式。
 */
enum class AppThemeMode(val key: String, val displayName: String, val icon: String) {
    LIGHT("light", "浅色", "☀️"),
    DARK("dark", "深色", "🌙"),
    SYSTEM("system", "跟随系统", "📱");

    companion object {
        fun fromKey(key: String?): AppThemeMode =
            values().firstOrNull { it.key == key } ?: SYSTEM
    }
}

/**
 * 主题偏好持久化存储。
 */
class ThemeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getThemeMode(): AppThemeMode {
        val key = prefs.getString(KEY_THEME_MODE, AppThemeMode.SYSTEM.key)
        return AppThemeMode.fromKey(key)
    }

    fun setThemeMode(mode: AppThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.key).apply()
    }

    fun isDark(context: Context): Boolean {
        return when (getThemeMode()) {
            AppThemeMode.LIGHT -> false
            AppThemeMode.DARK -> true
            AppThemeMode.SYSTEM -> isSystemInDarkMode(context)
        }
    }

    private fun isSystemInDarkMode(context: Context): Boolean {
        val nightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightMode == Configuration.UI_MODE_NIGHT_YES
    }

    private companion object {
        const val PREFS_NAME = "demo_theme_prefs"
        const val KEY_THEME_MODE = "app_theme_mode"
    }
}

/**
 * 全局主题管理器，负责主题响应式状态同步与通知。
 */
object ThemeManager {
    private var store: ThemeStore? = null
    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    var currentMode: AppThemeMode = AppThemeMode.SYSTEM
        private set

    var isDark: Boolean = false
        private set

    fun init(context: Context) {
        val currentStore = store ?: ThemeStore(context).also { store = it }
        currentMode = currentStore.getThemeMode()
        isDark = currentStore.isDark(context)
    }

    fun setMode(context: Context, mode: AppThemeMode) {
        val currentStore = store ?: ThemeStore(context).also { store = it }
        currentStore.setThemeMode(mode)
        currentMode = mode
        val newIsDark = currentStore.isDark(context)
        val changed = newIsDark != isDark
        isDark = newIsDark
        listeners.forEach { it(isDark) }
    }

    fun toggle(context: Context): AppThemeMode {
        val nextMode = when (currentMode) {
            AppThemeMode.LIGHT -> AppThemeMode.DARK
            AppThemeMode.DARK -> AppThemeMode.SYSTEM
            AppThemeMode.SYSTEM -> AppThemeMode.LIGHT
        }
        setMode(context, nextMode)
        return nextMode
    }

    fun addListener(listener: (Boolean) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }
}
