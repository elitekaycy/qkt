package com.qkt.instrument

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OptionRootsFileShapesTest {
    private fun load(
        dir: Path,
        body: String,
    ): List<OptionRoot> = OptionRootsFile.load(dir.resolve("instruments.yaml").also { Files.writeString(it, body) })

    private fun root(
        extra: String = "",
        name: String = "DERIBIT:BTC_USDC",
    ) = "  - { root: $name, currency: USDC, contractSize: 1, tickSize: 5, " +
        "volumeStep: 0.01, volumeMin: 0.01, underlyingIndex: btc_usdc$extra }\n"

    @Test
    fun `the file and its section must have the right shapes`(
        @TempDir dir: Path,
    ) {
        assertThatThrownBy { load(dir, "- a\n- b\n") }.hasMessageContaining("top-level must be a map")
        assertThatThrownBy { load(dir, "options: 3\n") }.hasMessageContaining("'options' must be a list")
        assertThatThrownBy { load(dir, "options:\n  - 3\n") }.hasMessageContaining("options entry 0 must be a map")
        assertThatThrownBy { load(dir, "options:\n" + root() + root()) }
            .hasMessageContaining("duplicate options root 'DERIBIT:BTC_USDC'")
    }

    @Test
    fun `an entry without a root is named by its index`(
        @TempDir dir: Path,
    ) {
        assertThatThrownBy { load(dir, "options:\n  - { currency: USDC }\n") }
            .hasMessageContaining("options root options entry 0: missing required key 'root'")
    }

    @Test
    fun `chains are trade or book`(
        @TempDir dir: Path,
    ) {
        val trade = load(dir, "options:\n" + root(", chains: trade, markSpread: 0.05")).single()
        assertThat(trade.chains).isEqualTo(QuoteSource.TRADE)
        assertThat(load(dir, "options:\n" + root(", chains: book")).single().chains).isEqualTo(QuoteSource.BOOK)
        assertThatThrownBy { load(dir, "options:\n" + root(", chains: quotes")) }
            .hasMessageContaining("chains must be trade or book, got 'quotes'")
    }

    @Test
    fun `tick steps must be a list of above and tick maps`(
        @TempDir dir: Path,
    ) {
        assertThatThrownBy { load(dir, "options:\n" + root(", tickSteps: 3")) }
            .hasMessageContaining("tickSteps must be a list of { above, tick }")
        assertThatThrownBy { load(dir, "options:\n" + root(", tickSteps: [3]")) }
            .hasMessageContaining("each tickSteps entry must be a map")
        assertThatThrownBy { load(dir, "options:\n" + root(", tickSteps: [{ above: 1, step: 2 }]")) }
            .hasMessageContaining("unknown tickSteps key(s) [step]")
        assertThatThrownBy { load(dir, "options:\n" + root(", tickSteps: [{ above: 1 }]")) }
            .hasMessageContaining("tickSteps missing 'tick'")
    }

    @Test
    fun `optional figures are read when present`(
        @TempDir dir: Path,
    ) {
        val parsed =
            load(
                dir,
                "options:\n" +
                    root(
                        ", exchangeFeePerContract: 0.1, markSpread: 0.05, maxQuoteAgeMinutes: 30, " +
                            "feeCapRate: 0.125, deliveryFeeRate: 0.00015",
                    ),
            ).single()

        assertThat(parsed.exchangeFeePerContract).isEqualByComparingTo("0.1")
        assertThat(parsed.markSpread).isEqualByComparingTo("0.05")
        assertThat(parsed.maxQuoteAgeMinutes).isEqualTo(30)
        assertThat(parsed.feeCapRate).isEqualByComparingTo("0.125")
        assertThat(parsed.deliveryFeeRate).isEqualByComparingTo("0.00015")
    }
}
