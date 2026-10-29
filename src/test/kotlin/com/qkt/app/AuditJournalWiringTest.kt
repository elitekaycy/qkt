package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.OrderEvent
import com.qkt.events.TickEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.Tick
import com.qkt.observe.EngineAuditJournal
import com.qkt.positions.StrategyPositionTracker
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AuditJournalWiringTest {
    private val clock = FixedClock(time = 1_700_000_000_000L)
    private val call = "DERIBIT:BTC_USDC_31OCT26_80000_C"
    private val perp = "DERIBIT:BTC_USDC_PERPETUAL"

    private fun tick(symbol: String) =
        TickEvent(Tick(symbol = symbol, price = Money.of("1500"), timestamp = clock.now()), timestamp = clock.now())

    @Test
    fun `a session fed a whole option root journals its stream and traded contracts, not every chain quote`(
        @TempDir tmp: Path,
    ) {
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val journal = EngineAuditJournal(tmp, "alpha", clock)
        val positions = StrategyPositionTracker().account
        wireAuditJournal(bus, journal, listOf(perp, "OPTIONS:DERIBIT.BTC_USDC"), positions)

        repeat(500) { bus.publish(tick(call)) }
        bus.publish(tick(perp))
        bus.publish(
            OrderEvent(
                OrderRequest.Market(
                    id = "o-1",
                    symbol = call,
                    side = Side.BUY,
                    quantity = Money.of("0.01"),
                    timeInForce = TimeInForce.GTC,
                    strategyId = "alpha",
                    timestamp = clock.now(),
                ),
            ),
        )
        bus.publish(tick(call))
        journal.close()

        val types =
            Files.readAllLines(tmp.resolve("alpha/audit-2023-11-14.jsonl")).map { line ->
                listOf(""""eventType":"com\.qkt\.events\.(\w+)"""", """"symbol":"([^"]+)"""").map {
                    Regex(it).find(line)!!.groupValues[1]
                }
            }
        assertThat(types).containsExactly(
            listOf("TickEvent", perp),
            listOf("OrderEvent", call),
            listOf("TickEvent", call),
        )
        assertThat(journal.health().omittedChainQuotes).isEqualTo(500L)
        assertThat(journal.health().lossy).isFalse()
    }
}
