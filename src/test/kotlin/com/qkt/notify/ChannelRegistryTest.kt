package com.qkt.notify

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ChannelRegistryTest {
    @Test
    fun `DEFAULT contains a TelegramChannelProvider for type telegram`() {
        val provider = ChannelRegistry.DEFAULT.get("telegram")
        assertThat(provider).isNotNull
        assertThat(provider).isInstanceOf(TelegramChannelProvider::class.java)
        assertThat(provider!!.type).isEqualTo("telegram")
    }

    @Test
    fun `DEFAULT returns null for an unknown type`() {
        assertThat(ChannelRegistry.DEFAULT.get("slack")).isNull()
    }

    @Test
    fun `DEFAULT types set contains telegram`() {
        assertThat(ChannelRegistry.DEFAULT.types).contains("telegram")
    }
}
