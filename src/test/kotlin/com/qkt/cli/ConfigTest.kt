package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

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

    @Test
    fun `default-syntax expands to the default when var is unset`(
        @TempDir tmp: Path,
    ) {
        // Pick a name that should not exist in the env / system properties.
        check(System.getenv("QKT_UNSET_TEST_VAR") == null)
        check(System.getProperty("QKT_UNSET_TEST_VAR") == null)
        val cfg = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            cfg,
            """
            source: tv
            brokers:
              exness:
                type: mt5
                gateway_url: ${'$'}{QKT_UNSET_TEST_VAR:-http://mt5-gateway:5001}
            """.trimIndent(),
        )
        val c = Config.load(cfg)
        assertThat(c.brokers["exness"]?.get("gateway_url"))
            .isEqualTo("http://mt5-gateway:5001")
    }

    @Test
    fun `env value wins over the default`(
        @TempDir tmp: Path,
    ) {
        System.setProperty("QKT_TEST_GATEWAY_URL", "http://override.local:9999")
        val cfg = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            cfg,
            """
            brokers:
              exness:
                type: mt5
                gateway_url: ${'$'}{QKT_TEST_GATEWAY_URL:-http://fallback:5001}
            """.trimIndent(),
        )
        val c = Config.load(cfg)
        assertThat(c.brokers["exness"]?.get("gateway_url"))
            .isEqualTo("http://override.local:9999")
    }

    @Test
    fun `var without default leaves literal when unset`(
        @TempDir tmp: Path,
    ) {
        check(System.getenv("QKT_NEVER_SET_VAR") == null)
        check(System.getProperty("QKT_NEVER_SET_VAR") == null)
        val cfg = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            cfg,
            """
            brokers:
              x:
                api_key: ${'$'}{QKT_NEVER_SET_VAR}
            """.trimIndent(),
        )
        val c = Config.load(cfg)
        // Literal flows through — caller is on the hook to fail loudly when reading.
        assertThat(c.brokers["x"]?.get("api_key")).isEqualTo("\${QKT_NEVER_SET_VAR}")
    }
}
