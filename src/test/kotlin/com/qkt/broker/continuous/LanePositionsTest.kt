package com.qkt.broker.continuous

import com.qkt.broker.InstrumentSlippage
import com.qkt.broker.exchange.ExchangeSimulator
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.derivatives.futures.ContinuousChains
import com.qkt.events.TickEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.ContractCatalogRegistry
import com.qkt.instrument.FuturesRoot
import com.qkt.instrument.ListedContract
import com.qkt.instrument.PriceAdjustment
import com.qkt.instrument.RollHistory
import com.qkt.instrument.RollPolicy
import com.qkt.instrument.RollRecord
import com.qkt.marketdata.Tick
import com.qkt.pnl.ContractFeeCommission
import com.qkt.pnl.NoCommission
import com.qkt.positions.PositionProvider
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalTime
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A stream's venue sees the stream's contract positions, as a venue account holds them, across a roll. */
class LanePositionsTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private val front = "BINANCE_UM:BTCUSDT@front"
    private val sep = "BINANCE_UM:BTCUSDT_240927"
    private val dec = "BINANCE_UM:BTCUSDT_241227"
    private val root =
        FuturesRoot(
            "BINANCE_UM:BTCUSDT",
            "USDT",
            BigDecimal.ONE,
            BigDecimal("0.1"),
            BigDecimal("0.001"),
            BigDecimal("0.001"),
            null,
            null,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null,
            RollPolicy(8, LocalTime.of(8, 0), PriceAdjustment.PANAMA),
        )
    private val registry =
        ContractCatalogRegistry(
            listOf(root),
            mapOf(
                root.root to
                    ContractCatalog(
                        root.root,
                        listOf(
                            ListedContract("BTCUSDT_240628", ms("2024-06-28T08:00:00Z")),
                            ListedContract("BTCUSDT_240927", ms("2024-09-27T08:00:00Z")),
                            ListedContract("BTCUSDT_241227", ms("2024-12-27T08:00:00Z")),
                        ),
                    ),
            ),
            mapOf(
                root.root to
                    RollHistory(
                        root.root,
                        "8d@08:00",
                        listOf(
                            RollRecord(
                                ms("2024-06-20T08:00:00Z"),
                                "BTCUSDT_240628",
                                "BTCUSDT_240927",
                                "65000",
                                "65000",
                            ),
                            RollRecord(
                                ms("2024-09-19T08:00:00Z"),
                                "BTCUSDT_240927",
                                "BTCUSDT_241227",
                                "63000",
                                "63800",
                            ),
                        ),
                    ),
            ),
        )

    @Test
    fun `the venue sees each fill and the roll's legs as the stream's contract position, with its entry price`() {
        val clock = FixedClock(time = ms("2024-09-19T07:45:00Z"))
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        var seen: PositionProvider? = null
        val broker =
            ContinuousContractBroker(
                bus,
                clock,
                ContinuousChains(requireNotNull(registry.futures())),
                setOf(front),
                RollLedger(),
                ContractFillLog(),
            ) {
                venueBus,
                prices,
                positions,
                ->
                seen = positions
                val exchange =
                    ExchangeSimulator(
                        venueBus,
                        clock,
                        prices,
                        registry,
                        InstrumentSlippage,
                        ContractFeeCommission(registry, NoCommission),
                    )
                ContractVenue(exchange, exchange::onTick)
            }
        bus.publish(TickEvent(Tick(front, BigDecimal("63010"), clock.time)))
        broker.submit(
            OrderRequest.Market("entry", front, Side.BUY, BigDecimal("0.01"), TimeInForce.GTC, clock.time, "s"),
        )

        val held = seen!!.positionFor(sep)!!
        assertThat(held.quantity).isEqualByComparingTo("0.01")
        assertThat(held.avgEntryPrice).isEqualByComparingTo("63010")

        clock.time = ms("2024-09-19T08:00:00Z")
        bus.publish(TickEvent(Tick(front, BigDecimal("63000"), clock.time)))

        assertThat(seen!!.positionFor(sep)?.quantity ?: BigDecimal.ZERO).isEqualByComparingTo("0")
        assertThat(seen!!.positionFor(dec)!!.quantity).isEqualByComparingTo("0.01")
        assertThat(seen!!.positionFor(dec)!!.avgEntryPrice).isEqualByComparingTo("63800")
        assertThat(seen!!.symbols()).containsExactly(dec)
    }
}
