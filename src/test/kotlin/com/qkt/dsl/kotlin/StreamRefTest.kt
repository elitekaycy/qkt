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
    fun `tickSize produces a StreamFieldRef for the alias`() {
        assertThat(StreamRef("gold").tickSize).isEqualTo(StreamFieldRef("gold", "tick_size"))
    }

    @Test
    fun `contractSize produces a StreamFieldRef for the alias`() {
        assertThat(StreamRef("gold").contractSize).isEqualTo(StreamFieldRef("gold", "contract_size"))
    }

    @Test
    fun `volumeStep produces a StreamFieldRef for the alias`() {
        assertThat(StreamRef("gold").volumeStep).isEqualTo(StreamFieldRef("gold", "volume_step"))
    }

    @Test
    fun `volumeMin produces a StreamFieldRef for the alias`() {
        assertThat(StreamRef("gold").volumeMin).isEqualTo(StreamFieldRef("gold", "volume_min"))
    }
}
