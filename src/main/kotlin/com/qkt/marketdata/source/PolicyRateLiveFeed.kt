package com.qkt.marketdata.source

import com.qkt.common.Clock
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.store.macro.MacroSeriesStore
import com.qkt.marketdata.store.macro.PolicyRateSeries
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/** Polls official policy-rate tables and emits only newly observed values for live strategies. */
class PolicyRateLiveFeed(
    private val symbols: Map<String, PolicyRateSeries>,
    private val store: MacroSeriesStore,
    private val clock: Clock,
    private val pollIntervalMs: Long,
    private val maxObservationAgeDays: Long = 7L,
    private val refresh: (PolicyRateSeries, LocalDate, LocalDate) -> Unit,
) : TickFeed {
    private val closed = AtomicBoolean(false)
    private val pending = ArrayDeque<Tick>()
    private val lastValues = mutableMapOf<String, java.math.BigDecimal>()
    private var firstPoll = true

    init {
        require(symbols.isNotEmpty()) { "policy-rate live feed requires at least one symbol" }
        require(pollIntervalMs > 0) { "policy-rate poll interval must be positive" }
        require(maxObservationAgeDays > 0) { "policy-rate maximum observation age must be positive" }
    }

    override fun next(): Tick? {
        while (!closed.get()) {
            if (pending.isNotEmpty()) return pending.removeFirst()
            if (!firstPoll) Thread.sleep(pollIntervalMs)
            firstPoll = false
            poll()
        }
        return null
    }

    private fun poll() {
        val observedAtMs = clock.now()
        val today = Instant.ofEpochMilli(observedAtMs).atZone(ZoneOffset.UTC).toLocalDate()
        val from = today.minusDays(14)
        symbols.values.distinct().forEach { refresh(it, from, today) }
        symbols.forEach { (symbol, series) ->
            val latest =
                store.read(series.id, from, today).maxByOrNull { it.availableAtMs ?: Long.MIN_VALUE }
                    ?: error("official policy-rate source returned no recent value for $symbol")
            check(!latest.date.isBefore(today.minusDays(maxObservationAgeDays))) {
                "official policy-rate source is stale for $symbol: latest=${latest.date}, today=$today"
            }
            if (lastValues[symbol]?.compareTo(latest.value) != 0) {
                lastValues[symbol] = latest.value
                pending.add(Tick(symbol = symbol, price = latest.value, timestamp = observedAtMs))
            }
        }
    }

    override fun close() {
        closed.set(true)
    }
}
