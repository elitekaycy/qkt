package com.qkt.backtest.report

import com.qkt.backtest.TradeRecord
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.evidence.DatasetEvidence
import com.qkt.evidence.EvidenceEnvelope
import com.qkt.evidence.ExecutionEvidence
import com.qkt.execution.Trade
import com.qkt.marketdata.Tick
import java.math.BigDecimal

/** Ticks, evidence envelope and trade records the backtest report writer tests feed the writer. */
internal object BacktestReportFixtures {
    fun ticks(): List<Tick> = (1..5).map { i -> Tick("X", Money.of((100 + i).toString()), i * 60_000L) }

    fun evidence(): EvidenceEnvelope =
        EvidenceEnvelope(
            qktVersion = "test",
            gitSha = "abc123",
            buildTimestamp = "2026-06-25T00:00:00Z",
            command = listOf("backtest", "s.qkt"),
            strategyHash = "sha256:strategy",
            dataset = DatasetEvidence(mutableStore = true),
            execution = ExecutionEvidence(preset = "paper-fast", broker = "paper"),
        )

    fun tradeRecord(
        orderId: String,
        timestamp: Long,
        side: Side,
        realized: String,
        riskUsd: String,
        price: String,
        quantity: String,
        contractSize: String,
        fxSource: String = "test",
    ): TradeRecord =
        TradeRecord(
            trade =
                Trade(
                    orderId = orderId,
                    symbol = "XAUUSD",
                    price = BigDecimal(price),
                    quantity = BigDecimal(quantity),
                    side = side,
                    timestamp = timestamp,
                ),
            realized = BigDecimal(realized),
            strategyId = "s1",
            riskUsd = BigDecimal(riskUsd),
            nativeRealized = BigDecimal(realized),
            nativeCurrency = "USD",
            accountRealized = BigDecimal(realized),
            accountCurrency = "USD",
            fxRate = BigDecimal.ONE,
            fxRateTimestamp = timestamp,
            fxSource = fxSource,
            contractSize = BigDecimal(contractSize),
        )
}
