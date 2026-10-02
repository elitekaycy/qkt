package com.qkt.connector.gateway

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A gateway account's equity is read from the venue each time, never frozen at the session's start. */
internal class GatewayEquityTest : GatewayHarness() {
    @Test
    fun `equity follows the account as the venue moves it without an event`() {
        val broker = broker(session(), Strategy(), "a")
        assertThat(broker.accountEquity()).isEqualByComparingTo("1000")

        fake.account = FakeWire.ACCOUNT.replace(""""equity":"1000"""", """"equity":"1012.5"""")

        assertThat(broker.accountEquity()).isEqualByComparingTo("1012.5")
    }
}
