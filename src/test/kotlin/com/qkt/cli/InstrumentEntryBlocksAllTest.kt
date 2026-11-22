package com.qkt.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InstrumentEntryBlocksAllTest {
    @Test
    fun `every entry of the instruments section is listed by symbol with its own text`() {
        val blocks =
            InstrumentEntryBlocks.of(
                "instruments:\n  - qktSymbol: EXNESS:XAUUSD\n    digits: 2\n  - { qktSymbol: EXNESS:EURUSD, digits: 5 }\n",
            )

        assertThat(blocks.all().keys).containsExactly("EXNESS:XAUUSD", "EXNESS:EURUSD")
        assertThat(blocks.all().getValue("EXNESS:XAUUSD")).contains("digits: 2")
        assertThat(blocks.all()).isEqualTo(
            mapOf(
                "EXNESS:XAUUSD" to blocks["EXNESS:XAUUSD"],
                "EXNESS:EURUSD" to blocks["EXNESS:EURUSD"],
            ),
        )
    }
}
