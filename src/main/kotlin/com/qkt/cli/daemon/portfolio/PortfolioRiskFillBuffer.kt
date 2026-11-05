package com.qkt.cli.daemon.portfolio

import java.math.BigDecimal
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

/** Buffers child fills until the book risk state is constructed, then forwards directly. */
internal class PortfolioRiskFillBuffer {
    private data class Fill(
        val strategyId: String,
        val realized: BigDecimal,
    )

    private val pending = ConcurrentLinkedQueue<Fill>()
    private val target = AtomicReference<PortfolioRiskAggregator?>()

    fun record(
        strategyId: String,
        realized: BigDecimal,
    ) {
        val bound = target.get()
        if (bound != null) {
            bound.recordRealized(strategyId, realized)
            return
        }
        pending.add(Fill(strategyId, realized))
        target.get()?.let(::drain)
    }

    fun bind(aggregator: PortfolioRiskAggregator) {
        check(target.compareAndSet(null, aggregator)) { "portfolio risk fill buffer already bound" }
        drain(aggregator)
    }

    private fun drain(aggregator: PortfolioRiskAggregator) {
        while (true) {
            val fill = pending.poll() ?: return
            aggregator.recordRealized(fill.strategyId, fill.realized)
        }
    }
}
