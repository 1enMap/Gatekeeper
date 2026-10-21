package app.gatekeeper.insights

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

enum class Risk { HIGH, MEDIUM, LOW }

data class Finding(
    val severity: Risk,
    val title: String,
    val detail: String,
    /** A reassuring fact rather than a problem. */
    val good: Boolean = false,
)

data class AppAudit(
    val packageName: String,
    val label: String,
    val uid: Int,
    /** Pre-installed by the phone maker or Google. */
    val preinstalled: Boolean,
    /** Pre-installed background component without an icon (hidden by default). */
    val isSystem: Boolean,
    val hasInternet: Boolean,
    val installerName: String?,
    /** Sensitive permissions currently granted, e.g. "Location", "Microphone". */
    val sensitive: List<String>,
    val findings: List<Finding>,
    val score: Int,
    val risk: Risk,
)

/**
 * Offline privacy and security check of installed apps: special powers (accessibility,
 * notification access, device admin), sensitive permissions combined with internet access,
 * where the app came from, and look-alikes of popular apps. Nothing is sent anywhere.
 */
object SecurityAudit {

    private class Sensitive(val name: String, val permissions: List<String>, val weight: Int, val why: String)

    private val SENSITIVE = listOf(
        Sensitive("Background location", listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 20,
            "Can track where you are even when you're not using it."),
        Sensitive("Location", listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 10,
            "Can see where you are while it's in use."),
        Sensitive("SMS", listOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS), 15,
            "Can read your text messages, including bank OTPs."),
        Sensitive("Microphone", listOf(Manifest.permission.RECORD_AUDIO), 12,
            "Can record audio while in use."),
        Sensitive("Call logs", listOf(Manifest.permission.READ_CALL_LOG), 12,
            "Can see who you called and when."),
        Sensitive("Contacts", listOf(Manifest.permission.READ_CONTACTS), 8,
            "Can read your address book."),
        Sensitive("Camera", listOf(Manifest.permission.CAMERA), 6,
            "Can take photos and video while in use."),
        Sensitive("Body sensors", listOf(Manifest.permission.BODY_SENSORS), 6,
            "Can read health sensor data."),
        Sensitive("Phone", listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_NUMBERS), 4,
            "Can see your phone number and call state."),
        Sensitive("Calendar", listOf(Manifest.permission.READ_CALENDAR), 4,
            "Can read your calendar."),
        Sensitive("Photos & media", listOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", Manifest.permission.READ_EXTERNAL_STORAGE), 3,
            "Can read your photos and files."),
    )

    fun run(context: Context, backgroundBytesByUid: Map<Int, Long> = emptyMap()): List<AppAudit> {
        val pm = context.packageManager
        val own = context.packageName
        val accessibility = enabledAccessibilityPackages(context)
        val notificationReaders = runCatching { NotificationManagerCompat.getEnabledListenerPackages(context) }
            .getOrDefault(emptySet())
        val deviceAdmins = runCatching {
            context.getSystemService(DevicePolicyManager::class.java).activeAdmins?.map { it.packageName }?.toSet()
        }.getOrNull().orEmpty()
        val launchable = launchablePackages(pm)

        return installedPackages(pm)
            .filter { it.packageName != own && it.applicationInfo != null }
            .map { info ->
                audit(context, pm, info, launchable, accessibility, notificationReaders, deviceAdmins, backgroundBytesByUid)
            }
            .sortedWith(compareBy<AppAudit> { it.risk.ordinal }.thenByDescending { it.score }.thenBy { it.label.lowercase() })
    }

    private fun audit(
        context: Context,
        pm: PackageManager,
        info: PackageInfo,
        launchable: Set<String>,
        accessibility: Set<String>,
        notificationReaders: Set<String>,
        deviceAdmins: Set<String>,
        backgroundBytesByUid: Map<Int, Long>,
    ): AppAudit {
        val app = info.applicationInfo!!
        val pkg = info.packageName
        val label = app.loadLabel(pm).toString()
        val preinstalled = app.flags and ApplicationInfo.FLAG_SYSTEM != 0
        val requested = info.requestedPermissions.orEmpty()
        val flags = info.requestedPermissionsFlags ?: IntArray(0)
        val granted = requested.filterIndexed { i, _ ->
            i < flags.size && flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
        }.toSet()
        val hasInternet = Manifest.permission.INTERNET in requested
        val installer = installerOf(pm, pkg)
        val storeName = KnownApps.storeName(installer)

        val findings = ArrayList<Finding>()
        var score = 0
        fun add(points: Int, finding: Finding) {
            score += points
            findings += finding
        }

        // --- Special powers: what real Android malware abuses most. Pre-installed tools (e.g.
        // Find My Device) legitimately use some of them, so they count for less there.
        if (pkg in accessibility) {
            if (preinstalled) add(15, Finding(Risk.MEDIUM, "Accessibility access is ON",
                "A pre-installed component that can read your screen and act for you."))
            else add(40, Finding(Risk.HIGH, "Accessibility access is ON",
                "Can read everything on your screen and tap for you. Only keep this for apps you fully trust, such as a password manager."))
        }
        if (pkg in deviceAdmins) {
            if (preinstalled) add(5, Finding(Risk.LOW, "Device admin",
                "A pre-installed component allowed to lock or wipe the phone (e.g. Find My Device)."))
            else add(30, Finding(Risk.HIGH, "Device admin",
                "Can lock or wipe the phone and make itself hard to uninstall."))
        }
        if (pkg in notificationReaders) add(if (preinstalled) 10 else 25, Finding(Risk.MEDIUM, "Reads all notifications",
            "Sees every notification, including OTP codes and message previews."))

        // --- Fakes and modified apps. ---
        if (!preinstalled) {
            KnownApps.impersonatedBrand(label, pkg)?.let { brand ->
                add(50, Finding(Risk.HIGH, "Looks like $brand, but isn't the official app",
                    "Its name matches $brand but its identity ($pkg) doesn't. Modified or fake copies of popular apps often steal messages or logins."))
            }
            if (storeName == null) {
                if (KnownApps.isOfficialPackage(pkg)) {
                    add(25, Finding(Risk.MEDIUM, "Popular app installed from outside a store",
                        "Fine if you downloaded it from the official website; otherwise it may be a tampered copy."))
                } else {
                    add(10, Finding(Risk.LOW, "Installed from an APK file",
                        "Not from an app store, so nobody checked it before it reached your phone."))
                }
            }
            if (isDebugSigned(info)) add(20, Finding(Risk.MEDIUM, "Signed with a developer test key",
                "Real store apps are signed with their maker's key. Test-signed apps are often modified copies."))
        }
        if (Manifest.permission.REQUEST_INSTALL_PACKAGES in requested && !preinstalled) {
            add(8, Finding(Risk.LOW, "Can ask to install other apps",
                "A common way for malicious apps to bring in more malware."))
        }
        if (Manifest.permission.SYSTEM_ALERT_WINDOW in requested && !preinstalled) {
            add(4, Finding(Risk.LOW, "Can ask to draw over other apps",
                "Used for chat bubbles, but also for fake login screens."))
        }

        // --- Sensitive data it can reach, and whether it can send it anywhere. ---
        val sensitive = SENSITIVE.filter { s -> s.permissions.any { it in granted } }
            // Background location already implies location.
            .let { list -> if (list.any { it.name == "Background location" }) list.filter { it.name != "Location" } else list }
        val permissionPoints = sensitive.sumOf { it.weight }
        sensitive.forEach { s ->
            // Without internet access the app can't send this data anywhere itself.
            val severity = if (s.weight >= 12 && hasInternet) Risk.MEDIUM else Risk.LOW
            findings += Finding(severity, "${s.name} access", s.why)
        }
        score += if (hasInternet) permissionPoints else permissionPoints / 4

        // --- What it actually did on the network. ---
        val background = backgroundBytesByUid[app.uid] ?: 0L
        val size = Formatter.formatShortFileSize(context, background)
        when {
            background >= 100L * MB -> add(15, Finding(Risk.MEDIUM, "Used $size in the background",
                "Over the last 7 days, while you weren't using it."))
            background >= 10L * MB -> add(5, Finding(Risk.LOW, "Used $size in the background",
                "Over the last 7 days, while you weren't using it."))
        }

        if (!hasInternet) findings += Finding(Risk.LOW, "Can't go online",
            "Doesn't have the Internet permission, so it can't send your data over the network itself.", good = true)
        if (!preinstalled && storeName != null) findings += Finding(Risk.LOW, "Installed from $storeName",
            "Came through an app store.", good = true)

        val worst = findings.filter { !it.good }.minOfOrNull { it.severity.ordinal }?.let { Risk.entries[it] } ?: Risk.LOW
        val byScore = when {
            score >= 50 -> Risk.HIGH
            score >= 20 -> Risk.MEDIUM
            else -> Risk.LOW
        }
        val risk = Risk.entries[minOf(worst.ordinal, byScore.ordinal)]

        return AppAudit(
            packageName = pkg,
            label = label,
            uid = app.uid,
            preinstalled = preinstalled,
            isSystem = preinstalled && pkg !in launchable,
            hasInternet = hasInternet,
            installerName = storeName ?: installer?.let { "APK ($it)" },
            sensitive = sensitive.map { it.name },
            findings = findings.sortedWith(compareBy<Finding> { it.good }.thenBy { it.severity.ordinal }),
            score = score,
            risk = risk,
        )
    }

    private const val MB = 1024L * 1024L

    private fun installedPackages(pm: PackageManager): List<PackageInfo> {
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNING_CERTIFICATES
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(flags)
        }
    }

    private fun installerOf(pm: PackageManager, pkg: String): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(pkg).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(pkg)
        }
    } catch (_: Exception) {
        null
    }

    private fun isDebugSigned(info: PackageInfo): Boolean {
        val signing = info.signingInfo ?: return false
        val certs = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        val first = certs?.firstOrNull() ?: return false
        return try {
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(first.toByteArray())) as X509Certificate
            cert.subjectX500Principal.name.contains("CN=Android Debug")
        } catch (_: Exception) {
            false
        }
    }

    private fun enabledAccessibilityPackages(context: Context): Set<String> = runCatching {
        context.getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .mapNotNull { it.resolveInfo?.serviceInfo?.packageName }
            .toSet()
    }.getOrDefault(emptySet())

    private fun launchablePackages(pm: PackageManager): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        return pm.queryIntentActivities(intent, 0).mapTo(HashSet()) { it.activityInfo.packageName }
    }
}
