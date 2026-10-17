package edu.fudan.elearning.sync.ui

enum class ThemeMode(val preference: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
}

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val syncInterval: Int = 15,
    val backgroundSync: Boolean = true,
    val passwordRemembered: Boolean = false,
)

enum class LibraryTab {
    COURSES,
    ACTIVITY,
    STORAGE,
    SETTINGS,
}

enum class FileSort {
    UNREAD,
    NAME,
    UPDATED,
}
