package app.gatekeeper.monitor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Socks5Test {
    @Test
    fun greetingOffersUsernamePasswordOnly() {
        assertArrayEquals(byteArrayOf(5, 1, 2), Socks5.greeting())
        assertTrue(Socks5.methodAccepted(byteArrayOf(5, 2)))
        assertFalse(Socks5.methodAccepted(byteArrayOf(5, 0)))
        assertFalse(Socks5.methodAccepted(byteArrayOf(5, 0xFF.toByte())))
    }

    @Test
    fun authCarriesUserAndPassword() {
        val a = Socks5.auth("com.app", "pw")
        assertArrayEquals(byteArrayOf(1, 7) + "com.app".toByteArray() + byteArrayOf(2) + "pw".toByteArray(), a)
        assertTrue(Socks5.authAccepted(byteArrayOf(1, 0)))
        assertFalse(Socks5.authAccepted(byteArrayOf(1, 1)))
    }

    @Test
    fun longUsernameIsCappedAt255Bytes() {
        val a = Socks5.auth("x".repeat(300), "p")
        assertEquals(255, a[1].toInt() and 0xFF)
        assertEquals(3 + 255 + 1, a.size)
    }

    @Test
    fun connectEncodesIpv4AndIpv6() {
        assertArrayEquals(
            byteArrayOf(5, 1, 0, 1, 1, 2, 3, 4, 0x01, 0xBB.toByte()),
            Socks5.connect(byteArrayOf(1, 2, 3, 4), 443),
        )
        val v6 = Socks5.connect(ByteArray(16) { it.toByte() }, 80)
        assertEquals(4, v6[3].toInt())
        assertEquals(6 + 16, v6.size)
        assertEquals(80, ((v6[20].toInt() and 0xFF) shl 8) or (v6[21].toInt() and 0xFF))
    }

    @Test
    fun replyLengthDependsOnAddressType() {
        assertEquals(10, Socks5.replyLength(byteArrayOf(5, 0, 0, 1, 0)))
        assertEquals(22, Socks5.replyLength(byteArrayOf(5, 0, 0, 4, 0)))
        assertEquals(4 + 1 + 9 + 2, Socks5.replyLength(byteArrayOf(5, 0, 0, 3, 9)))
        assertEquals(-1, Socks5.replyLength(byteArrayOf(5, 0, 0, 9, 0)))
        assertEquals(0, Socks5.replyCode(byteArrayOf(5, 0)))
        assertEquals(4, Socks5.replyCode(byteArrayOf(5, 4)))
    }
}
