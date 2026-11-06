package com.qkt.marketdata.ingest.plan

import com.qkt.marketdata.ingest.map.FieldExpr
import com.qkt.marketdata.ingest.rows.CellRef
import com.qkt.marketdata.ingest.rows.CsvDialect
import com.qkt.marketdata.ingest.rows.InputLayout
import com.qkt.marketdata.ingest.rows.JsonRow
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CellNamesTest {
    private fun csv(
        header: List<String>?,
        tickers: List<String>? = null,
    ) = InputLayout.Csv(CsvDialect(',', '.', 0, header, header?.size ?: 3, tickers = tickers), null, null)

    private fun json(names: List<String>?) = InputLayout.Json(emptyList(), null, names, names?.size ?: 0, null)

    @Test
    fun `a CSV column resolves by name ignoring case and space and by position`() {
        val names = CellNames(csv(listOf("Time", " Close ")))

        assertThat(names.resolve("close")).isEqualTo(CellRef.Index(1))
        assertThat(names.resolve("\$0")).isEqualTo(CellRef.Index(0))
        assertThat(names.resolve("[1]")).isEqualTo(CellRef.Index(1))
        assertThat(names.resolve("volume")).isNull()
    }

    @Test
    fun `a JSON key resolves as a path and the row key as itself`() {
        val names = CellNames(json(listOf("mid", "time")))

        assertThat(names.resolve("mid.o")).isEqualTo(CellRef.Path(listOf("mid", "o")))
        assertThat(names.resolve(JsonRow.KEY)).isEqualTo(CellRef.Path(listOf(JsonRow.KEY)))
        assertThat(names.resolve("bid.o")).isNull()
        assertThat(CellNames(json(null)).resolve("mid")).isNull()
    }

    @Test
    fun `a repeated column is picked by the imported ticker`() {
        val layout = csv(listOf("Price", "Close", "Close"), tickers = listOf("Ticker", "AAPL", "MSFT"))

        assertThat(CellNames(layout, "msft").resolve("Close")).isEqualTo(CellRef.Index(2))
    }

    @Test
    fun `a repeated column for a ticker the file does not hold is refused naming the tickers`() {
        val layout = csv(listOf("Price", "Close", "Close"), tickers = listOf("Ticker", "AAPL", "MSFT"))

        assertThatThrownBy { CellNames(layout, "GOOG").resolve("Close") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("several tickers (AAPL, MSFT)")
            .hasMessageContaining("'GOOG'")
    }

    @Test
    fun `a repeated column with no ticker row is refused naming its positions`() {
        val names = CellNames(csv(listOf("time", "close", "Close")))

        assertThatThrownBy { names.resolve("close") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("appears 2 times")
            .hasMessageContaining("\$1, \$2")
            .hasMessageContaining("--map close=\$1")
    }

    @Test
    fun `a missing time cell is refused listing the columns or saying there is no header`() {
        assertThatThrownBy { CellNames(csv(listOf("a", "b"))).cell("time") }
            .hasMessage("no column 'time' (columns: a, b)")
        assertThatThrownBy { CellNames(csv(null)).cell("time") }
            .hasMessageContaining("the file has no header")
        assertThat(CellNames(csv(listOf("a", "time"))).cell("time")).isEqualTo(CellRef.Index(1))
    }

    @Test
    fun `a trailing question mark makes a missing column an absent field`() {
        val names = CellNames(csv(listOf("time", "close")))

        assertThat(names.expr("volume?")).isEqualTo(FieldExpr.Missing)
        assertThatThrownBy { names.expr("volume") }.hasMessageContaining("no column")
    }
}
