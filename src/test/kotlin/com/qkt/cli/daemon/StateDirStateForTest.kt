package com.qkt.cli.daemon

import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StateDirStateForTest {
    @Test
    fun `stateFor resolves under root state strategyName fileName`(
        @TempDir tmp: Path,
    ) {
        val dir = StateDir.resolve(tmp.toString())
        val p = dir.stateFor("hedge-straddle", "legbook.json")
        assertThat(p).isEqualTo(tmp.resolve("state/hedge-straddle/legbook.json"))
    }

    @Test
    fun `stateFor preserves slashes in portfolio child names`(
        @TempDir tmp: Path,
    ) {
        val dir = StateDir.resolve(tmp.toString())
        val p = dir.stateFor("multi-asset/btc-play", "bracket-pairs.json")
        assertThat(p).isEqualTo(tmp.resolve("state/multi-asset/btc-play/bracket-pairs.json"))
    }

    @Test
    fun `stateRoot exposes the parent dir`(
        @TempDir tmp: Path,
    ) {
        val dir = StateDir.resolve(tmp.toString())
        assertThat(dir.stateRoot).isEqualTo(tmp.resolve("state"))
    }
}
