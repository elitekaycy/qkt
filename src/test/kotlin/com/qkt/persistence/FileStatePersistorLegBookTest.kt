package com.qkt.persistence

import com.qkt.common.Side
import com.qkt.positions.LegBook
import com.qkt.positions.LegRole
import com.qkt.positions.PositionLeg
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FileStatePersistorLegBookTest {
    @Test
    fun `saveLegBook then loadLegBook returns the same legs`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        val book = LegBook("XAUUSDm")
        book.add(
            PositionLeg(
                legId = "leg-1",
                symbol = "XAUUSDm",
                side = Side.BUY,
                quantity = BigDecimal("0.20"),
                entryPrice = BigDecimal("4700.0"),
                openedAt = 1000L,
                role = LegRole.PRIMARY,
            ),
        )
        book.add(
            PositionLeg(
                legId = "leg-2",
                symbol = "XAUUSDm",
                side = Side.BUY,
                quantity = BigDecimal("0.06"),
                entryPrice = BigDecimal("4710.5"),
                openedAt = 2000L,
                role = LegRole.STACK,
                parentLegId = "leg-1",
            ),
        )
        persistor.saveLegBook("hedge", "XAUUSDm", book)
        val loaded = persistor.loadLegBook("hedge", "XAUUSDm")
        assertThat(loaded).isNotNull
        assertThat(loaded!!.legs).hasSize(2)
        val primary = loaded.legs.first { it.role == LegRole.PRIMARY }
        assertThat(primary.quantity.toPlainString()).isEqualTo("0.20")
        assertThat(primary.entryPrice.toPlainString()).isEqualTo("4700.0")
        val stack = loaded.legs.first { it.role == LegRole.STACK }
        assertThat(stack.parentLegId).isEqualTo("leg-1")
        assertThat(stack.entryPrice.toPlainString()).isEqualTo("4710.5")
    }

    @Test
    fun `saveLegBook preserves brokerTicket across reload`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        val book = LegBook("XAUUSDm")
        book.add(
            PositionLeg(
                legId = "leg-1",
                symbol = "XAUUSDm",
                side = Side.BUY,
                quantity = BigDecimal("0.20"),
                entryPrice = BigDecimal("4700.0"),
                openedAt = 1000L,
                role = LegRole.PRIMARY,
                brokerTicket = "ticket-9981",
            ),
        )
        persistor.saveLegBook("hedge", "XAUUSDm", book)
        val loaded = persistor.loadLegBook("hedge", "XAUUSDm")
        assertThat(loaded).isNotNull
        assertThat(loaded!!.legs.single().brokerTicket).isEqualTo("ticket-9981")
    }

    @Test
    fun `loadLegBook returns null when file missing`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        assertThat(persistor.loadLegBook("absent", "XAUUSDm")).isNull()
    }

    @Test
    fun `loadLegBook returns null on version mismatch`(
        @TempDir tmp: Path,
    ) {
        val dir = tmp.resolve("hedge")
        Files.createDirectories(dir)
        Files.writeString(
            dir.resolve("XAUUSDm-legbook.json"),
            """{"version":99,"strategyId":"hedge","symbol":"XAUUSDm","legs":[]}""",
        )
        val persistor = FileStatePersistor(tmp)
        assertThat(persistor.loadLegBook("hedge", "XAUUSDm")).isNull()
    }

    @Test
    fun `loadLegBook returns null on corrupted JSON`(
        @TempDir tmp: Path,
    ) {
        val dir = tmp.resolve("hedge")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("XAUUSDm-legbook.json"), "{not valid json")
        val persistor = FileStatePersistor(tmp)
        assertThat(persistor.loadLegBook("hedge", "XAUUSDm")).isNull()
    }

    @Test
    fun `clearStrategy wipes the per-strategy dir`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        val book = LegBook("XAUUSDm")
        book.add(
            PositionLeg(
                legId = "leg-1",
                symbol = "XAUUSDm",
                side = Side.BUY,
                quantity = BigDecimal("1"),
                entryPrice = BigDecimal("100"),
                openedAt = 0L,
                role = LegRole.PRIMARY,
            ),
        )
        persistor.saveLegBook("hedge", "XAUUSDm", book)
        assertThat(Files.exists(tmp.resolve("hedge/XAUUSDm-legbook.json"))).isTrue
        persistor.clearStrategy("hedge")
        assertThat(Files.exists(tmp.resolve("hedge"))).isFalse
    }
}
