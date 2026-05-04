package com.qkt.marketdata

import com.qkt.common.Money
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CsvTickFeedTest {
    @TempDir lateinit var dir: Path

    private fun writeCsv(
        name: String,
        contents: String,
    ): Path {
        val p = dir.resolve(name)
        Files.writeString(p, contents)
        return p
    }

    private fun writeGzipCsv(
        name: String,
        contents: String,
    ): Path {
        val p = dir.resolve(name)
        GZIPOutputStream(Files.newOutputStream(p)).use { it.write(contents.toByteArray()) }
        return p
    }

    private val header = "timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume"

    @Test
    fun `parses minimal trade rows`() {
        val csv = "$header\n1000,XAUUSD,2400.50,1.5,,,,\n2000,XAUUSD,2400.55,2.0,,,,"
        CsvTickFeed(writeCsv("a.csv", csv)).use { feed ->
            val t1 = feed.next()!!
            assertThat(t1.symbol).isEqualTo("XAUUSD")
            assertThat(t1.price).isEqualByComparingTo(Money.of("2400.50"))
            assertThat(t1.volume).isEqualByComparingTo(Money.of("1.5"))
            assertThat(t1.bid).isNull()
            assertThat(feed.next()!!.timestamp).isEqualTo(2000L)
            assertThat(feed.next()).isNull()
        }
    }

    @Test
    fun `parses quote rows and computes price as mid when blank`() {
        val csv = "$header\n1000,EURUSD,,,1.0841,1.0843,2,1.5"
        CsvTickFeed(writeCsv("a.csv", csv)).use { feed ->
            val t = feed.next()!!
            assertThat(t.bid).isEqualByComparingTo(Money.of("1.0841"))
            assertThat(t.ask).isEqualByComparingTo(Money.of("1.0843"))
            assertThat(t.price).isEqualByComparingTo(Money.of("1.0842"))
        }
    }

    @Test
    fun `parses full 8 column rows`() {
        val csv = "$header\n1000,XAUUSD,2400.55,2.0,2400.54,2400.56,5.0,3.0"
        CsvTickFeed(writeCsv("a.csv", csv)).use { feed ->
            val t = feed.next()!!
            assertThat(t.price).isEqualByComparingTo(Money.of("2400.55"))
            assertThat(t.bid).isEqualByComparingTo(Money.of("2400.54"))
            assertThat(t.ask).isEqualByComparingTo(Money.of("2400.56"))
            assertThat(t.askVolume).isEqualByComparingTo(Money.of("3.0"))
        }
    }

    @Test
    fun `streams gzipped CSV transparently via gz extension`() {
        val csv = "$header\n1000,XAUUSD,100,1,,,,"
        CsvTickFeed(writeGzipCsv("a.csv.gz", csv)).use { feed ->
            assertThat(feed.next()!!.timestamp).isEqualTo(1000L)
            assertThat(feed.next()).isNull()
        }
    }

    @Test
    fun `header only file returns null on first next`() {
        CsvTickFeed(writeCsv("h.csv", header)).use { feed ->
            assertThat(feed.next()).isNull()
        }
    }

    @Test
    fun `skips empty lines mid file`() {
        val csv = "$header\n1000,XAUUSD,100,1,,,,\n\n2000,XAUUSD,101,1,,,,"
        CsvTickFeed(writeCsv("a.csv", csv)).use { feed ->
            assertThat(feed.next()!!.timestamp).isEqualTo(1000L)
            assertThat(feed.next()!!.timestamp).isEqualTo(2000L)
            assertThat(feed.next()).isNull()
        }
    }

    @Test
    fun `empty file throws`() {
        assertThatThrownBy { CsvTickFeed(writeCsv("e.csv", "")) }
            .hasMessageContaining("empty CSV")
    }

    @Test
    fun `wrong header throws`() {
        val bad = "time,sym,p\n1,X,1"
        assertThatThrownBy { CsvTickFeed(writeCsv("a.csv", bad)) }
            .hasMessageContaining("unexpected header")
    }

    @Test
    fun `wrong column count throws`() {
        val csv = "$header\n1000,XAUUSD,100"
        CsvTickFeed(writeCsv("a.csv", csv)).use { feed ->
            assertThatThrownBy { feed.next() }.hasMessageContaining("expected 8 columns")
        }
    }

    @Test
    fun `unparseable timestamp throws`() {
        val csv = "$header\nabc,XAUUSD,100,,,,,"
        CsvTickFeed(writeCsv("a.csv", csv)).use { feed ->
            assertThatThrownBy { feed.next() }.hasMessageContaining("invalid timestamp")
        }
    }

    @Test
    fun `non decreasing timestamps required`() {
        val csv = "$header\n2000,X,100,,,,,\n1000,X,100,,,,,"
        CsvTickFeed(writeCsv("a.csv", csv)).use { feed ->
            feed.next()
            assertThatThrownBy { feed.next() }.hasMessageContaining("non-decreasing")
        }
    }

    @Test
    fun `empty symbol throws`() {
        val csv = "$header\n1000,,100,,,,,"
        CsvTickFeed(writeCsv("a.csv", csv)).use { feed ->
            assertThatThrownBy { feed.next() }.hasMessageContaining("empty symbol")
        }
    }

    @Test
    fun `row needs price or bid plus ask`() {
        val csv = "$header\n1000,X,,,,,,"
        CsvTickFeed(writeCsv("a.csv", csv)).use { feed ->
            assertThatThrownBy { feed.next() }.hasMessageContaining("price OR")
        }
    }

    @Test
    fun `inverted bid greater than ask throws`() {
        val csv = "$header\n1000,X,,,1.5,1.4,,"
        CsvTickFeed(writeCsv("a.csv", csv)).use { feed ->
            assertThatThrownBy { feed.next() }.hasMessageContaining("bid > ask")
        }
    }

    @Test
    fun `negative price throws`() {
        val csv = "$header\n1000,X,-1.0,,,,,"
        CsvTickFeed(writeCsv("a.csv", csv)).use { feed ->
            assertThatThrownBy { feed.next() }.hasMessageContaining("negative")
        }
    }
}
