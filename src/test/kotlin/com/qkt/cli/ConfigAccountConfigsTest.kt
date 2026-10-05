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
                type: gateway
                gateway_url: http://gateway-bybit-linear:8443
            """.trimIndent(),
        )

        val accounts = Config.load(file).accountConfigs()

        assertThat(accounts.map { it.name }).containsExactly("prop_s01", "bybit_linear")
        assertThat(accounts[0].type).isEqualTo("mt5")
        assertThat(accounts[0].tradingHours).containsExactly("BTC*" to "crypto", "*" to "fx")
        assertThat(accounts[1].setting("gateway_url")).isEqualTo("http://gateway-bybit-linear:8443")
    }

    @Test
    fun `the bybit scaffold declares a gateway account that serves BYBIT_LINEAR symbols`(
        @TempDir dir: Path,
    ) {
        val template =
            checkNotNull(javaClass.classLoader.getResource("templates/bybit/qkt.config.yaml.tmpl")) {
                "bybit config template missing"
            }.readText()
        val file = dir.resolve("qkt.config.yaml").also { Files.writeString(it, template) }

        val accounts = Config.load(file).accountConfigs()

        assertThat(accounts.map { it.name to it.type }).containsExactly("bybit_linear" to "gateway")
        assertThat(accounts.single().setting("expected_adapter")).isEqualTo("bybit")
        TestAccounts.directory(*accounts.toTypedArray()).use { opened ->
            val account = opened.forSymbol("BYBIT_LINEAR:BTCUSDT")
            assertThat(account?.config?.name).isEqualTo("bybit_linear")
            assertThat(account?.marketDataPattern?.matches("BYBIT_LINEAR:BTCUSDT")).isTrue()
            assertThat(opened.forSymbol("BYBIT_SPOT:BTCUSDT")).isNull()
        }
    }

    @Test
    fun `the built-in connectors are discovered as services`() {
        assertThat(ConnectorRegistry.discover().types).containsExactly("bybit", "gateway", "mt5")
    }
}
