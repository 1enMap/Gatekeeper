package app.gatekeeper.monitor

import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * What the forwarding engine does with each app's traffic. Apps not in any set (and not blocked)
 * are relayed directly, unfiltered; that only happens in live-monitor mode, because otherwise
 * such apps aren't in the tunnel at all.
 */
data class RelayPolicy(
    /** Dropped entirely (Blocked apps, and While open apps that aren't open). */
    val blocked: Set<String> = emptySet(),
    /** Relayed only through Tor; never directly (fail closed). */
    val tor: Set<String> = emptySet(),
    /** Connections to known tracker servers are refused. */
    val trackers: Set<String> = emptySet(),
    /** Real DNS servers for lookups apps send to the tunnel's own DNS address (direct apps only). */
    val directDns: List<InetSocketAddress> = emptyList(),
)

/** Addresses inside Gatekeeper's tunnel. Nothing real lives at them. */
object TunnelAddresses {
    const val ADDRESS_V4 = "10.111.222.1"
    const val ADDRESS_V6 = "fd00:1:2:3::1"
    const val DNS_V4 = "10.111.222.2"
    const val DNS_V6 = "fd00:1:2:3::2"

    /** The DNS addresses in InetAddress.hostAddress form, for comparisons. */
    val dns: Set<String> = setOf(DNS_V4, DNS_V6).mapTo(HashSet()) { InetAddress.getByName(it).hostAddress!! }
}
