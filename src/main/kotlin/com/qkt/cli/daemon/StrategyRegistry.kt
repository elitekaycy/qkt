package com.qkt.cli.daemon

import com.qkt.cli.daemon.portfolio.PortfolioSupervisor
import java.nio.file.Path
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

    fun stop(name: String): Boolean {
        val h = handles.remove(name) ?: return false
        h.close()
        return true
    }

    fun get(name: String): StrategyHandle? = handles[name]

    fun list(): List<StrategyHandle> = handles.values.toList()

    fun stopAll() {
        for (record in portfolios.values) runCatching { record.supervisor.stop() }
        portfolios.clear()
        for (h in handles.values) runCatching { h.close() }
        handles.clear()
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
