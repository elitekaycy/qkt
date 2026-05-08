package com.qkt.cli.daemon

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

class StrategyRegistry(
    private val factory: StrategyHandle.Factory,
) {
    private val handles = ConcurrentHashMap<String, StrategyHandle>()

    fun deploy(
        name: String,
        file: Path,
    ): StrategyHandle {
        require(name.matches(NAME_REGEX)) { "invalid strategy name: $name" }
        check(!handles.containsKey(name)) { "strategy '$name' already deployed" }
        val handle = factory.create(name, file)
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
        for (h in handles.values) runCatching { h.close() }
        handles.clear()
    }

    companion object {
        private val NAME_REGEX = Regex("[A-Za-z0-9_-]+")
    }
}
