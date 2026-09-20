package com.qkt.connectivity

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AccountConfigTest {
    @Test
    fun `symbol prefix is the upper-cased account name`() {
        assertThat(AccountConfig("prop_s01", "mt5", emptyMap()).symbolPrefix).isEqualTo("PROP_S01:")
    }

    @Test
    fun `setting reads a scalar field`() {
        val cfg = AccountConfig("a", "mt5", mapOf("gateway_url" to "http://gw"))

        assertThat(cfg.setting("gateway_url")).isEqualTo("http://gw")
        assertThat(cfg.setting("missing")).isNull()
    }

    @Test
    fun `connector types are lowercase and named`() {
        assertThatThrownBy { ConnectorSpec("MT5", "MetaTrader 5", setOf(ProductType.CFD), emptySet()) }
            .hasMessageContaining("lowercase")
        assertThatThrownBy { ConnectorSpec(" ", "Nothing", emptySet(), emptySet()) }
            .hasMessageContaining("blank")
    }
}
