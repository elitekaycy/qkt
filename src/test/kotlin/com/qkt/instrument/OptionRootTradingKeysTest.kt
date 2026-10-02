package com.qkt.instrument

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OptionRootTradingKeysTest {
    private fun load(
        dir: Path,
        extra: String,
    ): OptionRoot =
        OptionRootsFile
            .load(
                dir.resolve("instruments.yaml").also {
                    Files.writeString(
                        it,
                        "options:\n  - { root: DERIBIT:BTC_USDC, currency: USDC, contractSize: 1, tickSize: 5, " +
                            "volumeStep: 0.01, volumeMin: 0.01, underlyingIndex: btc_usdc$extra }\n",
                    )
                },
            ).single()

    @Test
    fun `the trading keys are read and default to nothing traded`(
        @TempDir dir: Path,
    ) {
        val plain = load(dir, "")
        assertThat(plain.chains).isNull()
        assertThat(plain.maxQuoteAgeMinutes).isEqualTo(60)
        assertThat(plain.feeCapRate).isNull()
        assertThat(plain.deliveryFeeRate).isZero()

        val traded =
            load(
                dir,
                ", chains: trade, markSpread: 0.05, maxQuoteAgeMinutes: 90, feeCapRate: 0.125, deliveryFeeRate: 0.00015",
            )
        assertThat(traded.chains).isEqualTo(QuoteSource.TRADE)
        assertThat(traded.markSpread).isEqualByComparingTo("0.05")
        assertThat(traded.maxQuoteAgeMinutes).isEqualTo(90)
        assertThat(traded.feeCapRate).isEqualByComparingTo("0.125")
        assertThat(traded.deliveryFeeRate).isEqualByComparingTo("0.00015")
        assertThat(load(dir, ", chains: book").chains).isEqualTo(QuoteSource.BOOK)
    }

    @Test
    fun `a trade-built chain needs a spread and every rate stays in range`(
        @TempDir dir: Path,
    ) {
        assertThatThrownBy { load(dir, ", chains: trade") }.hasMessageContaining("markSpread")
        assertThatThrownBy { load(dir, ", chains: quotes") }.hasMessageContaining("chains")
        assertThatThrownBy { load(dir, ", chains: book, markSpread: 1") }.hasMessageContaining("markSpread")
        assertThatThrownBy { load(dir, ", feeCapRate: 0") }.hasMessageContaining("feeCapRate")
        assertThatThrownBy { load(dir, ", feeCapRate: 1.5") }.hasMessageContaining("feeCapRate")
        assertThatThrownBy { load(dir, ", deliveryFeeRate: -0.1") }.hasMessageContaining("deliveryFeeRate")
        assertThatThrownBy { load(dir, ", maxQuoteAgeMinutes: 0") }.hasMessageContaining("maxQuoteAgeMinutes")
    }
}
