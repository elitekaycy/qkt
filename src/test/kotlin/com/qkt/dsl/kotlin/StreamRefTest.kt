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
}
