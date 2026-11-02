package com.qkt.persistence

import com.qkt.broker.BookedDeals
import com.qkt.broker.BookedFill
import com.qkt.broker.SettledClose
import com.qkt.broker.VenueLeg
import com.qkt.common.Side
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Booked deals round trip on disk, a state directory from before them loads, and a write leaves the engine's thread. */
class BookedDealsFileTest {
    @TempDir
    lateinit var dir: Path

    private val deals =
        BookedDeals(
            listOf(BookedFill("o1", "f1", VenueLeg("IN", "p1", null), BigDecimal("0.50"), "s", 5L)),
            listOf(
                SettledClose(
                    "DERIBIT:X",
                    "s",
                    Side.SELL,
                    BigDecimal("0.5"),
                    VenueLeg("OUT", "p1", null),
                    BigDecimal("250"),
                    BigDecimal("0.3"),
                    9L,
                ),
            ),
        )

    @Test
    fun `saved deals load back as they were`() {
        FileStatePersistor(dir).saveBookedDeals("s", deals)

        val loaded = FileStatePersistor(dir).loadBookedDeals("s")

        assertThat(loaded).isEqualTo(deals)
    }

    @Test
    fun `a state directory from before booked deals has none, and everything else still loads`() {
        FileStatePersistor(dir).saveOrderIdMarks("s", mapOf("p" to 3L))

        val state = FileStatePersistor(dir)

        assertThat(state.loadBookedDeals("s")).isNull()
        assertThat(state.loadOrderIdMarks("s")).containsEntry("p", 3L)
    }

    @Test
    fun `a file naming neither list loads with none of either`() {
        FileStatePersistor(dir).saveOrderIdMarks("s", mapOf("p" to 3L))
        Files.writeString(dir.resolve("s").resolve("booked-deals.json"), """{"version":1}""")

        assertThat(FileStatePersistor(dir).loadBookedDeals("s")).isEqualTo(BookedDeals(emptyList(), emptyList()))
    }

    @Test
    fun `the async persistor writes on its own thread and drains on close`() {
        val writers = mutableListOf<String>()
        val recording =
            object : StatePersistor by NoopStatePersistor() {
                override fun saveBookedDeals(
                    ownerId: String,
                    deals: BookedDeals,
                ) {
                    writers += Thread.currentThread().name
                }
            }

        AsyncStatePersistor(recording).use { it.saveBookedDeals("s", deals) }

        assertThat(writers).containsExactly("qkt-persistence-async")
    }
}
