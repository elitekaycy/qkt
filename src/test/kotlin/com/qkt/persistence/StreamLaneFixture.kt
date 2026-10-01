package com.qkt.persistence

import com.qkt.accounting.CostKind
import com.qkt.accounting.MoneyAmount
import com.qkt.accounting.VenueCost
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.ExitReason
import com.qkt.execution.LegIntent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal

/** A continuous stream lane caught mid-roll, one holder at each carry step, for the stream-lane persistence tests. */
internal object StreamLaneFixture {
    const val STREAM = "CME:ES"

    fun market(
        id: String,
        symbol: String,
        side: Side,
    ) = OrderRequest.Market(
        id,
        symbol,
        side,
        BigDecimal("2"),
        TimeInForce.GTC,
        1_000L,
        "trend",
        legIntent = LegIntent.Net,
    )

    private fun fill(
        id: String,
        symbol: String,
        side: Side,
        price: String,
        exitReason: ExitReason? = null,
    ) = BrokerEvent.OrderFilled(
        clientOrderId = id,
        brokerOrderId = "v-$id",
        symbol = symbol,
        side = side,
        price = BigDecimal(price),
        quantity = BigDecimal("2"),
        strategyId = "trend",
        timestamp = 5_000L,
        sequenceId = 7L,
        updatesOrderExecution = exitReason == null,
        venueCosts = BigDecimal("4.20"),
        typedVenueCosts = listOf(VenueCost(CostKind.COMMISSION, MoneyAmount(BigDecimal("4.20"), "USD"), 5_000L)),
        exitReason = exitReason,
    )

    fun order(
        request: OrderRequest,
        venueId: String = request.id,
        contractIndex: Int = 3,
        replacements: Int = 0,
        filled: String = "0",
    ) = PersistedStreamOrder(request, venueId, contractIndex, replacements, BigDecimal(filled))

    fun laneInFlight(): PersistedStreamLane {
        val limit =
            OrderRequest.Limit(
                "e-1",
                STREAM,
                Side.BUY,
                BigDecimal("1"),
                BigDecimal("5010.25"),
                TimeInForce.GTC,
                900L,
                "trend",
            )
        val stop =
            OrderRequest.Stop(
                "e-2",
                STREAM,
                Side.SELL,
                BigDecimal("1"),
                BigDecimal("4990"),
                TimeInForce.DAY,
                900L,
                "carry",
            )
        val stopLimit =
            OrderRequest.StopLimit(
                "e-3",
                STREAM,
                Side.SELL,
                BigDecimal("1"),
                BigDecimal("4990"),
                BigDecimal("4985"),
                TimeInForce.GTC,
                900L,
                "carry",
            )
        return PersistedStreamLane(
            stream = STREAM,
            contractIndex = 3,
            strategies =
                listOf(
                    PersistedStreamStrategy("trend", BigDecimal("2"), stopped = null),
                    PersistedStreamStrategy(
                        "carry",
                        BigDecimal("-1"),
                        stopped = "CME:ES stopped: roll failed (refused)",
                    ),
                    PersistedStreamStrategy("meanrev", BigDecimal("3"), stopped = null),
                    PersistedStreamStrategy("swing", BigDecimal("-4"), stopped = null),
                ),
            orders = listOf(order(limit, venueId = "e-1~r1", contractIndex = 4, replacements = 1, filled = "0.4")),
            holdings =
                listOf(
                    PersistedContractHolding(
                        "trend",
                        "CME:ESZ26",
                        BigDecimal("2"),
                        BigDecimal("5002.5"),
                        openedAt = 800L,
                    ),
                    PersistedContractHolding(
                        "carry",
                        "CME:ESH27",
                        BigDecimal("-1"),
                        BigDecimal("5041"),
                        openedAt = null,
                    ),
                ),
            legs =
                listOf(
                    PersistedRollLeg(
                        market("o-3", "CME:ESH27", Side.BUY),
                        listOf(
                            fill("o-3", "CME:ESH27", Side.BUY, "5040.5"),
                            fill("o-3", "CME:ESH27", Side.BUY, "5041"),
                        ),
                    ),
                    PersistedRollLeg(market("c-4", "CME:ESZ26", Side.BUY), emptyList()),
                ),
            cancelling = listOf("e-2"),
            roll =
                PersistedStreamRoll(
                    fromIndex = 3,
                    toIndex = 4,
                    atMs = 4_000L,
                    fromPrice = BigDecimal("5000"),
                    toPrice = BigDecimal("5040.5"),
                    stopped = "CME:ES stopped: roll CME:ESZ26->CME:ESH27 failed",
                    resting = listOf(order(stop), order(stopLimit)),
                    holders =
                        listOf("trend" to "2", "carry" to "-1", "meanrev" to "3", "swing" to "-4")
                            .map { (id, q) -> PersistedRollHolder(id, BigDecimal(q)) },
                    steps =
                        listOf(
                            PersistedCarryStep.Carried(
                                "trend",
                                BigDecimal("2"),
                                close = fill("c-1", "CME:ESZ26", Side.SELL, "5000.25"),
                                open = fill("o-1", "CME:ESH27", Side.BUY, "5040.75"),
                            ),
                            PersistedCarryStep.Stopped(
                                "carry",
                                BigDecimal("-1"),
                                reason = "refused",
                                close = fill("c-2:failed", STREAM, Side.BUY, "4980", ExitReason.ROLL_FAILED),
                            ),
                            PersistedCarryStep.Opening(
                                "meanrev",
                                BigDecimal("3"),
                                close = fill("c-3", "CME:ESZ26", Side.SELL, "5000"),
                                leg = market("o-3", "CME:ESH27", Side.BUY),
                            ),
                            PersistedCarryStep.Closing(
                                "swing",
                                BigDecimal("-4"),
                                leg = market("c-4", "CME:ESZ26", Side.BUY),
                            ),
                        ),
                ),
        )
    }
}
