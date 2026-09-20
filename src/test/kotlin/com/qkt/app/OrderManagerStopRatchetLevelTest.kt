package com.qkt.app

import com.qkt.app.OrderManagerStopRatchetFixtures.fixture
import com.qkt.app.OrderManagerStopRatchetFixtures.stepped
import com.qkt.app.OrderManagerStopRatchetFixtures.tick
import com.qkt.app.OrderManagerStopRatchetFixtures.timeTighten
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerStopRatchetLevelTest {
    @Test
    fun `one gap tick consumes every crossed step and locks the final target`() {
        val fixture = fixture()
        fixture.manager.submit(stepped())

        fixture.tick("170", 1L)
        fixture.tick("139", 2L)

        val close = fixture.broker.submits.single() as OrderRequest.Market
        assertThat(close.id).isEqualTo("step-sl")
        assertThat(close.side).isEqualTo(Side.SELL)
    }

    @Test
    fun `a later widening step is skipped`() {
        val fixture = fixture()
        val request =
            stepped().copy(
                steps =
                    listOf(
                        StopLossSpec.Step(Money.of("30"), Money.of("40")),
                        StopLossSpec.Step(Money.of("70"), Money.of("10")),
                    ),
            )
        fixture.manager.submit(request)

        fixture.tick("170", 1L)
        fixture.tick("120", 2L)

        assertThat(fixture.broker.submits.single()).isInstanceOf(OrderRequest.Market::class.java)
    }

    @Test
    fun `stepped targets are direction relative for a short entry`() {
        val fixture = fixture()
        fixture.manager.submit(
            stepped().copy(
                side = Side.BUY,
                steps = listOf(StopLossSpec.Step(Money.of("30"), Money.ZERO)),
            ),
        )

        fixture.tick("70", 1L)
        fixture.tick("101", 2L)

        val close = fixture.broker.submits.single() as OrderRequest.Market
        assertThat(close.side).isEqualTo(Side.BUY)
    }

    @Test
    fun `time tightening accrues intervals and clamps at its floor`() {
        val fixture = fixture()
        fixture.manager.submit(timeTighten())

        fixture.tick("100", 6 * 900_000L)
        fixture.tick("79", 6 * 900_000L + 1)

        assertThat(fixture.broker.submits.single()).isInstanceOf(OrderRequest.Market::class.java)
    }
}
