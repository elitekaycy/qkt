package com.qkt.cli.daemon

import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StateDirStateRootTest {
    @Test
    fun `stateRoot exposes the state subdir under root`(
        @TempDir tmp: Path,
    ) {
        val dir = StateDir.resolve(tmp.toString())
        assertThat(dir.stateRoot).isEqualTo(tmp.resolve("state"))
    }
}
