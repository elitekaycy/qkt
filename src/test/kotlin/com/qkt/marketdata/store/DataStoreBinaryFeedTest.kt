package com.qkt.marketdata.store

import com.qkt.common.Money
import com.qkt.marketdata.BinaryTickWriter
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickAssembler
import com.qkt.marketdata.source.MarketRequest
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DataStoreBinaryFeedTest {
    private fun bd(s: String) = BigDecimal(s).setScale(Money.SCALE, Money.ROUNDING)

    @Test
    fun `openFeed reads a bin day via BinaryTickFeed`(
        @TempDir root: Path,
    ) {
        val symDir = root.resolve("symbols").resolve("XAUUSD")
        val ticks =
            listOf(
                TickAssembler.assemble(
                    "XAUUSD",
                    1_712_016_000_000L,
                    null,
                    null,
                    bd("1711.50400000"),
                    bd("1712.00200000"),
                    null,
                    null,
                    "t:1",
                ),
            )
        BinaryTickWriter().write(symDir.resolve("2024-04-02.bin"), "XAUUSD", ticks)

        val store = DefaultDataStore(root = root)
        store.rebuildManifests() // a .bin-only day must be recognized as covered
        val feed =
            store.openFeed(
                MarketRequest(
                    symbols = listOf("XAUUSD"),
                    from = Instant.parse("2024-04-02T00:00:00Z"),
                    to = Instant.parse("2024-04-03T00:00:00Z"),
                ),
            )
        val out = mutableListOf<Tick>()
        feed.use {
            while (true) {
                val t = it.next() ?: break
                out.add(t)
            }
        }
        assertEquals(ticks, out)
    }
}
