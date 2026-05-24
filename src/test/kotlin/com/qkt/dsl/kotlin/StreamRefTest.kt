package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.StreamFieldRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StreamRefTest {
    @Test
    fun `bid ask spread produce a StreamFieldRef for the alias`() {
        val s = StreamRef("gold")
        assertThat(s.bid).isEqualTo(StreamFieldRef("gold", "bid"))
        assertThat(s.ask).isEqualTo(StreamFieldRef("gold", "ask"))
        assertThat(s.spread).isEqualTo(StreamFieldRef("gold", "spread"))
    }

    @Test
    fun `tick_size produces a StreamFieldRef for the alias`() {
        assertThat(StreamRef("gold").tick_size).isEqualTo(StreamFieldRef("gold", "tick_size"))
    }

    @Test
    fun `contract_size produces a StreamFieldRef for the alias`() {
        assertThat(StreamRef("gold").contract_size).isEqualTo(StreamFieldRef("gold", "contract_size"))
    }

    @Test
    fun `volume_step produces a StreamFieldRef for the alias`() {
        assertThat(StreamRef("gold").volume_step).isEqualTo(StreamFieldRef("gold", "volume_step"))
    }

    @Test
    fun `volume_min produces a StreamFieldRef for the alias`() {
        assertThat(StreamRef("gold").volume_min).isEqualTo(StreamFieldRef("gold", "volume_min"))
    }
}
