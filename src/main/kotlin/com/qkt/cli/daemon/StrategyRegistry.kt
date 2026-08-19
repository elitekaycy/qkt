package com.qkt.cli.daemon

import com.qkt.cli.daemon.portfolio.PortfolioSupervisor
import com.qkt.risk.HaltScope
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class StrategyRegistry(
    private val factory: StrategyHandle.Factory,
) {
    private val handles = ConcurrentHashMap<String, StrategyHandle>()
    private val portfolios = ConcurrentHashMap<String, PortfolioRecord>()

    fun deploy(
        name: String,
        file: Path,
        ignoreMismatches: Boolean = false,
    ): StrategyHandle {
        require(name.matches(NAME_REGEX)) { "invalid strategy name: $name" }
        check(!handles.containsKey(name)) { "strategy '$name' already deployed" }
        check(!portfolios.containsKey(name)) { "name '$name' already deployed as portfolio" }
        val handle = factory.create(name, file, ignoreMismatches)
        handles[name] = handle
        return handle
    }

    /**
     * Replace an already-deployed standalone strategy under the same operator-facing name.
     *
     * The old session is halted, drained, and removed before the replacement is created. This
     * forbids two sessions from sharing one account/magic or racing durable-state writes. A
     * replacement startup failure leaves the strategy undeployed and must be retried explicitly.
     */
    fun resyncStrategy(
        name: String,
        file: Path,
        ignoreMismatches: Boolean = false,
    ): StrategyHandle {
        require(name.matches(NAME_REGEX)) { "invalid strategy name: $name" }
        check(!portfolios.containsKey(name)) { "name '$name' is deployed as portfolio" }
        val old = handles[name] ?: error("strategy '$name' is not deployed")
        check(old.childMeta == null) { "strategy '$name' is a portfolio child; resync the parent portfolio" }
        old.live.halt("operator resync", HaltScope.TRANSIENT)
        handles.remove(name, old)
        old.close()
        val replacement = factory.create(name, file, ignoreMismatches)
        handles[name] = replacement
        return replacement
    }

    fun stop(name: String): Boolean {
        val h = handles.remove(name) ?: return false
        h.close()
        return true
    }

    fun get(name: String): StrategyHandle? = handles[name]

    fun list(): List<StrategyHandle> = handles.values.toList()

    /** Stops every supervisor/session against one shared deadline. */
    fun stopAll(timeout: Duration = Duration.ofSeconds(5)) {
        require(!timeout.isNegative) { "stop timeout must not be negative" }
        val deadlineNanos = System.nanoTime() + timeout.toNanos()
        val supervisors = portfolios.values.map { it.supervisor }
        val closing = handles.values.toList()
        portfolios.clear()
        handles.clear()
        for (supervisor in supervisors) runCatching { supervisor.requestStop() }
        for (handle in closing) runCatching { handle.requestStop() }
        for (supervisor in supervisors) {
            val remaining = (deadlineNanos - System.nanoTime()).coerceAtLeast(0L)
            runCatching { supervisor.awaitStopped(Duration.ofNanos(remaining)) }
        }
        for (handle in closing) {
            val remaining = (deadlineNanos - System.nanoTime()).coerceAtLeast(0L)
            runCatching { handle.awaitStopped(Duration.ofNanos(remaining)) }
        }
    }

    fun registerPortfolio(record: PortfolioRecord) {
        require(record.name.matches(NAME_REGEX)) { "invalid portfolio name: ${record.name}" }
        require(!record.name.contains('/')) { "portfolio name must not contain '/': ${record.name}" }
        check(!handles.containsKey(record.name)) { "name '${record.name}' already in use" }
        check(!portfolios.containsKey(record.name)) { "portfolio '${record.name}' already deployed" }
        for (child in record.children) {
            check(!handles.containsKey(child.name)) { "child name '${child.name}' already in use" }
        }
        portfolios[record.name] = record
        for (child in record.children) handles[child.name] = child
    }

    /**
     * Retires and drains an existing portfolio before its replacement sessions are created.
     * [replacementAliases] are validated first so a known registry conflict does not cause downtime.
     */
    fun retirePortfolioForResync(
        name: String,
        replacementAliases: List<String>,
    ) {
        val old = portfolios[name] ?: error("portfolio '$name' is not deployed")
        val oldChildNames = old.children.map { it.name }.toSet()
        val replacementNames = replacementAliases.map { "$name/$it" }
        val conflicting =
            replacementNames.firstOrNull { childName ->
                handles.containsKey(childName) && childName !in oldChildNames
            }
        check(conflicting == null) { "child name '$conflicting' already in use" }

        portfolios.remove(name, old)
        old.children.forEach { handles.remove(it.name, it) }
        old.children.forEach { it.live.halt("operator resync", HaltScope.TRANSIENT) }
        old.supervisor.stop()
        old.children.forEach { it.close() }
    }

    /**
     * Replace an already-deployed portfolio and its child handles as one registry mutation.
     *
     * The existing supervisor is stopped and children are halted before the swap. If the
     * replacement cannot be installed, the previous supervisor and children are resumed.
     * Replacement children are closed on install failure so a failed resync does not leak
     * sessions or observability ports.
     */
    fun resyncPortfolio(record: PortfolioRecord): PortfolioRecord {
        require(record.name.matches(NAME_REGEX)) { "invalid portfolio name: ${record.name}" }
        require(!record.name.contains('/')) { "portfolio name must not contain '/': ${record.name}" }
        if (handles.containsKey(record.name)) {
            closeReplacement(record)
            error("name '${record.name}' already deployed as strategy")
        }
        val old = portfolios[record.name] ?: error("portfolio '${record.name}' is not deployed")
        var oldStopped = false
        return try {
            val oldChildNames = old.children.map { it.name }.toSet()
            val conflictingChild =
                record.children.firstOrNull { child ->
                    handles.containsKey(child.name) && child.name !in oldChildNames
                }
            check(conflictingChild == null) { "child name '${conflictingChild!!.name}' already in use" }

            old.supervisor.stop()
            old.children.forEach { it.live.halt("operator resync", HaltScope.TRANSIENT) }
            oldStopped = true
            portfolios.remove(old.name)
            old.children.forEach { handles.remove(it.name) }
            portfolios[record.name] = record
            record.children.forEach { handles[it.name] = it }
            old.children.forEach { runCatching { it.close() } }
            record
        } catch (e: RuntimeException) {
            closeReplacement(record)
            portfolios[old.name] = old
            old.children.forEach { handles[it.name] = it }
            if (oldStopped) {
                old.children.forEach { it.live.resume() }
                old.supervisor.start()
            }
            throw e
        }
    }

    private fun closeReplacement(record: PortfolioRecord) {
        runCatching { record.supervisor.stop() }
        record.children.forEach { runCatching { it.close() } }
    }

    fun getPortfolio(name: String): PortfolioRecord? = portfolios[name]

    fun listPortfolios(): List<PortfolioRecord> = portfolios.values.toList()

    fun childrenOf(parent: String): List<StrategyHandle> = handles.values.filter { it.childMeta?.parent == parent }

    fun removePortfolio(name: String): PortfolioRecord? {
        val record = portfolios.remove(name) ?: return null
        for (child in record.children) handles.remove(child.name)
        return record
    }

    companion object {
        private val NAME_REGEX = Regex("[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)?")
    }
}

data class PortfolioRecord(
    val name: String,
    val version: Int,
    val supervisor: PortfolioSupervisor,
    val children: List<StrategyHandle>,
    val logFile: Path,
    val startedAt: Instant,
)
