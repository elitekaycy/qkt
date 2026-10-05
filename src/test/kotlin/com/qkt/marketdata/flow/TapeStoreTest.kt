package com.qkt.marketdata.flow

import com.qkt.common.Side
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.zip.GZIPInputStream
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The tape and liquidations are stored a gzipped CSV a day, exactly, and read back as written. */
class TapeStoreTest {
    @TempDir lateinit var root: Path
    private val perp = "DERIBIT:BTC_USDC_PERPETUAL"
    private val day = LocalDate.parse("2026-10-04")

    private fun print(
        id: String,
        time: Long,
        size: String,
        side: Side,
    ) = Print(id, time, BigDecimal("85070.20"), BigDecimal(size), side)

    @Test
    fun `a day is stored oldest first and read back exactly, sizes and prices as their text`() {
        val store = TapeStore(root)
        store.write(
            perp,
            FlowKind.TRADES,
            day,
            listOf(print("USDC-2", 20, "0.0011", Side.SELL), print("USDC-1", 10, "0.0002", Side.BUY)),
        )

        val read = store.read(perp, FlowKind.TRADES, day)!!

        assertThat(read.map { it.id }).containsExactly("USDC-1", "USDC-2")
        assertThat(read.first().size.toPlainString()).isEqualTo("0.0002")
        assertThat(read.first().price.toPlainString()).isEqualTo("85070.20")
        assertThat(read.map { it.side }).containsExactly(Side.BUY, Side.SELL)
        val file = root.resolve("tape/DERIBIT/BTC_USDC_PERPETUAL/2026-10-04.csv.gz")
        val text = GZIPInputStream(Files.newInputStream(file)).bufferedReader().readText()
        assertThat(
            text,
        ).isEqualTo("id,time,price,size,side\nUSDC-1,10,85070.20,0.0002,buy\nUSDC-2,20,85070.20,0.0011,sell\n")
    }

    @Test
    fun `liquidations are their own series, and a day stored empty is a stored day`() {
        val store = TapeStore(root)
        store.write(perp, FlowKind.LIQUIDATIONS, day, emptyList())

        assertThat(store.has(perp, FlowKind.LIQUIDATIONS, day)).isTrue
        assertThat(store.read(perp, FlowKind.LIQUIDATIONS, day)).isEmpty()
        assertThat(store.has(perp, FlowKind.TRADES, day)).isFalse
        assertThat(store.read(perp, FlowKind.TRADES, day)).isNull()
        assertThat(
            store.dir(perp, FlowKind.LIQUIDATIONS),
        ).isEqualTo(root.resolve("liquidations/DERIBIT/BTC_USDC_PERPETUAL"))
    }

    @Test
    fun `a malformed line fails naming the file and line`() {
        val store = TapeStore(root)
        store.write(perp, FlowKind.TRADES, day, listOf(print("USDC-1", 10, "0.1", Side.BUY)))
        val file = store.dir(perp, FlowKind.TRADES).resolve("$day.csv.gz")
        java.util.zip.GZIPOutputStream(Files.newOutputStream(file)).bufferedWriter().use {
            it.write("id,time,price,size,side\nUSDC-1,10,1,0.1,up\n")
        }

        assertThatThrownBy {
            store.read(perp, FlowKind.TRADES, day)
        }.hasMessageContaining("line 2").hasMessageContaining("2026-10-04")
    }
}
