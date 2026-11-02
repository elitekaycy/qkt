package com.qkt.connector.gateway

import com.qkt.broker.BookedDeals
import com.qkt.broker.BookedFill
import com.qkt.broker.VenueLeg
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Facts taken back after a restart answer for their own fill, beside those booked since. */
class GatewayFillLegsRestoreTest {
    private fun fill(
        id: String,
        realized: String = "1",
    ) = BookedFill("o", id, VenueLeg("IN", "p", null), BigDecimal(realized), "s", 5L)

    @Test
    fun `a restored fill answers by its order and fill, and is kept for the next restart`() {
        val legs = GatewayFillLegs()

        legs.restore(BookedDeals(listOf(fill("f1", "7")), emptyList()))

        assertThat(legs.of("f1", "o")?.grossRealized).isEqualByComparingTo("7")
        assertThat(legs.of("f1", "other")).isNull()
        assertThat(legs.booked().fills.map { it.fillId }).containsExactly("f1")
    }

    @Test
    fun `restoring beside a fill still on its way to the books leaves that fill's match alone`() {
        val legs = GatewayFillLegs()
        legs.booked("o", "f2")

        legs.restore(BookedDeals(listOf(fill("f1")), emptyList()))

        assertThat(legs.of("f2", "o")).isNull()
        assertThat(legs.of("f1", "o")).isNotNull()
    }

    @Test
    fun `only the newest fills are kept`() {
        val legs = GatewayFillLegs(keptFills = 2)

        legs.restore(BookedDeals(listOf(fill("f1"), fill("f2"), fill("f3")), emptyList()))

        assertThat(legs.booked().fills.map { it.fillId }).containsExactly("f2", "f3")
    }
}
