package app.gatekeeper.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class SettingsState(
    /** Master switch. Also decides whether the firewall comes back after a reboot. */
    val firewallEnabled: Boolean = false,
    val newAppMode: Mode = Mode.ALLOWED,
    val defaultGraceSeconds: Int = AppRule.DEFAULT_GRACE_SECONDS,
    /** Poll UsageStatsManager when the accessibility service is not running. */
    val usageStatsFallback: Boolean = false,
    val onboardingDone: Boolean = false,
    /** Keep a local log of the servers blocked apps try to reach. */
    val recordConnections: Boolean = true,
    /** Experimental: relay every app's traffic through Tor while the firewall runs. */
    val torMode: Boolean = false,
)

class Settings(context: Context) {
    private val store = context.applicationContext.dataStore

    val state: Flow<SettingsState> = store.data.map { p ->
        SettingsState(
            firewallEnabled = p[FIREWALL_ENABLED] ?: false,
            newAppMode = p[NEW_APP_MODE]?.let { runCatching { Mode.valueOf(it) }.getOrNull() } ?: Mode.ALLOWED,
            defaultGraceSeconds = p[DEFAULT_GRACE] ?: AppRule.DEFAULT_GRACE_SECONDS,
            usageStatsFallback = p[USAGE_FALLBACK] ?: false,
            onboardingDone = p[ONBOARDING_DONE] ?: false,
            recordConnections = p[RECORD_CONNECTIONS] ?: true,
            torMode = p[TOR_MODE] ?: false,
        )
    }

    suspend fun current(): SettingsState = state.first()

    suspend fun setFirewallEnabled(value: Boolean) = store.edit { it[FIREWALL_ENABLED] = value }
    suspend fun setNewAppMode(value: Mode) = store.edit { it[NEW_APP_MODE] = value.name }
    suspend fun setDefaultGrace(value: Int) =
        store.edit { it[DEFAULT_GRACE] = value.coerceIn(0, AppRule.MAX_GRACE_SECONDS) }
    suspend fun setUsageStatsFallback(value: Boolean) = store.edit { it[USAGE_FALLBACK] = value }
    suspend fun setOnboardingDone(value: Boolean) = store.edit { it[ONBOARDING_DONE] = value }
    suspend fun setRecordConnections(value: Boolean) = store.edit { it[RECORD_CONNECTIONS] = value }
    suspend fun setTorMode(value: Boolean) = store.edit { it[TOR_MODE] = value }

    private companion object {
        val FIREWALL_ENABLED = booleanPreferencesKey("firewall_enabled")
        val NEW_APP_MODE = stringPreferencesKey("new_app_mode")
        val DEFAULT_GRACE = intPreferencesKey("default_grace_seconds")
        val USAGE_FALLBACK = booleanPreferencesKey("usage_stats_fallback")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val RECORD_CONNECTIONS = booleanPreferencesKey("record_connections")
        val TOR_MODE = booleanPreferencesKey("tor_mode")
    }
}
