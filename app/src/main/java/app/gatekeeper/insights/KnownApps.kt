package app.gatekeeper.insights

/** Offline reference data for spotting fake or modified copies of popular apps. */
object KnownApps {

    /** App stores whose installs count as "from a store". */
    val STORES = mapOf(
        "com.android.vending" to "Google Play",
        "com.vivo.appstore" to "Vivo App Store",
        "com.bbk.appstore" to "Vivo App Store",
        "com.sec.android.app.samsungapps" to "Galaxy Store",
        "com.xiaomi.market" to "Xiaomi GetApps",
        "com.xiaomi.mipicks" to "Xiaomi GetApps",
        "com.heytap.market" to "OPPO App Market",
        "com.oppo.market" to "OPPO App Market",
        "com.huawei.appmarket" to "Huawei AppGallery",
        "com.amazon.venezia" to "Amazon Appstore",
        "org.fdroid.fdroid" to "F-Droid",
        "com.aurora.store" to "Aurora Store",
    )

    fun storeName(installer: String?): String? =
        installer?.let { STORES[it] }

    /** Lower-case app name → official package names. */
    private val OFFICIAL = mapOf(
        "whatsapp" to setOf("com.whatsapp"),
        "whatsapp business" to setOf("com.whatsapp.w4b"),
        "instagram" to setOf("com.instagram.android"),
        "facebook" to setOf("com.facebook.katana"),
        "telegram" to setOf("org.telegram.messenger", "org.telegram.messenger.web"),
        "signal" to setOf("org.thoughtcrime.securesms"),
        "youtube" to setOf("com.google.android.youtube"),
        "gmail" to setOf("com.google.android.gm"),
        "chrome" to setOf("com.android.chrome"),
        "google pay" to setOf("com.google.android.apps.nbu.paisa.user", "com.google.android.apps.walletnfcrel"),
        "phonepe" to setOf("com.phonepe.app"),
        "paytm" to setOf("net.one97.paytm"),
        "bhim" to setOf("in.org.npci.upiapp"),
        "paypal" to setOf("com.paypal.android.p2pmobile"),
        "amazon" to setOf("in.amazon.mShop.android.shopping", "com.amazon.mShop.android.shopping"),
        "flipkart" to setOf("com.flipkart.android"),
        "netflix" to setOf("com.netflix.mediaclient"),
        "spotify" to setOf("com.spotify.music"),
        "truecaller" to setOf("com.truecaller"),
        "snapchat" to setOf("com.snapchat.android"),
    )

    /** Brands whose unofficial "mods" are common and dangerous (they can read your chats). */
    private val MOD_TARGETS = mapOf(
        "whatsapp" to setOf("com.whatsapp", "com.whatsapp.w4b"),
        "instagram" to setOf("com.instagram.android", "com.instagram.lite", "com.instagram.barcelona"),
    )

    private val BRAND_NAMES = mapOf("whatsapp" to "WhatsApp", "instagram" to "Instagram")

    /** UPI, wallet and bank apps: they expect an Indian IP and break over Tor. */
    private val PAYMENT_APPS = setOf(
        "net.one97.paytm", "com.phonepe.app", "com.google.android.apps.nbu.paisa.user", "in.org.npci.upiapp",
        "com.sbi.lotusintouch", "com.csam.icici.bank.imobile", "com.snapwork.hdfc", "com.axis.mobile",
        "com.msf.kbank.mobile", "com.dreamplug.androidapp", "com.mobikwik_new", "com.freecharge.android",
        "com.paypal.android.p2pmobile", "com.google.android.apps.walletnfcrel",
    )
    private val PAYMENT_WORDS = listOf("bank", "upi", "wallet", " pay", "pay ")

    /** Likely a payment or banking app, by package or by name. */
    fun isPaymentApp(packageName: String, label: String): Boolean {
        if (packageName in PAYMENT_APPS) return true
        val name = " ${label.lowercase()} "
        return PAYMENT_WORDS.any { name.contains(it) }
    }

    fun isOfficialPackage(packageName: String): Boolean = OFFICIAL.values.any { packageName in it }

    /** If [label] claims to be a well-known app but [packageName] isn't it, returns the brand. */
    fun impersonatedBrand(label: String, packageName: String): String? {
        val name = label.trim().lowercase()
        OFFICIAL[name]?.let { official -> if (packageName !in official) return label.trim() }
        for ((brand, official) in MOD_TARGETS) {
            if (name.contains(brand) && packageName !in official && !OFFICIAL.values.any { packageName in it }) {
                return BRAND_NAMES[brand] ?: brand
            }
        }
        return null
    }
}
