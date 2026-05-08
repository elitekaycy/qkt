package com.qkt.cli

import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ConfigTest {
    @Test
    fun `loads top-level fields with env-var expansion`() {
        System.setProperty("TEST_BYBIT_API_KEY", "secret_value")
        val c = Config.load(Path.of("src/test/resources/cli/qkt.config.yaml"))
        assertThat(c.source).isEqualTo("bybit")
        assertThat(c.dataRoot).isEqualTo("./fixtures")
        assertThat(c.startingBalance).isEqualByComparingTo("10000")
        assertThat(c.brokers["bybit"]?.get("api_key")).isEqualTo("secret_value")
    }

    @Test
    fun `missing config returns defaults`() {
        val c = Config.load(Path.of("nonexistent.yaml"))
        assertThat(c.source).isEqualTo("local")
        assertThat(c.dataRoot).isEqualTo("./data")
        assertThat(c.startingBalance).isEqualByComparingTo("0")
    }
}
