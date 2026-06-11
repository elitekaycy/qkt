package com.qkt.persistence

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FileStatePersistorPnlTest {
    @Test
    fun `savePnl then loadPnl round-trips the realized amount`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        persistor.savePnl("latch", PersistedPnl(realized = BigDecimal("123.45")))
        val loaded = persistor.loadPnl("latch")
        assertThat(loaded).isNotNull
        assertThat(loaded!!.realized).isEqualByComparingTo(BigDecimal("123.45"))
    }

    @Test
    fun `savePnl preserves negative realized`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        persistor.savePnl("latch", PersistedPnl(realized = BigDecimal("-987.10")))
        assertThat(persistor.loadPnl("latch")!!.realized).isEqualByComparingTo(BigDecimal("-987.10"))
    }

    @Test
    fun `loadPnl returns null when file missing`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        assertThat(persistor.loadPnl("absent")).isNull()
    }

    @Test
    fun `loadPnl returns null on version mismatch`(
        @TempDir tmp: Path,
    ) {
        val dir = tmp.resolve("latch")
        Files.createDirectories(dir)
        Files.writeString(
            dir.resolve("pnl.json"),
            """{"version":99,"strategyId":"latch","realized":"5"}""",
        )
        val persistor = FileStatePersistor(tmp)
        assertThat(persistor.loadPnl("latch")).isNull()
    }

    @Test
    fun `loadPnl returns null on corrupt json`(
        @TempDir tmp: Path,
    ) {
        val dir = tmp.resolve("latch")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("pnl.json"), "{not json")
        val persistor = FileStatePersistor(tmp)
        assertThat(persistor.loadPnl("latch")).isNull()
    }
}
