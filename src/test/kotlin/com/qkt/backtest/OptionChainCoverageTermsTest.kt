package com.qkt.backtest

import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.OptionDirectory
import com.qkt.instrument.OptionListing
import com.qkt.instrument.OptionRoot
import com.qkt.instrument.TickSteps
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class OptionChainCoverageTermsTest {
    private val root =
        OptionRoot(
            "DERIBIT:BTC_USDC",
            "USDC",
            BigDecimal.ONE,
            TickSteps(BigDecimal("5")),
            BigDecimal("0.01"),
            BigDecimal("0.01"),
            "btc_usdc",
        )

    /** Calls every DERIBIT symbol an option, but resolves no metadata for any of them. */
    private val registry =
        object : InstrumentRegistry {
            override fun lookup(qktSymbol: String): InstrumentMeta? = null

            override fun options(): OptionDirectory =
                object : OptionDirectory {
                    override val dataRoot: Path? = null

                    override fun root(name: String): OptionRoot? = root

                    override fun listings(name: String): Map<String, OptionListing> = emptyMap()

                    override fun optionRoot(qktSymbol: String): OptionRoot? =
                        root.takeIf { qktSymbol.startsWith("DERIBIT:") }

                    override fun venueName(qktSymbol: String): String? = null

                    override fun deliveryPrice(qktSymbol: String): BigDecimal? = null
                }
        }

    @Test
    fun `an option symbol without option terms is refused by name`() {
        val day = LocalDate.of(2026, 10, 1)

        assertThatThrownBy {
            OptionChainCoverage.ensure(registry, listOf("DERIBIT:BTC_USDC_2OCT26_92000_C"), day, day, false)
        }.hasMessage("DERIBIT:BTC_USDC_2OCT26_92000_C has no option terms")
    }
}
