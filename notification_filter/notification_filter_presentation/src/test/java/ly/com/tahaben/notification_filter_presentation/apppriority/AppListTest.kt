package ly.com.tahaben.notification_filter_presentation.apppriority

import ly.com.tahaben.notification_filter_domain.model.AppPriorityInfo
import ly.com.tahaben.notification_filter_domain.model.NotificationCategory
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class AppListTest {
    // why: discovery order and notification availability must not partition the alphabet.
    // catches: sorting enabled apps first, or retaining input order for equal names.
    @Test fun sortsByNameThenPackageAndPutsSymbolsAndDigitsLast() {
        val list = catalog(
            app("zeta"), app("8tracks"), app("alpha", "z", enabled = false),
            app("Alpha", "a"), app("Éclair"), app("!Clock"),
        ).filter(AppFilter.All, "")
        assertEquals(listOf("A", "E", "Z", "#"), list.sections.map { it.letter })
        assertEquals(
            listOf("a", "z", "Éclair", "zeta", "!Clock", "8tracks"),
            list.sections.flatMap { section -> section.apps.map { it.packageName } },
        )
    }

    // why: letter accents that belong to the locale's alphabet retain their own section.
    // catches: stripping all combining marks or sorting using the host locale.
    @Test fun followsSwedishAlphabet() {
        val list = AppListCatalog(listOf(app("Öl"), app("Åland"), app("Zulu"), app("Äpple")), Locale("sv"))
            .filter(AppFilter.All, "")
        assertEquals(listOf("Z", "Å", "Ä", "Ö"), list.sections.map { it.letter })
    }

    // why: a query narrows the selected category, and matches display names or package names.
    // catches: OR-ing the two controls, matching case-sensitively, or ignoring package names.
    @Test fun combinesCategoryAndTrimmedSearch() {
        val list = catalog(
            app("Beeper", "social.beeper", NotificationCategory.SOCIAL),
            app("Cozy", "social.cozy", NotificationCategory.SOCIAL),
            app("Social Bank", category = NotificationCategory.FINANCE),
            app("Fedi", category = NotificationCategory.SOCIAL),
        )
        val filter = AppFilter.Category(NotificationCategory.SOCIAL)
        assertEquals(listOf("B", "C"), list.filter(filter, " SOCIAL ").sections.map { it.letter })
        assertEquals(listOf("Beeper"), list.filter(filter, "eeP").sections.flatMap { it.apps }.map { it.appName })
        assertEquals(emptyList<String>(), list.filter(filter, "bank").sections.map { it.letter })
    }

    // why: rail destinations count each preceding header and use the filtered rows.
    // catches: using catalog letters or row-only indices after filtering.
    @Test fun railTargetsFirstRowOfEachRemainingSection() {
        val list = catalog(app("A map"), app("Beta", enabled = true), app("Bolt"), app("Dot", enabled = true))
            .filter(AppFilter.HasNotifications, "")
        assertEquals(linkedMapOf("B" to 1, "D" to 3), list.firstRowByLetter)
        assertEquals(listOf("B", "D"), list.firstRowByLetter.keys.toList())
    }

    // why: chip counts describe the whole catalog and categories may have no apps.
    // catches: counting only visible matches or treating all apps as notification-enabled.
    @Test fun countsFullCatalogIncludingEmptyCategories() {
        val list = catalog(
            app("Fedi", category = NotificationCategory.SOCIAL, enabled = true),
            app("Beeper", category = NotificationCategory.SOCIAL),
            app("Bank", category = NotificationCategory.FINANCE, enabled = true),
        )
        assertEquals(3, list.count(AppFilter.All))
        assertEquals(2, list.count(AppFilter.HasNotifications))
        assertEquals(2, list.count(AppFilter.Category(NotificationCategory.SOCIAL)))
        assertEquals(1, list.count(AppFilter.Category(NotificationCategory.FINANCE)))
        assertEquals(0, list.count(AppFilter.Category(NotificationCategory.HEALTH)))
    }

    private fun catalog(vararg apps: AppPriorityInfo) = AppListCatalog(apps.toList(), Locale.US)

    private fun app(
        name: String,
        packageName: String = name,
        category: NotificationCategory = NotificationCategory.OTHER,
        enabled: Boolean = false,
    ) = AppPriorityInfo(
        packageName = packageName,
        appName = name,
        currentPriority = NotificationPriority.MEDIUM,
        defaultPriority = NotificationPriority.MEDIUM,
        resolvedCategory = category,
        priorityReason = "",
        appCategoryLabel = null,
        androidNotificationCategoryLabels = emptyList(),
        isLocked = false,
        userOverride = null,
        channels = emptyList(),
        notificationsEnabled = enabled,
    )
}
