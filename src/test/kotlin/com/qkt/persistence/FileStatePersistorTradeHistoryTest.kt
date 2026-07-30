package com.qkt.persistence

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FileStatePersistorTradeHistoryTest {
    @Test
    fun `saveTradeHistory then loadTradeHistory round-trips outcomes`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        persistor.saveTradeHistory(
            "latch",
            PersistedTradeHistory(
                listOf(
                    PersistedTradeOutcome(100L, BigDecimal("-5.25"), "XAUUSD"),
                    PersistedTradeOutcome(200L, BigDecimal("10.50"), "XAUUSD"),
                ),
            ),
        )

        val loaded = persistor.loadTradeHistory("latch")

        assertThat(loaded).isNotNull
        assertThat(loaded!!.outcomes).hasSize(2)
        assertThat(loaded.outcomes[0].timestamp).isEqualTo(100L)
        assertThat(loaded.outcomes[0].pnl).isEqualByComparingTo("-5.25")
        assertThat(loaded.outcomes[0].symbol).isEqualTo("XAUUSD")
        assertThat(loaded.outcomes[1].timestamp).isEqualTo(200L)
        assertThat(loaded.outcomes[1].pnl).isEqualByComparingTo("10.50")
        assertThat(loaded.outcomes[1].symbol).isEqualTo("XAUUSD")
    }

    @Test
    fun `loadTradeHistory returns null when file missing`(
        @TempDir tmp: Path,
    ) {
        assertThat(FileStatePersistor(tmp).loadTradeHistory("absent")).isNull()
    }

    @Test
    fun `loadTradeHistory fails closed on corrupt state`(
        @TempDir tmp: Path,
    ) {
        val dir = tmp.resolve("latch")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("trade-history.json"), "{broken")

        assertThatThrownBy { FileStatePersistor(tmp).loadTradeHistory("latch") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("loadTradeHistory parse failed")
    }
}
