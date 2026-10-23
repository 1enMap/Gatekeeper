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

    suspend fun setRoute(packageName: String, route: Route, defaultGrace: Int) {
        if (packageName == ownPackage) return // Gatekeeper's own traffic (Tor itself) is never routed.
        val existing = dao.get(packageName)
        dao.upsert(existing?.copy(route = route) ?: AppRule(packageName, Mode.ALLOWED, defaultGrace, route = route))
    }

    suspend fun setBlockTrackers(packageName: String, block: Boolean, defaultGrace: Int) {
        if (packageName == ownPackage) return
        val existing = dao.get(packageName)
        dao.upsert(existing?.copy(blockTrackers = block) ?: AppRule(packageName, Mode.ALLOWED, defaultGrace, blockTrackers = block))
    }

    /** Turns tracker blocking on or off for many apps at once. */
    suspend fun setBlockTrackers(packageNames: Collection<String>, block: Boolean, defaultGrace: Int) {
        val targets = packageNames.filter { it != ownPackage }
        if (targets.isEmpty()) return
        val existing = dao.getAll(targets).associateBy { it.packageName }
        dao.upsertAll(
            targets.map { pkg ->
                existing[pkg]?.copy(blockTrackers = block) ?: AppRule(pkg, Mode.ALLOWED, defaultGrace, blockTrackers = block)
            },
        )
    }

    suspend fun remove(packageName: String) = dao.delete(packageName)

    suspend fun removeAll(packageNames: Collection<String>) {
        if (packageNames.isNotEmpty()) dao.deleteAll(packageNames)
    }
}
