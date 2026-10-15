package com.qkt.trade

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BotBrokerErrorsTest {
    @Test
    fun `a gateway broker is refused by name with the way to trade it`() {
        val brokers = mapOf("paper" to mapOf("type" to "gateway"), "mt5" to mapOf("type" to "mt5"))

        val message = BotBrokerErrors.missing(brokers, "PAPER", listOf("mt5"))

        assertThat(message).contains("broker 'PAPER' is type: gateway").contains("qkt deploy")
    }

    @Test
    fun `an unknown broker still lists the mt5 brokers`() {
        assertThat(BotBrokerErrors.missing(emptyMap(), "nope", emptyList()))
            .isEqualTo("no mt5 broker 'nope' in config; configured: none")
    }
}
