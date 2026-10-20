package app.gatekeeper.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RuleRepository(
    private val dao: RuleDao,
    private val ownPackage: String,
) {
    /** All rules keyed by package name. */
    val rules: Flow<Map<String, AppRule>> =
        dao.observeAll().map { list -> list.associateBy { it.packageName } }

    suspend fun get(packageName: String): AppRule? = dao.get(packageName)

    suspend fun setMode(packageName: String, mode: Mode, defaultGrace: Int) {
        if (packageName == ownPackage) return // Gatekeeper never blocks itself.
        val existing = dao.get(packageName)
        dao.upsert(existing?.copy(mode = mode) ?: AppRule(packageName, mode, defaultGrace))
    }

    suspend fun setGrace(packageName: String, seconds: Int, defaultMode: Mode) {
        if (packageName == ownPackage) return
        val clamped = seconds.coerceIn(0, AppRule.MAX_GRACE_SECONDS)
        val existing = dao.get(packageName)
        dao.upsert(existing?.copy(graceSeconds = clamped) ?: AppRule(packageName, defaultMode, clamped))
    }

    suspend fun remove(packageName: String) = dao.delete(packageName)

    suspend fun removeAll(packageNames: Collection<String>) {
        if (packageNames.isNotEmpty()) dao.deleteAll(packageNames)
    }
}
