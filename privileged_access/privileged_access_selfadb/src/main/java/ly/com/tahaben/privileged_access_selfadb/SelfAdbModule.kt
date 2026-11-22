package ly.com.tahaben.privileged_access_selfadb

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ly.com.tahaben.privileged_access_domain.SelfAdbRoute
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SelfAdbModule {
    @Provides
    @Singleton
    fun provideSelfAdbRoute(@ApplicationContext context: Context): SelfAdbRoute =
        AndroidSelfAdbRoute(context, AdbKeyFile(File(context.noBackupFilesDir, "privileged_access/adb_key")))
}
