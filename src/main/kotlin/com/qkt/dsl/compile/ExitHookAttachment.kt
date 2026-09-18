package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Log
import com.qkt.dsl.ast.Sell
import com.qkt.strategy.Signal

/**
 * The `ON_STOP` / `ON_TP` / `ON_CLOSE` rules of a hook-bearing `BUY`/`SELL`: which parents and
 * children v1 accepts, and the stamping of the registered [ExitHookRef] onto every order signal
 * the parent emits so the runtime can run the hooks when that position exits.
 */
internal object ExitHookAttachment {
    /** Rejects hook combinations v1 does not support, before anything is compiled. */
    fun validate(opts: ActionOpts) {
        require(opts.onFill.isEmpty()) { "Exit hooks cannot be combined with ON_FILL in v1." }
        require(opts.oco == null && opts.stackAts.isEmpty()) {
            "Exit hooks support plain, BRACKET, or STACK parents in v1; OCO and STACK_AT are not supported."
        }
        val children = opts.exitHooks.onStop + opts.exitHooks.onTakeProfit + opts.exitHooks.onClose
        children.forEach { child ->
            require(child is Buy || child is Sell || child is Log) {
                "Exit-hook children must be BUY, SELL, or LOG actions; got ${child::class.simpleName}"
            }
            val childOpts =
                when (child) {
                    is Buy -> child.opts
                    is Sell -> child.opts
                    is Log -> return@forEach
                    else -> error("validated above")
                }
            require(childOpts.exitHooks.isEmpty() && childOpts.onFill.isEmpty()) {
                "Exit-hook children cannot declare ON_FILL or nested ON_* hooks in v1."
            }
            require(childOpts.oco == null && childOpts.stack == null && childOpts.stackAts.isEmpty()) {
                "Exit-hook children may carry a BRACKET but not OCO, STACK, or STACK_AT in v1."
            }
        }
    }

    /** Wraps [base] so each order signal it emits carries [ref]. */
    fun attach(
        base: (EvalContext) -> List<Signal>,
        ref: ExitHookRef,
    ): (EvalContext) -> List<Signal> =
        { ctx ->
            base(ctx).map { signal ->
                when (signal) {
                    is Signal.Buy -> signal.copy(exitHook = ref)
                    is Signal.Sell -> signal.copy(exitHook = ref)
                    is Signal.Submit -> signal.copy(exitHook = ref)
                    else -> error("Exit hooks require an order-producing BUY/SELL action")
                }
            }
        }
}
