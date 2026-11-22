package ly.com.tahaben.privileged_access_data

import android.content.Context
import android.content.SharedPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ly.com.tahaben.privileged_access_data.shizuku.AndroidShizukuRoute
import ly.com.tahaben.privileged_access_domain.DefaultPrivilegedAccess
import ly.com.tahaben.privileged_access_domain.GrayscaleReset
import ly.com.tahaben.privileged_access_domain.ListenerRebinder
import ly.com.tahaben.privileged_access_domain.OtpRedactionFlag
import ly.com.tahaben.privileged_access_domain.PhoneFacts
import ly.com.tahaben.privileged_access_domain.PrivilegedAccess
import ly.com.tahaben.privileged_access_domain.PrivilegedPreferences
import ly.com.tahaben.privileged_access_domain.SelfAdbRoute
import ly.com.tahaben.privileged_access_domain.ShizukuRoute
import javax.inject.Singleton

/**
 * Needs three bindings from the app (it knows the notification listener, the OTP diagnostic and Grayscale):
 * [ListenerRebinder], [OtpRedactionFlag], [GrayscaleReset]; and [SelfAdbRoute] from `privileged_access_selfadb`.
 */
@Module
@InstallIn(SingletonComponent::class)
object PrivilegedAccessModule {
    @Provides @Singleton
    fun providePhoneFacts(@ApplicationContext context: Context): PhoneFacts = AndroidPhoneFacts(context)

    @Provides @Singleton
    fun providePrivilegedPreferences(preferences: SharedPreferences): PrivilegedPreferences =
        SharedPreferencesPrivilegedPreferences(preferences)

    @Provides @Singleton
    fun provideShizukuRoute(@ApplicationContext context: Context): ShizukuRoute = AndroidShizukuRoute(context)

    @Provides @Singleton
    fun providePrivilegedAccess(
        @ApplicationContext context: Context,
        phone: PhoneFacts,
        selfAdb: SelfAdbRoute,
        shizuku: ShizukuRoute,
        preferences: PrivilegedPreferences,
        rebinder: ListenerRebinder,
        redaction: OtpRedactionFlag,
        grayscale: GrayscaleReset,
    ): PrivilegedAccess = DefaultPrivilegedAccess(context.packageName, phone, selfAdb, shizuku, preferences, rebinder, redaction, grayscale)
}
