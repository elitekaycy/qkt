package com.qkt.instrument

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OptionRootsFileTest {
    private fun load(
        dir: Path,
        body: String,
    ): List<OptionRoot> =
        OptionRootsFile.load(
            dir.resolve("instruments.yaml").also {
                Files.writeString(it, body.trimIndent())
            },
        )

    private val btc =
        """
        options:
          - root: DERIBIT:BTC_USDC
            currency: USDC
            contractSize: 1
            tickSize: 5
            tickSteps: [{ above: 1000, tick: 20 }]
            volumeStep: 0.01
            volumeMin: 0.01
            underlyingIndex: btc_usdc
            takerFeeRate: 0.0003
        """

    @Test
    fun `an option root is parsed and gives each contract its metadata`(
        @TempDir dir: Path,
    ) {
        val root = load(dir, btc).single()
        val expiry = Instant.parse("2024-09-27T08:00:00Z").toEpochMilli()
        val contract = OptionContract("BTC_USDC-27SEP24-60000-C", BigDecimal("60000"), OptionRight.CALL, expiry)

        val meta = root.metaFor("DERIBIT:BTC_USDC-27SEP24-60000-C", contract)

        assertThat(meta.currency).isEqualTo("USDC")
        assertThat(meta.contractSize).isEqualByComparingTo("1")
        assertThat(meta.pointSize).isEqualByComparingTo("5")
        val terms = meta.derivative as OptionTerms
        assertThat(terms.root).isEqualTo("DERIBIT:BTC_USDC")
        assertThat(terms.underlyingIndex).isEqualTo("btc_usdc")
        assertThat(terms.strike).isEqualByComparingTo("60000")
        assertThat(terms.right).isEqualTo(OptionRight.CALL)
        assertThat(terms.expiryMs).isEqualTo(expiry)
        assertThat(terms.tickSteps.tickAt(BigDecimal("1500"))).isEqualByComparingTo("20")
        assertThat(terms.takerFeeRate).isEqualByComparingTo("0.0003")
    }

    @Test
    fun `a file without an options section has no option roots`(
        @TempDir dir: Path,
    ) {
        assertThat(load(dir, "instruments: []")).isEmpty()
    }

    @Test
    fun `unknown keys, missing keys and misspelled sections are refused by name`(
        @TempDir dir: Path,
    ) {
        assertThatThrownBy { load(dir, btc.replace("contractSize", "multiplier")) }
            .hasMessageContaining("DERIBIT:BTC_USDC")
            .hasMessageContaining("multiplier")
        assertThatThrownBy {
            load(dir, btc.lines().filterNot { "underlyingIndex" in it }.joinToString("\n"))
        }.hasMessageContaining("underlyingIndex")
        assertThatThrownBy { load(dir, btc.replace("options:", "option:")) }.hasMessageContaining("options:")
    }

    @Test
    fun `an invalid root spec names the root`(
        @TempDir dir: Path,
    ) {
        assertThatThrownBy { load(dir, btc.replace("contractSize: 1", "contractSize: 0")) }
            .hasMessageContaining("options root DERIBIT:BTC_USDC")
            .hasMessageContaining("contractSize")
    }
}
