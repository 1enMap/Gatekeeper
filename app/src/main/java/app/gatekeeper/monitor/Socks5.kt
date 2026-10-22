package app.gatekeeper.monitor

/**
 * The client side of SOCKS5 (RFC 1928) with username/password auth (RFC 1929), as much as the
 * forwarder needs to open a connection through Tor. Pure functions, so they are unit-tested.
 *
 * The exchange is: greeting → method reply (2 bytes) → auth → auth reply (2 bytes) →
 * CONNECT → connect reply (variable length, see [replyLength]).
 */
object Socks5 {
    const val VERSION = 5
    const val METHOD_USERPASS = 2
    const val CMD_CONNECT = 1
    const val ATYP_IPV4 = 1
    const val ATYP_DOMAIN = 3
    const val ATYP_IPV6 = 4

    /** "I support username/password auth only." */
    fun greeting(): ByteArray = byteArrayOf(VERSION.toByte(), 1, METHOD_USERPASS.toByte())

    /** True if the server picked username/password auth. */
    fun methodAccepted(reply: ByteArray): Boolean =
        reply.size >= 2 && reply[0].toInt() == VERSION && reply[1].toInt() == METHOD_USERPASS

    fun auth(username: String, password: String): ByteArray {
        val u = username.toByteArray(Charsets.UTF_8).let { if (it.size > 255) it.copyOf(255) else it }
        val p = password.toByteArray(Charsets.UTF_8).let { if (it.size > 255) it.copyOf(255) else it }
        val out = ByteArray(3 + u.size + p.size)
        out[0] = 1 // Sub-negotiation version.
        out[1] = u.size.toByte()
        u.copyInto(out, 2)
        out[2 + u.size] = p.size.toByte()
        p.copyInto(out, 3 + u.size)
        return out
    }

    fun authAccepted(reply: ByteArray): Boolean = reply.size >= 2 && reply[1].toInt() == 0

    /** CONNECT to a raw IPv4 (4 bytes) or IPv6 (16 bytes) address. */
    fun connect(address: ByteArray, port: Int): ByteArray {
        val atyp = when (address.size) {
            4 -> ATYP_IPV4
            16 -> ATYP_IPV6
            else -> throw IllegalArgumentException("address must be 4 or 16 bytes")
        }
        val out = ByteArray(6 + address.size)
        out[0] = VERSION.toByte()
        out[1] = CMD_CONNECT.toByte()
        out[2] = 0
        out[3] = atyp.toByte()
        address.copyInto(out, 4)
        out[4 + address.size] = (port ushr 8).toByte()
        out[5 + address.size] = port.toByte()
        return out
    }

    /**
     * Total length of a connect reply, given at least its first 5 bytes, or -1 if the address
     * type is unknown. Reading exactly this many bytes matters: anything after the reply is
     * already the remote server's data.
     */
    fun replyLength(head: ByteArray): Int {
        require(head.size >= 5)
        return when (head[3].toInt()) {
            ATYP_IPV4 -> 4 + 4 + 2
            ATYP_IPV6 -> 4 + 16 + 2
            ATYP_DOMAIN -> 4 + 1 + (head[4].toInt() and 0xFF) + 2
            else -> -1
        }
    }

    /** Reply code of a connect reply: 0 is success. */
    fun replyCode(reply: ByteArray): Int = if (reply.size >= 2 && reply[0].toInt() == VERSION) reply[1].toInt() and 0xFF else -1

    /** Human-readable reason for a non-zero reply code (Tor uses the standard ones). */
    fun describe(code: Int): String = when (code) {
        1 -> "general failure"
        2 -> "not allowed"
        3 -> "network unreachable"
        4 -> "host unreachable"
        5 -> "connection refused"
        6 -> "TTL expired (Tor circuit timed out)"
        7 -> "command not supported"
        8 -> "address type not supported"
        else -> "error $code"
    }
}
