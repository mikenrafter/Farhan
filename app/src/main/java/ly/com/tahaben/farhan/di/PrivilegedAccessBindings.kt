package ly.com.tahaben.farhan.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ly.com.tahaben.notification_filter_data.service.NotificationService
import ly.com.tahaben.notification_filter_domain.delivery.Experiments
import ly.com.tahaben.notification_filter_domain.preferences.Preferences
import ly.com.tahaben.privileged_access_domain.GrayscaleReset
import ly.com.tahaben.privileged_access_domain.ListenerRebinder
import ly.com.tahaben.privileged_access_domain.OtpRedactionFlag
import ly.com.tahaben.screen_grayscale_domain.util.GrayscaleUtil
import javax.inject.Singleton

/** The three things the privileged-access connector needs from features it must not depend on. */
@Module
@InstallIn(SingletonComponent::class)
object PrivilegedAccessBindings {
    @Provides @Singleton
    fun provideListenerRebinder(@ApplicationContext context: Context): ListenerRebinder =
        ListenerRebinder { NotificationService.requestRestart(context) }

    @Provides @Singleton
    fun provideOtpRedactionFlag(preferences: Preferences): OtpRedactionFlag =
        OtpRedactionFlag { preferences.setExperimentEnabled(Experiments.OTP_REDACTION_SEEN, false) }

    @Provides @Singleton
    fun provideGrayscaleReset(grayscale: GrayscaleUtil): GrayscaleReset =
        GrayscaleReset { grayscale.setGrayscaleState(false) }
}
