package app.gatekeeper

import android.app.Application
import app.gatekeeper.data.AppDatabase
import app.gatekeeper.data.ConnectionDao
import app.gatekeeper.data.SensorDao
import app.gatekeeper.data.Mode
import app.gatekeeper.data.Route
import app.gatekeeper.data.RuleRepository
import app.gatekeeper.data.Settings
import app.gatekeeper.engine.FirewallEngine
import app.gatekeeper.foreground.ForegroundFilter
import app.gatekeeper.privacy.SensorWatch
import app.gatekeeper.tor.TorController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GatekeeperApp : Application() {
    /** Process-wide scope on the main thread; the engine relies on main-thread confinement. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var settings: Settings
        private set
    lateinit var rules: RuleRepository
        private set
    lateinit var connections: ConnectionDao
        private set
    /** Camera and microphone use, watched for as long as Gatekeeper's process runs. */
    lateinit var sensors: SensorWatch
        private set
    lateinit var sensorEvents: SensorDao
        private set
    lateinit var engine: FirewallEngine
        private set
    lateinit var foregroundFilter: ForegroundFilter
        private set

    /** Embedded Tor for Tor mode; started and stopped by the VPN service. */
    val tor: TorController by lazy { TorController(this) }

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        val db = AppDatabase.create(this)
        rules = RuleRepository(db.ruleDao(), packageName)
        connections = db.connectionDao()
        engine = FirewallEngine(this, rules, appScope)
        foregroundFilter = ForegroundFilter(this)
        Notifications.createChannels(this)
        sensorEvents = db.sensorDao()
        sensors = SensorWatch(this, engine, sensorEvents, settings, appScope).also { it.start() }
    }

    /** Single entry point for changing a mode, so the engine hears about it too. */
    fun setMode(packageName: String, mode: Mode) {
        appScope.launch {
            val grace = settings.current().defaultGraceSeconds
            withContext(Dispatchers.IO) { rules.setMode(packageName, mode, grace) }
            engine.onRuleChanged(packageName, mode)
        }
    }

    fun setGrace(packageName: String, seconds: Int) {
        appScope.launch(Dispatchers.IO) { rules.setGrace(packageName, seconds, Mode.ALLOWED) }
    }

    fun setRoute(packageName: String, route: Route) {
        appScope.launch {
            val grace = settings.current().defaultGraceSeconds
            withContext(Dispatchers.IO) { rules.setRoute(packageName, route, grace) }
        }
    }

    fun setBlockTrackers(packageName: String, block: Boolean) {
        appScope.launch {
            val grace = settings.current().defaultGraceSeconds
            withContext(Dispatchers.IO) { rules.setBlockTrackers(packageName, block, grace) }
        }
    }

    fun setBlockTrackers(packageNames: Collection<String>, block: Boolean) {
        appScope.launch {
            val grace = settings.current().defaultGraceSeconds
            withContext(Dispatchers.IO) { rules.setBlockTrackers(packageNames, block, grace) }
        }
    }

    fun removeRule(packageName: String) {
        appScope.launch(Dispatchers.IO) { rules.remove(packageName) }
    }
}

val android.content.Context.gatekeeper: GatekeeperApp
    get() = applicationContext as GatekeeperApp
