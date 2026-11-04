package com.qkt.backtest

import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.ContractCatalogRegistry
import com.qkt.instrument.FuturesRoot
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class Mt5SimSymbolsTest {
    private val root = "PAPER:BTC_USDC"
    private val perpetual = "PAPER:BTC_USDC_PERPETUAL"
    private val cfd = "EXNESS:BTCUSD"

    private val registry =
        ContractCatalogRegistry(
            listOf(
                FuturesRoot(
                    root = root,
                    currency = "USD",
                    multiplier = BigDecimal.ONE,
                    tickSize = BigDecimal("0.5"),
                    volumeStep = BigDecimal("0.001"),
                    volumeMin = BigDecimal("0.001"),
                    volumeMax = BigDecimal("100"),
                    calendar = null,
                    exchangeFeePerContract = BigDecimal.ZERO,
                    takerFeeRate = BigDecimal("0.0005"),
                    margin = null,
                    perpetual = "BTC_USDC_PERPETUAL",
                ),
            ),
            mapOf(root to ContractCatalog(root, emptyList())),
        )

    @Test
    fun `under mt5-sim a perpetual fills on the exchange simulator and only the cfd on mt5-sim`() {
        assertThat(mt5SimSymbols(BrokerKind.MT5_SIM, registry, listOf(cfd, perpetual))).containsExactly(cfd)
    }

    @Test
    fun `the paper broker routes nothing to mt5-sim`() {
        assertThat(mt5SimSymbols(BrokerKind.PAPER, registry, listOf(cfd, perpetual))).isEmpty()
    }
}
