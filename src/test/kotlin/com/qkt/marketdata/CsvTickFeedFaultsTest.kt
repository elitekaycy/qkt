package com.qkt.marketdata

import com.qkt.common.Side
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CsvTickFeedFaultsTest {
    @TempDir
    lateinit var dir: Path

    private val header = CsvTickFeed.EXPECTED_HEADER

    @Test
    fun `an unreadable number is refused naming the file line and column`() {
        val file = Files.write(dir.resolve("day.csv"), listOf(header, "0,X,100,lots,,,,"))

        CsvTickFeed(file).use { feed ->
            assertThatThrownBy { feed.next() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessage("$file:2: invalid volume: 'lots'")
        }
    }

    @Test
    fun `a gzip cut short mid-file fails naming the file and the line reached`() {
        val rows = (0 until 20_000).joinToString("\n", prefix = "$header\n") { "$it,X,${100_000 + it * 7},$it,,,," }
        val zipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(rows.toByteArray()) } }
        val bytes = zipped.toByteArray()
        val file = Files.write(dir.resolve("day.csv.gz"), bytes.copyOf(bytes.size - 64))

        CsvTickFeed(file).use { feed ->
            assertThat(feed.next()!!.timestamp).isZero()
            assertThatThrownBy { generateSequence { feed.next() }.count() }
                .isInstanceOf(IOException::class.java)
                .hasMessageMatching("unreadable tick data at .*day\\.csv\\.gz:\\d+: .*")
        }
    }

    @Test
    fun `a provider without quote depth executes and marks at its last price`() {
        val provider =
            object : MarketPriceProvider {
                override fun lastPrice(symbol: String): BigDecimal? = if (symbol == "X") BigDecimal("7") else null
            }

        assertThat(provider.executionPrice("X", Side.BUY)).isEqualByComparingTo("7")
        assertThat(provider.executionPrice("Y", Side.SELL)).isNull()
        assertThat(provider.closingMark("X", 0)).isEqualByComparingTo("7")
        assertThat(provider.closingMark("X", -1)).isEqualByComparingTo("7")
    }
}
