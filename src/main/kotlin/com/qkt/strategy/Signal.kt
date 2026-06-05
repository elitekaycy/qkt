package com.qkt.strategy

import com.qkt.execution.OrderRequest
import java.math.BigDecimal

/**
 * A trading intent produced by a strategy.
 *
 * `Buy`/`Sell` are simple market entries; `Submit` carries a fully-built
 * [OrderRequest] (used by the DSL when it needs limit/stop/bracket shapes);
 * `CancelPendingForSymbol` cancels any working order tied to a symbol.
 */
sealed class Signal {
    /** Open a long position at market. */
    data class Buy(
        val symbol: String,
        val size: BigDecimal,
    ) : Signal()

    /** Open a short position at market (or close a long, depending on current state). */
    data class Sell(
        val symbol: String,
        val size: BigDecimal,
    ) : Signal()

    /** Submit a fully-constructed [OrderRequest] — limit, stop, bracket, scale-out, etc. */
    data class Submit(
        val request: OrderRequest,
    ) : Signal()

    /** Cancel every working order on [symbol]. Emitted by DSL `CANCEL` actions. */
    data class CancelPendingForSymbol(
        val symbol: String,
    ) : Signal()

    /**
     * Arm a compiled latch: hand off [compiled] to the [com.qkt.app.LatchManager] so it can
     * watch ticks, detect the first wire cross, and fan out the entry orders. The latch fires
     * at most once; if no wire is crossed before the arm window elapses it is dropped silently.
     *
     * [ec] is the evaluation context captured at rule-fire time; [compiled.reference] and
     * [compiled.offset] are evaluated against it to compute the trip-wire prices.
     */
    data class ArmLatch(
        val compiled: com.qkt.dsl.compile.CompiledLatch,
        val ec: com.qkt.dsl.compile.EvalContext,
    ) : Signal()
}
