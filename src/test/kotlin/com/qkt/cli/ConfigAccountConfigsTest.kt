package com.qkt.cli

import com.qkt.connectivity.ConnectorRegistry
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ConfigAccountConfigsTest {
    @Test
    fun `every brokers entry becomes an account config with its nested blocks, in order`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("qkt.config.yaml")
        Files.writeString(
            file,
            """
            brokers:
              prop_s01:
                type: mt5
                extends: exness
                gateway_url: http://gw:5001
                magic: 7
                calendars:
                  "BTC*": crypto
                  "*": fx
              bybit_linear:
                type: bybit
                category: linear
            """.trimIndent(),
        )

        val accounts = Config.load(file).accountConfigs()

        assertThat(accounts.map { it.name }).containsExactly("prop_s01", "bybit_linear")
        assertThat(accounts[0].type).isEqualTo("mt5")
        assertThat(accounts[0].tradingHours).containsExactly("BTC*" to "crypto", "*" to "fx")
        assertThat(accounts[1].setting("category")).isEqualTo("linear")
    }

    @Test
    fun `the built-in connectors are discovered as services`() {
        assertThat(ConnectorRegistry.discover().types).containsExactly("bybit", "mt5")
    }
}
