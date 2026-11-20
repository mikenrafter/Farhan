package ly.com.tahaben.notification_filter_data.sound

import android.content.Context
import ly.com.tahaben.notification_filter_domain.sound.BreakthroughChannel
import ly.com.tahaben.notification_filter_domain.sound.BreakthroughChannelPort

internal class AndroidBreakthroughChannel(private val context: Context) : BreakthroughChannelPort {
    override fun create() = Unit
    override fun read(): BreakthroughChannel? = null
}
