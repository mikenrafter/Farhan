package ly.com.tahaben.notification_filter_domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NotificationCategoryResolverTest {

    @Test
    fun `app store game maps to entertainment`() {
        val category = NotificationCategoryResolver.fromAppStoreCategory(AppStoreCategory.GAME)
        assertThat(category).isEqualTo(NotificationCategory.ENTERTAINMENT)
        assertThat(category.defaultPriority).isEqualTo(NotificationPriority.LOW)
    }

    @Test
    fun `app store social maps to social`() {
        assertThat(NotificationCategoryResolver.fromAppStoreCategory(AppStoreCategory.SOCIAL))
            .isEqualTo(NotificationCategory.SOCIAL)
    }

    @Test
    fun `resolve prefers notification categories over app category`() {
        val category = NotificationCategoryResolver.resolve(
            primaryAndroidCategory = null,
            knownAndroidCategories = setOf("msg"),
            appCategoryCode = AppStoreCategory.GAME,
        )
        assertThat(category).isEqualTo(NotificationCategory.COMMUNICATION)
    }

    @Test
    fun `resolve falls back to app category when no notification categories`() {
        val category = NotificationCategoryResolver.resolve(
            primaryAndroidCategory = null,
            knownAndroidCategories = emptySet(),
            appCategoryCode = AppStoreCategory.VIDEO,
        )
        assertThat(category).isEqualTo(NotificationCategory.ENTERTAINMENT)
    }
}
