package com.inknironapps.libraryiq.ui.screens.library

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class LibraryLayout(val label: String) {
    LIST("List"),
    GRID("Grid")
}

@Singleton
class LibraryPreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("library_display_prefs", Context.MODE_PRIVATE)

    var layout: LibraryLayout
        get() = try {
            LibraryLayout.valueOf(prefs.getString(KEY_LAYOUT, LibraryLayout.LIST.name)!!)
        } catch (_: Exception) { LibraryLayout.LIST }
        set(value) = prefs.edit().putString(KEY_LAYOUT, value.name).apply()

    var gridColumns: Int
        get() = prefs.getInt(KEY_GRID_COLUMNS, 3)
        set(value) = prefs.edit().putInt(KEY_GRID_COLUMNS, value).apply()

    var defaultSort: SortOption
        get() = try {
            SortOption.valueOf(prefs.getString(KEY_DEFAULT_SORT, SortOption.TITLE_ASC.name)!!)
        } catch (_: Exception) { SortOption.TITLE_ASC }
        set(value) = prefs.edit().putString(KEY_DEFAULT_SORT, value.name).apply()

    var defaultGroup: GroupOption
        get() = try {
            GroupOption.valueOf(prefs.getString(KEY_DEFAULT_GROUP, GroupOption.NONE.name)!!)
        } catch (_: Exception) { GroupOption.NONE }
        set(value) = prefs.edit().putString(KEY_DEFAULT_GROUP, value.name).apply()

    var showCovers: Boolean
        get() = prefs.getBoolean(KEY_SHOW_COVERS, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_COVERS, value).apply()

    var compactList: Boolean
        get() = prefs.getBoolean(KEY_COMPACT_LIST, false)
        set(value) = prefs.edit().putBoolean(KEY_COMPACT_LIST, value).apply()

    companion object {
        private const val KEY_LAYOUT = "layout"
        private const val KEY_GRID_COLUMNS = "grid_columns"
        private const val KEY_DEFAULT_SORT = "default_sort"
        private const val KEY_DEFAULT_GROUP = "default_group"
        private const val KEY_SHOW_COVERS = "show_covers"
        private const val KEY_COMPACT_LIST = "compact_list"
    }
}
