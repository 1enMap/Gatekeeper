package app.gatekeeper.monitor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One connection seen by the live monitor, as shown in the UI. */
data class LiveEntry(
    val id: Long,
    val startMillis: Long,
    val lastMillis: Long,
    val packageName: String?,
    /** "TCP", "UDP", "DNS" or "QUIC". */
    val protocol: String,
    /** Server name from TLS/HTTP/DNS, if known. */
    val host: String?,
    val address: String,
    val port: Int,
    val bytesOut: Long,
    val bytesIn: Long,
    val open: Boolean,
    val blocked: Boolean,
)

/** Process-wide live view, published by the forwarding engine a couple of times per second. */
object LiveMonitor {
    private val _entries = MutableStateFlow<List<LiveEntry>>(emptyList())
    val entries: StateFlow<List<LiveEntry>> = _entries.asStateFlow()

    fun publish(entries: List<LiveEntry>) {
        _entries.value = entries
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
