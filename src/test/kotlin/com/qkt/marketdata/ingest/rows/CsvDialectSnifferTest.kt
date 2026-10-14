package com.qkt.marketdata.ingest.rows

import com.qkt.marketdata.ingest.ImportRefusal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CsvDialectSnifferTest {
    private fun sniff(vararg lines: String) = CsvDialectSniffer.sniff(lines.toList())

    @Test
    fun `MetaTrader exports are tab separated with tag headers`() {
        val d = sniff("<DATE>\t<TIME>\t<OPEN>\t<CLOSE>", "2024.03.11\t00:00:00\t2180.512\t2180.877")

        assertThat(d.delimiter).isEqualTo('\t')
        assertThat(d.header).containsExactly("<DATE>", "<TIME>", "<OPEN>", "<CLOSE>")
    }

    @Test
    fun `a semicolon file with decimal commas is told from a comma file by what its cells are`() {
        val d = sniff("time;bid;ask", "2024-01-02 10:00;1,10024;1,10031", "2024-01-02 10:01;1,10027;1,10035")

        assertThat(d.delimiter).isEqualTo(';')
        assertThat(d.decimal).isEqualTo(',')
    }

    @Test
    fun `comma numbers that could be thousands refuse`() {
        assertThatThrownBy { sniff("t;v", "2024-01-02;1,234", "2024-01-03;2,345") }
            .isInstanceOf(ImportRefusal::class.java)
            .hasMessageContaining("--decimal")
    }

    @Test
    fun `headerless files have no header`() {
        val d =
            sniff(
                "20170102 020000;1.051550;1.051970;1.051550;1.051900;0",
                "20170102 020100;1.052090;1.052090;1.051770;1.051790;0",
            )

        assertThat(d.header).isNull()
        assertThat(d.columns).isEqualTo(6)
    }

    @Test
    fun `a text column such as a trade side does not hide a header`() {
        val d =
            sniff(
                "timestamp,symbol,side,size,price",
                "1791849600.1695,BTCUSDT,Sell,0.012,81807.60",
                "1791849600.5,BTCUSDT,Buy,0.003,81807.70",
            )

        assertThat(d.header).containsExactly("timestamp", "symbol", "side", "size", "price")
    }

    @Test
    fun `a banner line before the header is skipped`() {
        val d =
            sniff(
                "https://www.CryptoDataDownload.com",
                "unix,date,open,close",
                "1700000000,2023-11-14,1.0,1.1",
                "1700003600,2023-11-14,1.1,1.2",
            )

        assertThat(d.preamble).isEqualTo(1)
        assertThat(d.header).containsExactly("unix", "date", "open", "close")
    }

    @Test
    fun `whole-line quoting and a trailing delimiter are both undone`() {
        assertThat(sniff("\"Date,Open,Close\"", "\"2008-01-02,1467.96,1447.16\"").wholeLineQuoted).isTrue()
        val trailing = sniff("t;o;c;", "2024-01-02;1;2;", "2024-01-03;2;3;")

        assertThat(trailing.dropTrailingEmpty).isTrue()
        assertThat(trailing.columns).isEqualTo(3)
    }

    @Test
    fun `an HTML page saved as csv is refused`() {
        assertThatThrownBy { sniff("<!DOCTYPE html>", "<html><body>Please verify you are human</body></html>") }
            .hasMessageContaining("HTML")
    }
}
