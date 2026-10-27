package com.qkt.cli

import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.TradeMode
import com.qkt.instrument.VenueInstrumentSpec
import com.qkt.instrument.YamlInstrumentRegistry
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.DayOfWeek
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/** A stock CFD's folder and trade mode survive `instruments pull` into the file a backtest loads. */
class InstrumentEntryStockFieldsTest {
    private fun stock(
        symbol: String,
        mode: TradeMode,
    ) = InstrumentMeta(
        qktSymbol = symbol,
        contractSize = BigDecimal(100),
        volumeStep = BigDecimal("0.01"),
        volumeMin = BigDecimal("0.01"),
        volumeMax = BigDecimal(10),
        pointSize = BigDecimal("0.01"),
        digits = 2,
        tradeStopsLevelPoints = 0,
        swapLongPoints = BigDecimal("-6.1"),
        swapShortPoints = BigDecimal("-5.3"),
        swapTripleDay = DayOfWeek.FRIDAY,
        category = "Stocks",
        tradeMode = mode,
    )

    @Test
    fun `category and a restricted trade mode are written and read back, and full is the default`(
        @TempDir dir: Path,
    ) {
        val text =
            "instruments:\n" +
                VenueInstrumentSpec(stock("EXNESS:AAPL", TradeMode.FULL), emptyMap()).entryText() +
                VenueInstrumentSpec(stock("EXNESS:FB", TradeMode.CLOSE_ONLY), emptyMap()).entryText()
        assertThat(text).contains("category: Stocks").contains("tradeMode: close_only")
        assertThat(text.lines().count { it.trim().startsWith("tradeMode:") }).isEqualTo(1)
        val file = dir.resolve("instruments.yaml")
        Files.writeString(file, text)

        val registry = YamlInstrumentRegistry.load(file)
        val aapl = registry.require("EXNESS:AAPL")
        assertThat(aapl.category).isEqualTo("Stocks")
        assertThat(aapl.tradeMode).isEqualTo(TradeMode.FULL)
        assertThat(aapl.swapTripleDay).isEqualTo(DayOfWeek.FRIDAY)
        assertThat(registry.require("EXNESS:FB").tradeMode).isEqualTo(TradeMode.CLOSE_ONLY)
    }

    @Test
    fun `an unknown trade mode is refused naming the entry and the modes qkt knows`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("instruments.yaml")
        Files.writeString(
            file,
            """
            instruments:
              - qktSymbol: X
                contractSize: 1
                volumeStep: 0.01
                volumeMin: 0.01
                pointSize: 0.01
                digits: 2
                tradeStopsLevelPoints: 0
                tradeMode: sometimes
            """.trimIndent(),
        )
        val error = assertThrows<IllegalStateException> { YamlInstrumentRegistry.load(file) }
        assertThat(error.message).contains("entry 0").contains("sometimes").contains("close_only")
    }

    @Test
    fun `trade modes open and close as MT5 defines them`() {
        assertThat(TradeMode.ofMt5(0)).isEqualTo(TradeMode.DISABLED)
        assertThat(TradeMode.ofMt5(3)).isEqualTo(TradeMode.CLOSE_ONLY)
        assertThat(TradeMode.ofMt5(4)).isEqualTo(TradeMode.FULL)
        assertThat(TradeMode.ofMt5(9)).isNull()
        assertThat(TradeMode.FULL.allowsOpening(buy = true)).isTrue()
        assertThat(TradeMode.LONG_ONLY.allowsOpening(buy = false)).isFalse()
        assertThat(TradeMode.SHORT_ONLY.allowsOpening(buy = false)).isTrue()
        assertThat(TradeMode.CLOSE_ONLY.allowsOpening(buy = true)).isFalse()
        assertThat(TradeMode.CLOSE_ONLY.allowsClosing).isTrue()
        assertThat(TradeMode.DISABLED.allowsClosing).isFalse()
        assertThat(TradeMode.parse("Close-Only")).isEqualTo(TradeMode.CLOSE_ONLY)
    }
}
