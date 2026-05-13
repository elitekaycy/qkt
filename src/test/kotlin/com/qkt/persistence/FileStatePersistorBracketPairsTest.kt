package com.qkt.persistence

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FileStatePersistorBracketPairsTest {
    @Test
    fun `saveBracketPairs then loadBracketPairs round-trips a full bracket`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        val pairs =
            listOf(
                BracketPair(
                    entryClientOrderId = "c-1",
                    stopLossClientOrderId = "c-1-sl",
                    takeProfitClientOrderId = "c-1-tp",
                    legId = "leg-1",
                ),
                BracketPair(
                    entryClientOrderId = "c-2",
                    stopLossClientOrderId = "c-2-sl",
                    takeProfitClientOrderId = "c-2-tp",
                    legId = "leg-2",
                ),
            )
        persistor.saveBracketPairs("hedge", pairs)
        val loaded = persistor.loadBracketPairs("hedge")
        assertThat(loaded).isEqualTo(pairs)
    }

    @Test
    fun `saveBracketPairs preserves nullable SL and TP`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        val partial =
            BracketPair(
                entryClientOrderId = "c-3",
                stopLossClientOrderId = "c-3-sl",
                takeProfitClientOrderId = null,
                legId = null,
            )
        persistor.saveBracketPairs("hedge", listOf(partial))
        val loaded = persistor.loadBracketPairs("hedge")
        assertThat(loaded).containsExactly(partial)
    }

    @Test
    fun `saveBracketPairs with empty list round-trips to empty`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        persistor.saveBracketPairs("hedge", emptyList())
        assertThat(persistor.loadBracketPairs("hedge")).isEmpty()
    }

    @Test
    fun `loadBracketPairs returns empty when file missing`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        assertThat(persistor.loadBracketPairs("absent")).isEmpty()
    }

    @Test
    fun `loadBracketPairs returns empty on version mismatch`(
        @TempDir tmp: Path,
    ) {
        val dir = tmp.resolve("hedge")
        Files.createDirectories(dir)
        Files.writeString(
            dir.resolve("bracket-pairs.json"),
            """{"version":99,"strategyId":"hedge","pairs":[]}""",
        )
        val persistor = FileStatePersistor(tmp)
        assertThat(persistor.loadBracketPairs("hedge")).isEmpty()
    }
}
