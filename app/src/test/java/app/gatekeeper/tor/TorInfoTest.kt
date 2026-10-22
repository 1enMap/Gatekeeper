package app.gatekeeper.tor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TorInfoTest {
    @Test
    fun parsesBootstrapPhase() {
        assertEquals(
            45 to "Loading relay descriptors",
            TorInfo.bootstrap("NOTICE BOOTSTRAP PROGRESS=45 TAG=loading_descriptors SUMMARY=\"Loading relay descriptors\""),
        )
        assertEquals(100 to "Done", TorInfo.bootstrap("NOTICE BOOTSTRAP PROGRESS=100 TAG=done SUMMARY=\"Done\""))
        assertEquals(5 to "", TorInfo.bootstrap("NOTICE BOOTSTRAP PROGRESS=5"))
        assertNull(TorInfo.bootstrap(null))
        assertNull(TorInfo.bootstrap("garbage"))
    }

    @Test
    fun parsesListenerPorts() {
        assertEquals(9050, TorInfo.firstPort("\"127.0.0.1:9050\""))
        assertEquals(40123, TorInfo.firstPort("\"127.0.0.1:40123\" \"[::1]:40124\""))
        assertEquals(5353, TorInfo.firstPort("\"[::1]:5353\""))
        assertEquals(0, TorInfo.firstPort(""))
        assertEquals(0, TorInfo.firstPort(null))
        assertEquals(0, TorInfo.firstPort("\"unix:/data/socks\""))
    }

    @Test
    fun torrcIsClientOnlyAndHasBothPorts() {
        val rc = TorInfo.torrc()
        assertTrue(rc.contains("SocksPort auto"))
        assertTrue(rc.contains("DNSPort auto"))
        assertTrue(!rc.contains("ORPort") && !rc.contains("ExitRelay"))
    }
}
