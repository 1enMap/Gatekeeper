package app.gatekeeper.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReferenceDataTest {
    @Test
    fun trackerSubdomainsMatch() {
        assertEquals("Analytics / tracking", TrackerDomains.categoryOf("app-measurement.com"))
        assertEquals("Advertising", TrackerDomains.categoryOf("pagead2.googlesyndication.com"))
        assertEquals("Crash reporting", TrackerDomains.categoryOf("settings.crashlytics.com."))
        assertNull(TrackerDomains.categoryOf("web.whatsapp.com"))
        assertNull(TrackerDomains.categoryOf("notgoogle-analytics.com"))
        assertNull(TrackerDomains.categoryOf(null))
    }

    @Test
    fun impersonationIsDetected() {
        assertNull(KnownApps.impersonatedBrand("WhatsApp", "com.whatsapp"))
        assertNull(KnownApps.impersonatedBrand("WhatsApp Business", "com.whatsapp.w4b"))
        assertEquals("WhatsApp", KnownApps.impersonatedBrand("WhatsApp", "com.fake.whatsapp"))
        assertEquals("WhatsApp", KnownApps.impersonatedBrand("GBWhatsApp", "com.gbwhatsapp"))
        assertNull(KnownApps.impersonatedBrand("Gatekeeper", "app.gatekeeper"))
        assertNull(KnownApps.impersonatedBrand("Threads, an Instagram app", "com.instagram.barcelona"))
    }
}
