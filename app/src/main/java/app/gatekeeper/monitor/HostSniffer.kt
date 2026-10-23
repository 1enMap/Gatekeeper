package app.gatekeeper.monitor

import java.net.InetAddress

/**
 * Finds the server name in the first bytes an app sends: the SNI of a TLS ClientHello (sent in
 * clear text even for HTTPS) or the Host header of plain HTTP. Contents are never stored.
 */
object HostSniffer {

    fun hostOf(data: ByteArray, offset: Int, length: Int): String? =
        tlsServerName(data, offset, length) ?: httpHost(data, offset, length)

    fun tlsServerName(b: ByteArray, off: Int, len: Int): String? {
        val end = off + len
        // TLS record: type 22 (handshake), version 3.x, length; then handshake type 1 (ClientHello).
        if (len < 43 || b[off].toInt() != 22 || b[off + 1].toInt() != 3 || b[off + 5].toInt() != 1) return null
        var i = off + 5 + 4 // Handshake header.
        i += 2 + 32 // client_version + random
        if (i >= end) return null
        i += 1 + (b[i].toInt() and 0xFF) // session_id
        if (i + 2 > end) return null
        i += 2 + IpPacket.u16(b, i) // cipher_suites
        if (i >= end) return null
        i += 1 + (b[i].toInt() and 0xFF) // compression_methods
        if (i + 2 > end) return null
        val extEnd = minOf(end, i + 2 + IpPacket.u16(b, i))
        i += 2
        while (i + 4 <= extEnd) {
            val type = IpPacket.u16(b, i)
            val extLen = IpPacket.u16(b, i + 2)
            i += 4
            if (type == 0 && i + 5 <= extEnd) { // server_name
                // list length (2), name type (1) == 0 host_name, name length (2), name
                if (b[i + 2].toInt() != 0) return null
                val nameLen = IpPacket.u16(b, i + 3)
                val start = i + 5
                if (nameLen == 0 || start + nameLen > extEnd) return null
                return cleanHost(String(b, start, nameLen, Charsets.US_ASCII))
            }
            i += extLen
        }
        return null
    }

    fun httpHost(b: ByteArray, off: Int, len: Int): String? {
        val text = String(b, off, minOf(len, 2048), Charsets.ISO_8859_1)
        val firstSpace = text.indexOf(' ')
        if (firstSpace !in 3..7 || text.substring(0, firstSpace) !in HTTP_METHODS) return null
        for (line in text.split("\r\n")) {
            if (line.startsWith("Host:", ignoreCase = true)) {
                return cleanHost(line.substring(5).trim().substringBefore(':'))
            }
        }
        return null
    }

    /**
     * True while [data] looks like the start of a TLS ClientHello or an HTTP request that hasn't
     * fully arrived, so the server name may still come. False means "this is all we'll learn".
     */
    fun mayBeIncomplete(data: ByteArray, off: Int, len: Int): Boolean {
        if (len <= 0) return true
        if (data[off].toInt() == 22) { // TLS handshake record.
            if (len < 5) return true
            return len < 5 + IpPacket.u16(data, off + 3)
        }
        val text = String(data, off, minOf(len, 2048), Charsets.ISO_8859_1)
        val looksHttp = HTTP_METHODS.any { m -> text.startsWith("$m ") || "$m ".startsWith(text) }
        return looksHttp && !text.contains("\r\n\r\n")
    }

    private val HTTP_METHODS = setOf("GET", "POST", "PUT", "HEAD", "DELETE", "OPTIONS", "PATCH", "CONNECT")

    private fun cleanHost(host: String): String? =
        host.lowercase().trimEnd('.').takeIf { h -> h.isNotEmpty() && h.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' } }
}

/** Reads A/AAAA answers from DNS responses so plain connections to an IP can be named. */
object DnsAnswers {

    /** Returns (address, name) pairs; the name is the question name the app asked for. */
    fun parse(b: ByteArray, off: Int, len: Int): List<Pair<InetAddress, String>> {
        val end = off + len
        if (len < 12) return emptyList()
        if (b[off + 2].toInt() and 0x80 == 0) return emptyList() // Not a response.
        val questions = IpPacket.u16(b, off + 4)
        val answers = IpPacket.u16(b, off + 6)
        var i = off + 12
        var question: String? = null
        repeat(questions) {
            val (name, next) = readName(b, off, i, end) ?: return emptyList()
            if (question == null) question = name
            i = next + 4
        }
        val asked = question ?: return emptyList()
        val result = ArrayList<Pair<InetAddress, String>>()
        repeat(answers) {
            val (_, next) = readName(b, off, i, end) ?: return result
            i = next
            if (i + 10 > end) return result
            val type = IpPacket.u16(b, i)
            val rdLen = IpPacket.u16(b, i + 8)
            i += 10
            if (i + rdLen > end) return result
            if ((type == 1 && rdLen == 4) || (type == 28 && rdLen == 16)) {
                runCatching { InetAddress.getByAddress(b.copyOfRange(i, i + rdLen)) }.getOrNull()
                    ?.let { result += it to asked }
            }
            i += rdLen
        }
        return result
    }

    /**
     * A "no such domain" (NXDOMAIN) answer to a DNS query, echoing its ID and question, or null
     * if the query can't be parsed. Used to refuse lookups of tracker servers.
     */
    fun nxdomain(b: ByteArray, off: Int, len: Int): ByteArray? {
        if (len < 13 || b[off + 2].toInt() and 0x80 != 0 || IpPacket.u16(b, off + 4) != 1) return null
        val (_, afterName) = readName(b, off, off + 12, off + len) ?: return null
        val end = afterName + 4 // QTYPE + QCLASS
        if (end > off + len) return null
        val out = b.copyOfRange(off, end)
        out[2] = (0x80 or (out[2].toInt() and 0x79)).toByte() // QR=1, keep opcode and RD.
        out[3] = (0x80 or 3).toByte() // RA=1, RCODE=3 (NXDOMAIN).
        for (i in 6 until 12) out[i] = 0 // No answer, authority or additional records.
        return out
    }

    /** The name asked for in a DNS query, or null. */
    fun question(b: ByteArray, off: Int, len: Int): String? {
        if (len < 13 || b[off + 2].toInt() and 0x80 != 0 || IpPacket.u16(b, off + 4) == 0) return null
        return readName(b, off, off + 12, off + len)?.first?.takeIf { it.isNotEmpty() }
    }

    /** Reads a possibly compressed name; returns it and the offset after it in the record. */
    private fun readName(b: ByteArray, msg: Int, start: Int, end: Int): Pair<String, Int>? {
        val sb = StringBuilder()
        var i = start
        var after = -1
        var jumps = 0
        while (true) {
            if (i >= end) return null
            val len = b[i].toInt() and 0xFF
            when {
                len == 0 -> {
                    if (after < 0) after = i + 1
                    return sb.toString().lowercase() to after
                }
                len and 0xC0 == 0xC0 -> {
                    if (i + 1 >= end || ++jumps > 16) return null
                    if (after < 0) after = i + 2
                    i = msg + (((len and 0x3F) shl 8) or (b[i + 1].toInt() and 0xFF))
                }
                else -> {
                    if (i + 1 + len > end || sb.length > 255) return null
                    if (sb.isNotEmpty()) sb.append('.')
                    sb.append(String(b, i + 1, len, Charsets.US_ASCII))
                    i += 1 + len
                }
            }
        }
    }
}
