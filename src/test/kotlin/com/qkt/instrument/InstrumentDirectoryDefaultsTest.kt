package com.qkt.instrument

import com.qkt.common.FixedClock
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstrumentDirectoryDefaultsTest {
    private val optionRoot =
        OptionRoot(
            "DERIBIT:BTC_USDC",
            "USDC",
            BigDecimal.ONE,
            TickSteps(BigDecimal("5")),
            BigDecimal("0.01"),
            BigDecimal("0.01"),
            "btc_usdc",
            chains = QuoteSource.BOOK,
        )

    @Test
    fun `a futures directory has no history store or funding rates unless it says so`() {
        val bare =
            object : FuturesDirectory {
                override fun root(rootId: String): FuturesRoot? = null

                override fun catalog(rootId: String): ContractCatalog? = null

                override fun history(rootId: String): RollHistory? = null

                override fun rootOfContinuous(symbol: String): String? = null
            }

        assertThat(bare.historyStore()).isNull()
        assertThat(bare.fundingRates("BINANCE_UM:BTCUSDT")).isNull()
    }

    @Test
    fun `an option catalog source cannot count its listings unless it says so`() {
        val source =
            object : OptionCatalogSource {
                override fun build(
                    root: OptionRoot,
                    warn: (String) -> Unit,
                ): OptionCatalog = OptionCatalog(root.root, emptyList())
            }

        assertThat(source.listed("DERIBIT:BTC_USDC")).isNull()
        assertThat(source.build(optionRoot).contracts).isEmpty()
    }

    @Test
    fun `an option directory treats any symbol with a declared root as an option code`() {
        val directory =
            object : OptionDirectory {
                override val dataRoot: Path? = null

                override fun root(name: String): OptionRoot? = null

                override fun listings(name: String): Map<String, OptionListing> = emptyMap()

                override fun optionRoot(qktSymbol: String): OptionRoot? =
                    optionRoot.takeIf { qktSymbol.startsWith("DERIBIT:") }

                override fun venueName(qktSymbol: String): String? = null

                override fun deliveryPrice(qktSymbol: String): BigDecimal? = null
            }

        assertThat(directory.isOptionCode("DERIBIT:BTC_USDC_27SEP24_60000_C")).isTrue()
        assertThat(directory.isOptionCode("EXNESS:XAUUSD")).isFalse()
    }

    @Test
    fun `a refreshing catalog explains a declared contract missing from the catalog`(
        @TempDir dir: Path,
    ) {
        OptionCatalogStore(dir).write(OptionCatalog(optionRoot.root, emptyList()))
        val catalogs = RefreshingOptionCatalogs(listOf(optionRoot), dir, FixedClock(0L))

        assertThat(catalogs.missingReason("DERIBIT:BTC_USDC_27SEP24_60000_C"))
            .contains("not in the catalog of DERIBIT:BTC_USDC")
        assertThat(catalogs.missingReason("EXNESS:XAUUSD")).isNull()
    }

    @Test
    fun `a registry loaded without a funding store models no funding`(
        @TempDir dir: Path,
    ) {
        val root =
            FuturesRoot(
                "BINANCE_UM:BTCUSDT",
                "USDT",
                BigDecimal.ONE,
                BigDecimal("0.1"),
                BigDecimal("0.001"),
                BigDecimal("0.001"),
                null,
                "crypto",
                BigDecimal.ZERO,
                BigDecimal("0.0005"),
                null,
            )
        val registry = ContractCatalogRegistry.load(listOf(root), ContractCatalogStore(dir), RollHistoryStore(dir))

        assertThat(registry.fundingRates("BINANCE_UM:BTCUSDT")).isNull()
        assertThat(registry.catalog(root.root)).isNull()
    }
}
