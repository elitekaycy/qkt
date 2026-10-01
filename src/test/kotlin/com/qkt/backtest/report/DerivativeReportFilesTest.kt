package com.qkt.backtest.report

import com.qkt.broker.continuous.ContractFill
import com.qkt.broker.continuous.RollEntry
import com.qkt.broker.exchange.Settlement
import com.qkt.common.Side
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DerivativeReportFilesTest {
    private val at = 1_726_732_800_000L

    @Test
    fun `a run without futures has no futures files`() {
        assertThat(DerivativeReportFiles.render(emptyList(), emptyList(), emptyList())).isEmpty()
    }

    @Test
    fun `each futures ledger renders its own file`() {
        val roll =
            RollEntry(
                at,
                "BINANCE_UM:BTCUSDT@front",
                "s",
                "BINANCE_UM:BTCUSDT_240927",
                "BINANCE_UM:BTCUSDT_241227",
                BigDecimal("0.01"),
                BigDecimal.ONE,
                BigDecimal("62206.2"),
                BigDecimal("63344.1"),
                BigDecimal("62206.4"),
                BigDecimal("63343.9"),
                BigDecimal("0.6"),
            )
        val fill =
            ContractFill(
                at,
                "s",
                "BINANCE_UM:BTCUSDT@front",
                "o1",
                "BINANCE_UM:BTCUSDT_241227",
                Side.BUY,
                BigDecimal("0.01"),
                BigDecimal("63344.1"),
                BigDecimal("60140.7"),
            )
        val settlement =
            Settlement(at, "s", "BINANCE_UM:BTCUSDT_240927", Side.SELL, BigDecimal("0.01"), BigDecimal("65422.7"), true)

        val files = DerivativeReportFiles.render(listOf(roll), listOf(fill), listOf(settlement)).toMap()

        assertThat(files.keys).containsExactly("rolls.csv", "contracts.csv", "settlements.csv")
        assertThat(files.getValue("rolls.csv")).isEqualTo(
            "timestamp,stream,strategy,from,to,quantity,multiplier,fromReference,toReference,gap," +
                "fromFill,toFill,fees,rollCost\n" +
                "1726732800000,BINANCE_UM:BTCUSDT@front,s,BINANCE_UM:BTCUSDT_240927,BINANCE_UM:BTCUSDT_241227," +
                "0.01,1,62206.4,63343.9,1137.5,62206.2,63344.1,0.6,0.604\n",
        )
        assertThat(files.getValue("contracts.csv")).isEqualTo(
            "timestamp,strategy,stream,orderId,contract,side,quantity,contractPrice,streamPrice\n" +
                "1726732800000,s,BINANCE_UM:BTCUSDT@front,o1,BINANCE_UM:BTCUSDT_241227,BUY,0.01,63344.1,60140.7\n",
        )
        assertThat(files.getValue("settlements.csv")).isEqualTo(
            "timestamp,strategy,contract,side,quantity,price,deliveryPriceKnown\n" +
                "1726732800000,s,BINANCE_UM:BTCUSDT_240927,SELL,0.01,65422.7,true\n",
        )
    }
}
