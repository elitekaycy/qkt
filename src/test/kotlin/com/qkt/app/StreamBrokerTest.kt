package com.qkt.app

import com.qkt.dsl.compile.HubKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StreamBrokerTest {
    @Test
    fun `an option root feed trades on its venue's account, other streams on their own prefix`() {
        assertThat(tradingBroker(HubKey("OPTIONS", "DERIBIT.BTC_USDC", "1m"))).isEqualTo("DERIBIT")
        assertThat(tradingBroker(HubKey("CHAIN", "DERIBIT.BTC_USDC.atm_iv.30d", "1m"))).isEqualTo("CHAIN")
        assertThat(tradingBroker(HubKey("EXNESS", "XAUUSD", "5m"))).isEqualTo("EXNESS")
    }
}
