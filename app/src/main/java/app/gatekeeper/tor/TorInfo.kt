package app.gatekeeper.tor

/** Parsers for Tor control-port GETINFO answers. Pure, so they are unit-tested. */
object TorInfo {
    private val PROGRESS = Regex("""PROGRESS=(\d+)""")
    private val SUMMARY = Regex("""SUMMARY="([^"]*)"""")

    /**
     * `status/bootstrap-phase`, e.g. `NOTICE BOOTSTRAP PROGRESS=45 TAG=loading_descriptors
     * SUMMARY="Loading relay descriptors"`, as (percent, summary). Null if unparseable.
     */
    fun bootstrap(phase: String?): Pair<Int, String>? {
        if (phase == null) return null
        val progress = PROGRESS.find(phase)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val summary = SUMMARY.find(phase)?.groupValues?.get(1).orEmpty()
        return progress.coerceIn(0, 100) to summary
    }

    /**
     * Port of the first listener in `net/listeners/socks` or `net/listeners/dns`, whose value
     * looks like `"127.0.0.1:9050"` (quoted, space-separated if several). 0 if there is none.
     */
    fun firstPort(listeners: String?): Int {
        val first = listeners?.trim()?.split(' ')?.firstOrNull()?.trim('"') ?: return 0
        if (first.startsWith("unix:")) return 0
        return first.substringAfterLast(':', "").toIntOrNull() ?: 0
    }

    /** The torrc Gatekeeper writes before every start. */
    fun torrc(): String = """
        |# Written by Gatekeeper every time Tor mode starts; edits here are overwritten.
        |# Client only: this device never relays other people's traffic.
        |SocksPort auto IPv6Traffic
        |DNSPort auto
        |HTTPTunnelPort 0
        |# Lets apps reach .onion addresses: Tor answers DNS with a placeholder address and maps it back.
        |AutomapHostsOnResolve 1
        |VirtualAddrNetworkIPv4 10.192.0.0/10
        |AvoidDiskWrites 1
        |""".trimMargin()
}
