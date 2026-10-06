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
        val exitHook: com.qkt.dsl.compile.ExitHookRef? = null,
        /**
         * When true the pipeline submits the order even if the portfolio gate is inactive.
         * Used by regime-aware backtests to flatten a child when its gate closes.
         */
        val force: Boolean = false,
    ) : Signal()

    /** Open a short position at market (or close a long, depending on current state). */
    data class Sell(
        val symbol: String,
        val size: BigDecimal,
        val exitHook: com.qkt.dsl.compile.ExitHookRef? = null,
        /**
         * When true the pipeline submits the order even if the portfolio gate is inactive.
         * Used by regime-aware backtests to flatten a child when its gate closes.
         */
        val force: Boolean = false,
    ) : Signal()

    /** Submit a fully-constructed [OrderRequest] — limit, stop, bracket, scale-out, etc. */
    data class Submit(
        val request: OrderRequest,
        val exitHook: com.qkt.dsl.compile.ExitHookRef? = null,
    ) : Signal()

    /** Cancel every working order on [symbol]. Emitted by DSL `CANCEL` actions. */
    data class CancelPendingForSymbol(
        val symbol: String,
        /** True for a cancel that ends an option structure: it only removes risk, so the gate never drops it. */
        val force: Boolean = false,
        /** True for the cancel of a `CLOSE`: the close flattens the position, so no bracket exit is kept for it. */
        val closing: Boolean = false,
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

    /**
     * Option legs submitted together as group [structureId] for the structure opened under [alias]:
     * every leg must pass the per-order rules, the option margin judges them as one position, and
     * either all go to the venue (published buys before sells) or none does. Emitted by DSL
     * `OPEN … = OPTIONS ON …` actions, and as closing groups by `CLOSE` and a failed structure's unwind.
     */
    data class SubmitGroup(
        val structureId: String,
        val alias: String,
        val requests: List<OrderRequest>,
        /** The structure this group closes (an unwind or a `CLOSE`), or null when it opens [structureId]. */
        val closes: String? = null,
    ) : Signal() {
        /** A closing group only removes risk, so the portfolio gate never drops it. */
        val force: Boolean get() = closes != null
    }

    /** Intent intentionally suppressed before an order could be constructed. */
    data class Suppressed(
        val symbol: String,
        val reason: String,
    ) : Signal()
}

/** Symbol the signal targets, or null for latch arms and structure groups (whose legs carry their own symbols). */
fun Signal.targetSymbol(): String? =
    when (this) {
        is Signal.Buy -> symbol
        is Signal.Sell -> symbol
        is Signal.Submit -> request.symbol
        is Signal.CancelPendingForSymbol -> symbol
        is Signal.ArmLatch -> null
        is Signal.SubmitGroup -> null
        is Signal.Suppressed -> symbol
    }
