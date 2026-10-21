package app.gatekeeper.insights

/**
 * Small offline list of well-known advertising, analytics and crash-reporting endpoints.
 * Used only to label names seen in the connection log; nothing is downloaded.
 */
object TrackerDomains {
    private val categories: Map<String, String> = buildMap {
        listOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
            "an.facebook.com", "applovin.com", "applvn.com", "unityads.unity3d.com", "ironsrc.com",
            "ironsrc.mobi", "supersonicads.com", "inmobi.com", "chartboost.com", "vungle.com",
            "adcolony.com", "startappservice.com", "mopub.com", "pangle.io", "ads.tiktok.com",
            "admob.com", "media.net", "taboola.com", "outbrain.com", "criteo.com", "criteo.net",
        ).forEach { put(it, "Advertising") }
        listOf(
            "google-analytics.com", "app-measurement.com", "firebaselogging-pa.googleapis.com",
            "graph.facebook.com", "connect.facebook.net", "appsflyer.com", "appsflyersdk.com",
            "adjust.com", "adjust.net.in", "branch.io", "kochava.com", "singular.net",
            "mixpanel.com", "amplitude.com", "segment.io", "segment.com", "flurry.com",
            "onesignal.com", "moengage.com", "clevertap-prod.com", "wzrkt.com", "webengage.com",
            "analytics.tiktok.com", "scorecardresearch.com", "hotjar.com", "braze.com",
            "appmetrica.yandex.net", "newrelic.com", "nr-data.net",
        ).forEach { put(it, "Analytics / tracking") }
        listOf(
            "crashlytics.com", "firebase-settings.crashlytics.com", "sentry.io", "bugsnag.com",
            "instabug.com", "raygun.io",
        ).forEach { put(it, "Crash reporting") }
    }

    /** Category for [domain] or one of its parent domains, or null if it's not a known tracker. */
    fun categoryOf(domain: String?): String? {
        var d = domain?.lowercase()?.trimEnd('.') ?: return null
        while (true) {
            categories[d]?.let { return it }
            val dot = d.indexOf('.')
            if (dot < 0) return null
            d = d.substring(dot + 1)
        }
    }
}
