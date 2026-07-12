package com.qkt.broker.mt5

import com.qkt.broker.OrderTypeCapability
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MT5ProtocolTest {
    @Test
    fun `MT5 advertises POSITION_MODIFY so exits attach to the position not rest as counters`() {
        assertThat(MT5Protocol.capabilities).contains(OrderTypeCapability.POSITION_MODIFY)
    }

    @Test
    fun `MT5 advertises only pending shapes accepted by the gateway`() {
        assertThat(MT5Protocol.capabilities)
            .contains(OrderTypeCapability.STOP, OrderTypeCapability.LIMIT)
            .doesNotContain(OrderTypeCapability.STOP_LIMIT, OrderTypeCapability.TRAILING_STOP)
    }
}
