package com.qkt.pnl

import com.qkt.accounting.AccountCurrency
import com.qkt.accounting.AccountingConfig
import com.qkt.accounting.AccountingEngine
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.LegIntent
import com.qkt.instrument.ContractCatalogRegistry
import com.qkt.instrument.FundingRate
import com.qkt.instrument.FundingRateStore
import com.qkt.instrument.FuturesRoot
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.positions.LegRole
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Backtest funding charged as a venue charges it: on the account's net position, shared by holding. */
class FundingBookTest {
    private val perp = "BINANCE_UM:BTCUSDT"
    private val hour = 3_600_000L
    private val positions = StrategyPositionTracker()
    private val prices =
        object : MarketPriceProvider {
            override fun lastPrice(symbol: String) = BigDecimal("57000")
        }

    private fun book(dir: Path): FundingBook {
        val store =
            FundingRateStore(dir).also {
                it.merge(perp, listOf(FundingRate(8 * hour, BigDecimal("0.0001"), BigDecimal("57100"))))
            }
        val root =
            FuturesRoot(
                "BINANCE_UM:BTCUSDT",
                "USD",
                BigDecimal.ONE,
                BigDecimal("0.1"),
                BigDecimal("0.001"),
                BigDecimal("0.001"),
                null,
                "crypto",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                perpetual = "BTCUSDT",
            )
        val registry = ContractCatalogRegistry(listOf(root), emptyMap(), fundingStore = store)
        val accounting = AccountingEngine(AccountingConfig(AccountCurrency("USD")), prices) { "USD" }
        return FundingBook(registry, positions, accounting, prices, listOf("a", "b"), listOf(perp))
    }

    private fun hold(
        strategy: String,
        side: Side,
        quantity: String,
    ) = positions.applyFill(
        BrokerEvent.OrderFilled(
            "o-$strategy",
            null,
            perp,
            side,
            BigDecimal("57000"),
            BigDecimal(quantity),
            strategy,
            timestamp = hour,
        ),
        LegIntent.Open("leg-$strategy", LegRole.PRIMARY),
    )

    private fun accrued(dir: Path): Map<String, BigDecimal> {
        val charged = linkedMapOf<String, BigDecimal>()
        book(dir).accrueBetween(0, 24 * hour) { id, _, amount -> charged[id] = amount }
        return charged
    }

    @Test
    fun `a long and a short of one account each pay their own side while the account is net long`(
        @TempDir dir: Path,
    ) {
        hold("a", Side.BUY, "2")
        hold("b", Side.SELL, "1")

        val charged = accrued(dir)

        assertThat(charged.mapValues { it.value.stripTrailingZeros().toPlainString() }).containsExactly(
            java.util.Map.entry("a", "-11.42"),
            java.util.Map.entry("b", "5.71"),
        )
    }

    @Test
    fun `strategies netting to nothing pay nothing, as the venue charges the net`(
        @TempDir dir: Path,
    ) {
        hold("a", Side.BUY, "1")
        hold("b", Side.SELL, "1")

        assertThat(accrued(dir)).isEmpty()
    }
}
