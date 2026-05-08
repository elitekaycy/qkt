package com.qkt.cli.daemon

import com.qkt.app.LiveSessionHandle
import com.qkt.cli.observe.EventRing
import com.qkt.cli.observe.ObservabilityServer
import com.qkt.dsl.ast.StrategyAst
import java.nio.file.Path
import java.time.Instant

class StrategyHandle(
    val name: String,
    val ast: StrategyAst,
    val live: LiveSessionHandle,
    val observability: ObservabilityServer,
    val ring: EventRing,
    val logFile: Path,
    val startedAt: Instant,
) : AutoCloseable {
    val port: Int get() = observability.boundPort
    val tradeCount: Int get() = ring.size()

    fun isRunning(): Boolean = live.running

    override fun close() {
        live.stop()
        observability.close()
    }

    fun interface Factory {
        fun create(
            name: String,
            file: Path,
        ): StrategyHandle
    }
}
