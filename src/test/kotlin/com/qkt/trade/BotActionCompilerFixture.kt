package com.qkt.trade

import com.qkt.common.Side
import java.math.BigDecimal

abstract class BotActionCompilerFixture {
    protected val ctx =
        BotQuoteContext(
            bid = BigDecimal("2650.00"),
            ask = BigDecimal("2650.50"),
            equity = BigDecimal("10000"),
            balance = BigDecimal("9800"),
            contractSize = BigDecimal("100"),
            accountCurrency = "USD",
            quoteCurrency = "USD",
            volumeMin = BigDecimal("0.01"),
            volumeStep = BigDecimal("0.01"),
            volumeMax = BigDecimal("50"),
            digits = 2,
        )

    protected fun compile(
        intent: BotIntent,
        context: BotQuoteContext = ctx,
    ): CompiledBotOrder =
        compileBotAction(
            bot = parseBotStrategy(renderBotStrategy(intent)),
            ctx = context,
            id = "bot-1",
            timestamp = 1_000L,
            strategyId = "manual",
        )

    protected fun intent(
        side: Side = Side.BUY,
        lots: String? = "0.5",
        sizingDsl: String? = null,
        limit: String? = null,
        stop: String? = null,
        stopLimit: String? = null,
        sl: ExitSpec? = null,
        tp: ExitSpec? = null,
        tif: BotTif = BotTif.GTC,
        expiresAtMs: Long? = null,
    ) = BotIntent(
        side = side,
        qktSymbol = "EXNESS:XAUUSD",
        lots = lots?.let { BigDecimal(it) },
        sizingDsl = sizingDsl,
        limitPrice = limit?.let { BigDecimal(it) },
        stopPrice = stop?.let { BigDecimal(it) },
        stopLimitPrice = stopLimit?.let { BigDecimal(it) },
        sl = sl,
        tp = tp,
        tif = tif,
        expiresAtMs = expiresAtMs,
    )
}
