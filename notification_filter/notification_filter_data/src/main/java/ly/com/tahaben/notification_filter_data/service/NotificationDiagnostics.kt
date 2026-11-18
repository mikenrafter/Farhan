package ly.com.tahaben.notification_filter_data.service

import android.os.Build
import android.os.Trace
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Named, local counters and trace sections only. Never pass notification contents or identifiers. */
object NotificationDiagnostics {
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    fun increment(name: String) = counter(name, counters.getOrPut(name) { AtomicLong() }.incrementAndGet())
    fun counter(name: String, count: Long) { if (Build.VERSION.SDK_INT >= 29) Trace.setCounter(name, count) }
    fun counter(name: String, count: Int) = counter(name, count.toLong())
    suspend fun <T> suspending(name: String, block: suspend () -> T): T {
        val cookie = nextCookie.incrementAndGet()
        if (Build.VERSION.SDK_INT >= 29) Trace.beginAsyncSection(name, cookie)
        return try { block() } finally { if (Build.VERSION.SDK_INT >= 29) Trace.endAsyncSection(name, cookie) }
    }
    private val nextCookie = java.util.concurrent.atomic.AtomicInteger()
    inline fun <T> trace(name: String, block: () -> T): T {
        Trace.beginSection(name)
        return try { block() } finally { Trace.endSection() }
    }
}
