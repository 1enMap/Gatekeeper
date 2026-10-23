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

    @Test
    fun adobeAndFirebaseTrackersFromTheConnectionLogAreKnown() {
        assertEquals("Analytics / tracking", TrackerDomains.categoryOf("adobe.tt.omtrdc.net"))
        assertEquals("Analytics / tracking", TrackerDomains.categoryOf("assets.adobedtm.com"))
        assertEquals("Advertising", TrackerDomains.categoryOf("dpm.demdex.net"))
        assertEquals("Analytics / tracking", TrackerDomains.categoryOf("firebaselogging.googleapis.com"))
        // Needed for push notifications and app installs: must not be treated as trackers.
        assertNull(TrackerDomains.categoryOf("fcm.googleapis.com"))
        assertNull(TrackerDomains.categoryOf("firebaseinstallations.googleapis.com"))
        assertNull(TrackerDomains.categoryOf("cc-api-data.adobe.io"))
    }

    @Test
    fun paymentAppsAreRecognised() {
        org.junit.Assert.assertTrue(KnownApps.isPaymentApp("com.phonepe.app", "PhonePe"))
        org.junit.Assert.assertTrue(KnownApps.isPaymentApp("com.example.x", "Some Bank"))
        org.junit.Assert.assertTrue(KnownApps.isPaymentApp("com.example.y", "Amazon Pay"))
        org.junit.Assert.assertFalse(KnownApps.isPaymentApp("com.android.vending", "Google Play Store"))
        org.junit.Assert.assertFalse(KnownApps.isPaymentApp("com.example.z", "Display Settings"))
    }
}
