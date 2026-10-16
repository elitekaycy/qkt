package com.qkt.marketdata.store

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BarDayCsvStrictReadTest {
    @TempDir
    lateinit var dir: Path

    private fun read(text: String) =
        BarDayCsv.read(dir.resolve("2024-01-02.csv").also { Files.writeString(it, text) }, "SRC:X", 60_000)

    private val header = "timestamp,open,high,low,close,volume\n"

    @Test
    fun `a short row fails naming its line instead of vanishing`() {
        assertThatThrownBy { read(header + "1704153600000,1,2,1,2,5\n1704153660000,1,2\n") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("2024-01-02.csv:3")
            .hasMessageContaining("expected 6 fields")
    }

    @Test
    fun `a bad number fails naming its line`() {
        assertThatThrownBy {
            read(
                header + "1704153600000,1,2,1,x,5\n",
            )
        }.hasMessageContaining("2024-01-02.csv:2: not a number")
    }

    @Test
    fun `bars out of time order fail`() {
        assertThatThrownBy {
            read(header + "1704153660000,1,2,1,2,5\n1704153600000,1,2,1,2,5\n")
        }.hasMessageContaining("must rise")
    }

    @Test
    fun `a file without the header fails`() {
        assertThatThrownBy { read("1704153600000,1,2,1,2,5\n") }.hasMessageContaining("unexpected header")
    }

    @Test
    fun `extra columns past the sixth and blank lines are fine`() {
        val bars = read(header + "1704153600000,1,2,1,2,5,0.0002\n\n1704153660000,2,3,2,3,6,0.0003\n")

        assertThat(bars.map { it.startTime }).containsExactly(1_704_153_600_000L, 1_704_153_660_000L)
        assertThat(bars.first().endTime).isEqualTo(1_704_153_660_000L)
    }
}
