package com.qkt.instrument

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FuturesRootsFileTest {
    private fun write(
        dir: Path,
        body: String,
    ): Path = dir.resolve("instruments.yaml").also { Files.writeString(it, body.trimIndent()) }

    @Test
    fun `a file without a futures section has no roots`(
        @TempDir dir: Path,
    ) {
        assertThat(FuturesRootsFile.load(write(dir, "instruments: []"))).isEmpty()
    }

    @Test
    fun `a root is parsed with optional fields defaulted`(
        @TempDir dir: Path,
    ) {
        val roots =
            FuturesRootsFile.load(
                write(
                    dir,
                    """
                    futures:
                      - root: BINANCE_UM:BTCUSDT
                        currency: USDT
                        multiplier: 1
                        tickSize: 0.1
                        volumeStep: 0.001
                        volumeMin: 0.001
                        takerFeeRate: 0.0005
                        margin: { initial: 0.05, maintenance: 0.025, basis: notional }
                    """,
                ),
            )
        val btc = roots.single()
        assertThat(btc.root).isEqualTo("BINANCE_UM:BTCUSDT")
        assertThat(btc.exchangeFeePerContract).isEqualByComparingTo("0")
        assertThat(btc.takerFeeRate).isEqualByComparingTo("0.0005")
        assertThat(btc.volumeMax).isNull()
        assertThat(btc.calendar).isNull()
        assertThat(btc.margin?.basis).isEqualTo(MarginBasis.NOTIONAL)
    }

    @Test
    fun `an unknown key is refused with the allowed keys`(
        @TempDir dir: Path,
    ) {
        val f =
            write(
                dir,
                """
                futures:
                  - root: CME:ES
                    currency: USD
                    multipler: 50
                    tickSize: 0.25
                    volumeStep: 1
                    volumeMin: 1
                """,
            )
        assertThatThrownBy { FuturesRootsFile.load(f) }
            .hasMessageContaining("multipler")
            .hasMessageContaining("multiplier")
    }

    @Test
    fun `a missing required key names the root and the key`(
        @TempDir dir: Path,
    ) {
        val f = write(dir, "futures:\n  - root: CME:ES\n    currency: USD\n")
        assertThatThrownBy {
            FuturesRootsFile.load(
                f,
            )
        }.hasMessageContaining("CME:ES").hasMessageContaining("multiplier")
    }

    @Test
    fun `a duplicate root is refused`(
        @TempDir dir: Path,
    ) {
        val entry = "  - { root: CME:ES, currency: USD, multiplier: 50, tickSize: 0.25, volumeStep: 1, volumeMin: 1 }\n"
        assertThatThrownBy {
            FuturesRootsFile.load(
                write(dir, "futures:\n$entry$entry"),
            )
        }.hasMessageContaining("duplicate")
    }

    @Test
    fun `a margin that is not a map is refused`(
        @TempDir dir: Path,
    ) {
        val f =
            write(
                dir,
                "futures:\n  - { root: CME:ES, currency: USD, multiplier: 50, tickSize: 0.25, volumeStep: 1, volumeMin: 1, margin: 5 }\n",
            )
        assertThatThrownBy { FuturesRootsFile.load(f) }.hasMessageContaining("CME:ES").hasMessageContaining("margin")
    }

    @Test
    fun `a non-numeric value names the root and the key`(
        @TempDir dir: Path,
    ) {
        val f =
            write(
                dir,
                "futures:\n  - { root: CME:ES, currency: USD, multiplier: fifty, tickSize: 0.25, volumeStep: 1, volumeMin: 1 }\n",
            )
        assertThatThrownBy {
            FuturesRootsFile.load(
                f,
            )
        }.hasMessageContaining("CME:ES").hasMessageContaining("multiplier")
    }

    @Test
    fun `a misspelled futures section is refused`(
        @TempDir dir: Path,
    ) {
        val f =
            write(
                dir,
                "future:\n  - { root: CME:ES, currency: USD, multiplier: 50, tickSize: 0.25, volumeStep: 1, volumeMin: 1 }\n",
            )
        assertThatThrownBy { FuturesRootsFile.load(f) }.hasMessageContaining("future").hasMessageContaining("futures")
    }
}
