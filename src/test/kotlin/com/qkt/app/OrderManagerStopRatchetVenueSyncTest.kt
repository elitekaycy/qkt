package com.qkt.app

import com.qkt.app.OrderManagerStopRatchetFixtures.fixture
import com.qkt.app.OrderManagerStopRatchetFixtures.stepped
import com.qkt.app.OrderManagerStopRatchetFixtures.tick
import com.qkt.broker.OrderTypeCapability
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SteppedStopAst
import com.qkt.dsl.ast.StopStepAst
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerStopRatchetVenueSyncTest {
    @Test
    fun `ratchet transition mirrors the tighter stop to a position-modify venue`() {
        val fixture =
            fixture(
                capabilities =
                    setOf(
                        OrderTypeCapability.MARKET,
                        OrderTypeCapability.POSITION_MODIFY,
                    ),
                closeTicket = "ticket-42",
            )
        fixture.manager.submit(stepped())

        fixture.tick("130", 1L)
        fixture.tick("131", 2L)

        assertThat(fixture.broker.modifyPositions).hasSize(1)
        val modification = fixture.broker.modifyPositions.single()
        assertThat(modification.ticket).isEqualTo("ticket-42")
        assertThat(modification.sl).isEqualByComparingTo("100")
        assertThat(modification.tp).isNull()
    }

    @Test
    fun `attached bracket anchors then modifies its venue stop at a milestone`() {
        val fixture =
            fixture(
                capabilities =
                    setOf(
                        OrderTypeCapability.BRACKET,
                        OrderTypeCapability.MARKET,
                        OrderTypeCapability.POSITION_MODIFY,
                    ),
                closeTicket = "entry",
            )
        fixture.tick("100", 0L)
        val entry =
            OrderRequest.Market(
                id = "entry",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        val bracket =
            OrderRequest.Bracket(
                id = "bracket",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                entry = entry,
                takeProfit = Money.of("220"),
                stopLoss =
                    StopLossSpec.SteppedStop(
                        Money.of("50"),
                        listOf(StopLossSpec.Step(Money.of("30"), Money.ZERO)),
                    ),
                stopLossAst =
                    ChildBy(
                        NumLit(Money.of("50")),
                        SteppedStopAst(
                            listOf(
                                StopStepAst(NumLit(Money.of("30")), NumLit(Money.ZERO)),
                            ),
                        ),
                    ),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "alpha",
            )

        fixture.manager.submit(bracket)
        fixture.broker.emitFill(fixture.broker.submits.single(), Money.of("100"))
        fixture.tick("130", 1L)

        assertThat(fixture.broker.modifyPositions).hasSize(2)
        assertThat(fixture.broker.modifyPositions[0].sl).isEqualByComparingTo("50")
        assertThat(fixture.broker.modifyPositions[1].sl).isEqualByComparingTo("100")
        assertThat(fixture.broker.modifyPositions[1].tp).isNull()
    }
}
