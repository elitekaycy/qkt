package com.qkt.connector.gateway

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class GatewayTradeModeTest : GatewayHarness() {
    @Test
    fun `a gateway account states the trade mode its identity is checked against`() {
        assertThat(session().venue.tradeMode).isEqualTo("demo")
    }
}
