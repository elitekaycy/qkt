package com.qkt.marketdata.openinterest

import com.qkt.marketdata.store.binance.BinanceOpenInterest
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Stored open interest: one venue-neutral file per contract, figures kept as their exact text. */
class OpenInterestStoreTest {
    private val perp = "BINANCE_UM:BTCUSDT"

    private fun recorded(name: String) = javaClass.getResource("/futures/binance/$name")!!.readText()

    @Test
    fun `binance figures are base-asset open interest, known at the end of the five minutes they are stamped`() {
        val figures = BinanceOpenInterest.parse(recorded("open-interest-hist-btcusdt.json"))

        assertThat(
            figures.map { it.timeMs },
        ).containsExactly(1_791_129_900_000L, 1_791_130_200_000L, 1_791_130_500_000L)
        assertThat(figures.first().openInterest.toPlainString()).isEqualTo("98658.66000000")
    }

    @Test
    fun `binance's history older than thirty days is refused naming the limit before any request`() {
        val now = 1_791_154_801_848L
        val binance = BinanceOpenInterest(apiBaseUrl = "http://127.0.0.1:9", clock = { now })

        assertThatThrownBy { binance.figures(perp, now - 31 * 86_400_000L, now) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("last 30 days of open interest")
    }

    @Test
    fun `merged figures are kept once per time, oldest first, and read back exactly in a window`(
        @TempDir dir: Path,
    ) {
        val store = OpenInterestStore(dir)
        store.merge(perp, listOf(OpenInterest(300, BigDecimal("2.50")), OpenInterest(100, BigDecimal("1"))))

        val held = store.merge(perp, listOf(OpenInterest(300, BigDecimal("2.75")), OpenInterest(200, BigDecimal("0"))))

        assertThat(held).isEqualTo(3)
        assertThat(Files.readAllLines(dir.resolve("open_interest/BINANCE_UM/BTCUSDT.csv")))
            .containsExactly("time,open_interest", "100,1", "200,0", "300,2.75")
        assertThat(store.figures(perp, 150, 300).map { it.openInterest.toPlainString() }).containsExactly("0", "2.75")
        assertThat(store.read("BINANCE_UM:ETHUSDT")).isNull()
    }

    @Test
    fun `a malformed line fails naming the file and line, and a negative figure is refused`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("open_interest/BINANCE_UM/BTCUSDT.csv")
        Files.createDirectories(file.parent)
        Files.writeString(file, "time,open_interest\n100,1,2\n")

        assertThatThrownBy { OpenInterestStore(dir).read(perp) }.hasMessageContaining("BTCUSDT.csv line 2")
        assertThatThrownBy { OpenInterest(1, BigDecimal("-1")) }.hasMessageContaining("must not be negative")
    }

    @Test
    fun `an open-interest stream names its contract and nothing else is one`() {
        assertThat(OpenInterestSymbol.of(perp)).isEqualTo("OI:BINANCE_UM:BTCUSDT")
        assertThat(OpenInterestSymbol.contract("OI:BINANCE_UM:BTCUSDT")).isEqualTo(perp)
        assertThat(OpenInterestSymbol.contract("OI:BTCUSDT")).isNull()
        assertThat(OpenInterestSymbol.contract(perp)).isNull()
    }
}
