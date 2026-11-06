package com.qkt.marketdata.ingest.run

import com.qkt.instrument.EventCatalogRegistry
import com.qkt.instrument.EventFamilyFixture
import com.qkt.marketdata.ingest.IngestFixtures
import com.qkt.marketdata.ingest.plan.ImportDescriptor
import com.qkt.marketdata.ingest.plan.IngestKind
import com.qkt.marketdata.ingest.record.TickRecord
import com.qkt.marketdata.ingest.store.DayPrices
import com.qkt.marketdata.openDayFeed
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** An event contract's quotes may carry one side only; for every other instrument that is still a row error. */
class EventQuoteImportTest {
    @TempDir
    lateinit var dir: Path

    private val root get() = dir.resolve("root")

    private val registry =
        EventCatalogRegistry(
            listOf(EventFamilyFixture.FAMILY),
            mapOf(EventFamilyFixture.ROOT to EventFamilyFixture.catalog()),
        )

    private val quotes =
        ImportDescriptor(kind = IngestKind.TICKS, time = listOf("t"), fields = mapOf("bid" to "b", "ask" to "a"))

    private fun file(): Path =
        dir.resolve("q.csv").also {
            Files.writeString(it, "t,b,a\n1709546400000,0.40,0.42\n1709546460000,0.41,\n1709546520000,,0.05\n")
        }

    private fun dayFile(): Path = IngestFixtures.dataFiles(root).first { it.toString().endsWith(".csv.gz") }

    @Test
    fun `a one-sided quote of an event contract is kept and reads back at the side it has`() {
        IngestFixtures.import(root, listOf(file()), "KALSHI:KXT_E1_T2", quotes, registry = registry)

        val stored = IngestFixtures.lines(dayFile()).drop(1)
        assertThat(stored).containsExactly(
            "1709546400000,KXT_E1_T2,,,0.40000000,0.42000000,,",
            "1709546460000,KXT_E1_T2,,,0.41000000,,,",
            "1709546520000,KXT_E1_T2,,,,0.05000000,,",
        )
        val ticks =
            openDayFeed(dayFile()).use { feed ->
                generateSequence { feed.next() }.toList()
            }
        assertThat(ticks.map { it.price.toPlainString() }).containsExactly("0.41000000", "0.41000000", "0.05000000")
        assertThat(ticks[1].ask).isNull()
        assertThat(ticks[2].bid).isNull()
    }

    @Test
    fun `the same quotes for an instrument that is not an event refuse the one-sided rows`() {
        assertThatThrownBy {
            IngestFixtures.import(root, listOf(file()), "KALSHI:KXT_E1_T2", quotes)
        }.hasMessageContaining("only one side")
    }

    @Test
    fun `a one-sided quote is priced at its side when days are compared`() {
        fun tick(
            bid: String?,
            ask: String?,
        ) = TickRecord(1L, 0L, null, null, bid?.let(::BigDecimal), ask?.let(::BigDecimal), null, null)
        val records = listOf(tick("0.40", "0.42"), tick("0.41", null), tick(null, "0.05"), tick(null, null))
        val prices = DayPrices.of(records)
        assertThat(prices.points.map { it.price.toPlainString() }).containsExactly("0.41000000", "0.41", "0.05")
    }
}
