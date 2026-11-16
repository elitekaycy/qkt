package com.qkt.marketdata.store

import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A symbol that can be no file name has no built timeframes rather than crashing the resolver. */
class BinaryBarStorePathTest {
    @Test
    fun `a symbol holding a character no path allows has no built timeframes`(
        @TempDir root: Path,
    ) {
        assertThat(BinaryBarStore(root).builtTimeframes("BACKTEST", "EUR\u0000USD")).isEmpty()
    }
}
