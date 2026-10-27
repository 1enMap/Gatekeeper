package app.gatekeeper.privacy

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import app.gatekeeper.Notifications
import app.gatekeeper.data.InstalledApps
import app.gatekeeper.data.SensorDao
import app.gatekeeper.data.SensorEvent
import app.gatekeeper.data.Settings
import app.gatekeeper.engine.FirewallEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Notices when the camera or microphone is switched on and off, using the only signals Android
 * gives ordinary apps: camera availability and audio-recording callbacks. Android doesn't say
 * which app it is, so each period is stored with the app that was on screen at the time (from
 * the accessibility service). Use while the screen is off, or with no app open, is flagged and,
 * if the user wants, announced right away.
 *
 * Gatekeeper never opens the camera or microphone itself. Main thread only.
 */
class SensorWatch(
    private val context: Context,
    private val engine: FirewallEngine,
    private val dao: SensorDao,
    private val settings: Settings,
    private val scope: CoroutineScope,
) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private val _cameraInUse = MutableStateFlow(false)
    val cameraInUse: StateFlow<Boolean> = _cameraInUse.asStateFlow()
    private val _micInUse = MutableStateFlow(false)
    val micInUse: StateFlow<Boolean> = _micInUse.asStateFlow()

    private val busyCameras = HashSet<String>()
    private var cameraEventId: Long? = null
    private var micEventId: Long? = null
    private var lastAlert = HashMap<String, Long>()
    private var started = false

    private val cameraCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) {
            val wasIdle = busyCameras.isEmpty()
            busyCameras += cameraId
            if (wasIdle) onStart(SensorEvent.SENSOR_CAMERA)
        }

        override fun onCameraAvailable(cameraId: String) {
            if (busyCameras.remove(cameraId) && busyCameras.isEmpty()) onEnd(SensorEvent.SENSOR_CAMERA)
        }
    }

    private val micCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            val active = configs.isNotEmpty()
            if (active && !_micInUse.value) onStart(SensorEvent.SENSOR_MIC)
            if (!active && _micInUse.value) onEnd(SensorEvent.SENSOR_MIC)
        }
    }

    fun start() {
        if (started) return
        started = true
        scope.launch(Dispatchers.IO) {
            dao.closeDangling()
            dao.deleteOlderThan(System.currentTimeMillis() - RETENTION_MS)
        }
        runCatching {
            context.getSystemService(CameraManager::class.java).registerAvailabilityCallback(cameraCallback, main)
        }.onFailure { Log.w(TAG, "Camera watch unavailable", it) }
        runCatching {
            val audio = context.getSystemService(AudioManager::class.java)
            audio.registerAudioRecordingCallback(micCallback, main)
            if (audio.activeRecordingConfigurations.isNotEmpty()) onStart(SensorEvent.SENSOR_MIC)
        }.onFailure { Log.w(TAG, "Microphone watch unavailable", it) }
    }

    private fun onStart(sensor: String) {
        val camera = sensor == SensorEvent.SENSOR_CAMERA
        if (camera) _cameraInUse.value = true else _micInUse.value = true

        val now = System.currentTimeMillis()
        val screenOn = power.isInteractive
        val locked = keyguard.isKeyguardLocked
        // The app the user was last in; meaningless once the screen is off.
        val foreground = if (screenOn) engine.foreground.value?.takeIf { it != homePackage() } else null
        // Face unlock uses the camera on the lock screen with the screen on: not flagged. With the
        // screen off, or no app open, nothing the user is looking at explains it.
        val suspicious = !screenOn || (foreground == null && !locked)
        val event = SensorEvent(
            sensor = sensor,
            startMillis = now,
            endMillis = null,
            foregroundPackage = foreground,
            screenOn = screenOn,
            locked = locked,
            suspicious = suspicious,
        )
        scope.launch {
            val id = withContext(Dispatchers.IO) { dao.insert(event) }
            if (camera) cameraEventId = id else micEventId = id
            // Ended before the insert finished: close it now.
            if ((camera && !_cameraInUse.value) || (!camera && !_micInUse.value)) {
                withContext(Dispatchers.IO) { dao.end(id, System.currentTimeMillis()) }
                if (camera) cameraEventId = null else micEventId = null
            }
            if (suspicious) maybeAlert(sensor, screenOn)
        }
        engine.log(foreground, "${if (camera) "Camera" else "Microphone"} on" + if (suspicious) " (screen off or no app open)" else "")
    }

    private fun onEnd(sensor: String) {
        val camera = sensor == SensorEvent.SENSOR_CAMERA
        if (camera) _cameraInUse.value = false else _micInUse.value = false
        val id = (if (camera) cameraEventId else micEventId) ?: return
        if (camera) cameraEventId = null else micEventId = null
        val end = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) { dao.end(id, end) }
        engine.log(null, "${if (camera) "Camera" else "Microphone"} off")
    }

    private suspend fun maybeAlert(sensor: String, screenOn: Boolean) {
        if (!settings.current().sensorAlerts) return
        val now = System.currentTimeMillis()
        // One alert per sensor per few minutes: a voice assistant or call can flip it repeatedly.
        if (now - (lastAlert[sensor] ?: 0L) < ALERT_INTERVAL_MS) return
        lastAlert[sensor] = now
        val detail = if (!screenOn) {
            "It switched on while the screen was off. Open Gatekeeper to see when, and check which apps have this permission."
        } else {
            "It switched on while no app was open. Open Gatekeeper to see when, and check which apps have this permission."
        }
        Notifications.showSensorAlert(context, sensor == SensorEvent.SENSOR_CAMERA, detail)
    }

    private var home: String? = null
    private fun homePackage(): String? = home ?: runCatching {
        context.packageManager
            .resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
    }.getOrNull().also { home = it }

    /** Label for a stored foreground package. */
    fun labelOf(pkg: String): String = InstalledApps.label(context, pkg)

    private companion object {
        const val TAG = "SensorWatch"
        const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000
        const val ALERT_INTERVAL_MS = 5 * 60_000L
    }
}
