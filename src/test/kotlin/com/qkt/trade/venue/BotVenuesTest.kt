package com.qkt.trade.venue

import com.qkt.cli.Config
import com.qkt.connector.mt5.Mt5DirectVenue
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BotVenuesTest {
    private fun config(
        dir: Path,
        brokers: String,
    ): Config {
        val file = dir.resolve("qkt.config.yaml")
        Files.writeString(file, "brokers:\n$brokers")
        return Config.load(file)
    }

    private val exness =
        """
        |  exness:
        |    type: mt5
        |    gateway_url: http://127.0.0.1:1
        |    symbol_suffix: m
        |    magic: 777001
        |
        """.trimMargin()

    private val paper =
        """
        |  paper:
        |    type: gateway
        |    gateway_url: http://127.0.0.1:2
        |    api_key: k
        |    expected_adapter: paper
        |    expected_account_login: paper-1
        |    expected_trade_mode: demo
        |
        """.trimMargin()

    @Test
    fun `mt5 broker resolves to the mt5 venue`(
        @TempDir dir: Path,
    ) {
        BotVenues.forSymbol(config(dir, exness), "EXNESS:XAUUSD").use { opened ->
            assertThat(opened.broker).isEqualTo("exness")
            assertThat(opened.venue).isInstanceOf(Mt5DirectVenue::class.java)
            assertThat(opened.venue.venueSymbol("EXNESS:XAUUSD")).isEqualTo("XAUUSDm")
        }
    }

    @Test
    fun `the symbol prefix matches the broker name in any case`(
        @TempDir dir: Path,
    ) {
        BotVenues.forSymbol(config(dir, exness), "Exness:XAUUSD").use { assertThat(it.broker).isEqualTo("exness") }
    }

    @Test
    fun `unknown prefix names the configured brokers`(
        @TempDir dir: Path,
    ) {
        assertThatThrownBy { BotVenues.forSymbol(config(dir, exness + paper), "ICM:XAUUSD") }
            .hasMessageContaining("no broker 'ICM' in config")
            .hasMessageContaining("exness, paper")
    }

    @Test
    fun `no broker named and two configured asks for --broker`(
        @TempDir dir: Path,
    ) {
        assertThatThrownBy { BotVenues.forBroker(config(dir, exness + paper), null) }
            .hasMessageContaining("pass --broker <name>")
    }

    @Test
    fun `a symbol without a broker prefix is refused`(
        @TempDir dir: Path,
    ) {
        assertThatThrownBy { BotVenues.forSymbol(config(dir, exness), "XAUUSD") }
            .hasMessageContaining("symbol must be BROKER:SYMBOL")
    }
}
