package ly.com.tahaben.notification_filter_presentation.apppriority

import ly.com.tahaben.notification_filter_domain.model.AppPriorityInfo
import ly.com.tahaben.notification_filter_domain.model.NotificationCategory
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

internal sealed interface AppFilter {
    data object All : AppFilter
    data object HasNotifications : AppFilter
    data class Category(val category: NotificationCategory) : AppFilter
}

internal class AppListCatalog(apps: List<AppPriorityInfo>, private val locale: Locale) {
    private val collation = Collator.getInstance(locale).apply {
        strength = Collator.SECONDARY
        decomposition = Collator.CANONICAL_DECOMPOSITION
    }
    private val sectionCollation = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
    private val sections = apps
        .sortedWith(compareBy(collation, AppPriorityInfo::appName).thenBy(AppPriorityInfo::packageName))
        .groupBy { sectionLetter(it.appName) }
        .entries
        .sortedWith(compareBy { it.key == "#" })
        .map { (letter, rows) -> AppSection(letter, rows) }
    private val total = apps.size
    private val notifications = apps.count(AppPriorityInfo::notificationsEnabled)
    private val categories = apps.groupingBy(AppPriorityInfo::resolvedCategory).eachCount()

    fun count(filter: AppFilter): Int = when (filter) {
        AppFilter.All -> total
        AppFilter.HasNotifications -> notifications
        is AppFilter.Category -> categories[filter.category] ?: 0
    }

    fun filter(filter: AppFilter, query: String): AppList {
        val search = query.trim()
        val matches = sections.mapNotNull { section ->
            val apps = section.apps.filter { app ->
                val matchesFilter = when (filter) {
                    AppFilter.All -> true
                    AppFilter.HasNotifications -> app.notificationsEnabled
                    is AppFilter.Category -> app.resolvedCategory == filter.category
                }
                matchesFilter && (app.appName.contains(search, ignoreCase = true) ||
                    app.packageName.contains(search, ignoreCase = true))
            }
            if (apps.isEmpty()) null else section.copy(apps = apps)
        }
        return AppList(matches)
    }

    private fun sectionLetter(name: String): String {
        val label = Normalizer.normalize(name.trimStart(), Normalizer.Form.NFC)
        if (label.isEmpty() || !Character.isLetter(label.codePointAt(0))) return "#"
        val initial = label.substring(0, label.offsetByCodePoints(0, 1))
        val decomposed = Normalizer.normalize(initial, Normalizer.Form.NFD)
        val base = decomposed.substring(0, decomposed.offsetByCodePoints(0, 1))
        val letter = if (sectionCollation.compare(initial, base) == 0) base else initial
        return letter.uppercase(locale)
    }
}

internal data class AppSection(val letter: String, val apps: List<AppPriorityInfo>)

internal class AppList(val sections: List<AppSection>) {
    // CLAIM(app-rail-index): each preceding section contributes one header and all of its rows
    // [AppListTest.railTargetsFirstRowOfEachRemainingSection].
    val firstRowByLetter: Map<String, Int> = buildMap {
        var headerIndex = 0
        sections.forEach { section ->
            put(section.letter, headerIndex + 1)
            headerIndex += 1 + section.apps.size
        }
    }
}
