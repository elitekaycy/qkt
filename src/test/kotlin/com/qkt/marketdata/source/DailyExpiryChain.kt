package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.derivatives.futures.RollHistoryBuilder
import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.ContractCatalogRegistry
import com.qkt.instrument.FuturesDirectory
import com.qkt.instrument.FuturesRoot
import com.qkt.instrument.ListedContract
import com.qkt.instrument.PriceAdjustment
import com.qkt.instrument.RollHistoryStore
import com.qkt.instrument.RollPolicy
import com.qkt.marketdata.Candle
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * `PAPER_DERIBIT:ETH_USDC`, a contract expiring every day at 08:00 from 11-03 to 11-08 2026, rolled a day before
 * expiry, with one closed 1-minute bar per contract every hour from 11-01 to 11-06 (qkt#1647). Its stored history
 * was last measured at the 11-03 roll: a daemon down from then on missed the 11-04 and 11-05 rolls.
 */
internal object DailyExpiryChain {
    fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    const val FRONT = "PAPER_DERIBIT:ETH_USDC@front"
    val days = listOf(3, 4, 5, 6, 7, 8)
    val root =
        FuturesRoot(
            "PAPER_DERIBIT:ETH_USDC",
            "USDC",
            BigDecimal.ONE,
            BigDecimal("0.25"),
            BigDecimal("0.001"),
            BigDecimal("0.001"),
            null,
            null,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null,
            RollPolicy(1, LocalTime.of(8, 0), PriceAdjustment.PANAMA),
        )
    val catalog =
        ContractCatalog(root.root, days.map { ListedContract("ETH_USDC_${it}NOV26", ms("2026-11-0${it}T08:00:00Z")) })

    val bars =
        days.flatMap { day ->
            (0 until 6 * 24).map { hour ->
                val start = ms("2026-11-01T00:00:00Z") + hour * 3_600_000L
                val close = BigDecimal(2_300 + day * 10 + hour % 7)
                Candle("ETH_USDC_${day}NOV26", close, close, close, close, BigDecimal.ONE, start, start + 60_000)
            }
        }

    /** The venue account: its contracts' closed bars, and [live] ticks once each feed opens. */
    fun account(live: List<com.qkt.marketdata.Tick> = emptyList()): MarketSource =
        object : MarketSource {
            override val name = "paper"
            override val capabilities = emptySet<MarketSourceCapability>()

            override fun supports(symbol: String) = true

            override fun liveTicks(symbols: List<String>): com.qkt.marketdata.TickFeed {
                val queue = ArrayDeque(live)
                return object : com.qkt.marketdata.TickFeed {
                    override fun next() = queue.removeFirstOrNull()
                }
            }

            override fun bars(
                symbol: String,
                window: TimeWindow,
                range: TimeRange,
            ): Sequence<Candle> =
                bars.asSequence().filter {
                    "${root.venue}:${it.symbol}" == symbol &&
                        it.startTime in range.from.toEpochMilli() until range.to.toEpochMilli()
                }
        }

    /** Writes the history last measured at the 11-03 roll into [store], unless it holds one already. */
    fun stored(store: RollHistoryStore): RollHistoryStore {
        if (store.read(root.root) != null) return store
        val byDay = { code: String, day: LocalDate ->
            bars.filter {
                it.symbol == code && Instant.ofEpochMilli(it.startTime).atZone(ZoneOffset.UTC).toLocalDate() == day
            }
        }
        val built = RollHistoryBuilder(bars = byDay).build(root, catalog)
        store.write(built.copy(rolls = built.rolls.filter { it.atMs <= ms("2026-11-03T08:00:00Z") }))
        return store
    }

    /** The declared root over what [store] holds now, as a session starting now reads it. */
    fun registry(store: RollHistoryStore): ContractCatalogRegistry =
        ContractCatalogRegistry(
            listOf(root),
            mapOf(root.root to catalog),
            mapOf(root.root to requireNotNull(store.read(root.root))),
        )

    fun directory(store: RollHistoryStore): FuturesDirectory = requireNotNull(registry(store).futures())
}
