package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RunFeedTest {
    @Test
    fun `with no config file qkt run paper-trades on TradingView`(
        @TempDir dir: Path,
    ) {
        assertThat(runFeed(dir.resolve("qkt.config.yaml"), configured = "local")).isEqualTo("tv")
    }

    @Test
    fun `a config file's source is kept`(
        @TempDir dir: Path,
    ) {
        val config = Files.writeString(dir.resolve("qkt.config.yaml"), "source: local\n")

        assertThat(runFeed(config, configured = "local")).isEqualTo("local")
    }
}
