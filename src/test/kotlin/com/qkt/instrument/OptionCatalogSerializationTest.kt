package com.qkt.instrument

import com.qkt.common.WireAsserts.decodes
import com.qkt.common.WireAsserts.refusesWithout
import com.qkt.common.WireAsserts.refusesWrongType
import com.qkt.common.WireAsserts.roundTrips
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class OptionCatalogSerializationTest {
    private val call = OptionListing("BTC-1-C", "90000", "call", 5L)
    private val put = OptionListing("BTC-1-P", "90000.5", "put", 5L)

    @Test
    fun `a listing round trips and becomes the matching contract`() {
        roundTrips(call)
        assertThat(call.toContract()).isEqualTo(OptionContract("BTC-1-C", BigDecimal("90000"), OptionRight.CALL, 5L))
        assertThat(put.toContract().right).isEqualTo(OptionRight.PUT)
        val body = """{"symbol":"s","strike":"1","right":"call","expiryMs":2}"""
        decodes(body, OptionListing("s", "1", "call", 2L))
        refusesWithout<OptionListing>(body, "symbol", "strike", "right", "expiryMs")
        refusesWrongType<OptionListing>(body, "symbol", "expiryMs")
    }

    @Test
    fun `a listing refuses a bad right or strike`() {
        assertThatThrownBy { OptionListing("s", "1", "straddle", 1L) }.hasMessageContaining("call or put")
        assertThatThrownBy { OptionListing("s", "0", "call", 1L) }.hasMessageContaining("strike must be > 0")
        assertThatThrownBy { OptionListing("s", "-1", "put", 1L) }.hasMessageContaining("strike must be > 0")
        assertThatThrownBy { OptionListing("s", "abc", "put", 1L) }.hasMessageContaining("strike must be > 0: abc")
    }

    @Test
    fun `a catalog round trips with and without delivery prices`() {
        roundTrips(OptionCatalog("BTC", listOf(call, put), mapOf("2024-12-27" to "95000.5")))
        roundTrips(OptionCatalog("BTC", emptyList()))
        decodes("""{"root":"BTC","contracts":[]}""", OptionCatalog("BTC", emptyList(), emptyMap()))
        refusesWithout<OptionCatalog>("""{"root":"BTC","contracts":[]}""", "root", "contracts")
        refusesWrongType<OptionCatalog>("""{"root":"BTC","contracts":[]}""", "deliveryPrices", "contracts")
    }

    @Test
    fun `a catalog refuses duplicate contracts and unusable delivery prices`() {
        assertThatThrownBy { OptionCatalog("BTC", listOf(call, call.copy(strike = "1"))) }
            .hasMessageContaining("duplicate contract 'BTC-1-C'")
        for (bad in listOf("0", "-5", "n/a")) {
            assertThatThrownBy { OptionCatalog("BTC", listOf(call), mapOf("2024-12-27" to bad)) }
                .hasMessageContaining("delivery price on 2024-12-27 must be > 0: $bad")
        }
    }
}
