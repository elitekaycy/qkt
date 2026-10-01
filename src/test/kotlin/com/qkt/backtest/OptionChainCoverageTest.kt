package com.qkt.backtest

import com.qkt.derivatives.options.chain.OptionChainFixture
import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OptionChainCoverageTest {
    private fun day(iso: String) = LocalDate.parse(iso)

    @Test
    fun `a missing chain day before expiry fails naming the fetch, days after expiry are not needed`(
        @TempDir dir: Path,
    ) {
        val f = OptionChainFixture(dir)
        f.store(Triple("2026-10-02T01:00:00Z", "100", 0L))
        val symbols = listOf(f.symbol, "EXNESS:XAUUSD")

        assertThatThrownBy {
            OptionChainCoverage.ensure(
                f.registry,
                symbols,
                day("2026-10-01"),
                day("2026-10-05"),
                allowIncomplete = false,
            )
        }.isInstanceOf(IncompleteDataException::class.java)
            .hasMessageContaining("2026-10-01")
            .hasMessageContaining("qkt fetch DERIBIT:BTC_USDC --chains --from 2026-10-01 --to 2026-10-01")
        assertThatCode {
            OptionChainCoverage.ensure(
                f.registry,
                symbols,
                day("2026-10-02"),
                day("2026-10-05"),
                allowIncomplete = false,
            )
        }.doesNotThrowAnyException()
        assertThatCode {
            OptionChainCoverage.ensure(
                f.registry,
                symbols,
                day("2026-10-01"),
                day("2026-10-05"),
                allowIncomplete = true,
            )
        }.doesNotThrowAnyException()
    }

    @Test
    fun `an analytics stream needs its root's chain on every day of the run`(
        @TempDir dir: Path,
    ) {
        val f = OptionChainFixture(dir)
        f.store(Triple("2026-10-02T01:00:00Z", "100", 0L))
        val stream = listOf("CHAIN:DERIBIT.BTC_USDC.atm_iv.30d")

        assertThatThrownBy {
            OptionChainCoverage.ensure(
                f.registry,
                stream,
                day("2026-10-02"),
                day("2026-10-03"),
                allowIncomplete = false,
            )
        }.hasMessageContaining("2026-10-03")
            .hasMessageContaining("qkt fetch DERIBIT:BTC_USDC --chains --from 2026-10-03 --to 2026-10-03")
        assertThatCode {
            OptionChainCoverage.ensure(
                f.registry,
                stream,
                day("2026-10-02"),
                day("2026-10-02"),
                allowIncomplete = false,
            )
        }.doesNotThrowAnyException()
    }
}
