package com.qkt.dsl.compile

import com.qkt.dsl.ast.ExprAst
import com.qkt.strategy.Signal
import java.math.RoundingMode
import org.slf4j.Logger

/** Compiles the `TIMES <expr>` clause of a `BUY`/`SELL`: one action repeated N times per fire. */
internal class RepeatedActionCompiler(
    private val exprCompiler: ExprCompiler,
    private val strategyLogger: Logger,
) {
    /**
     * Wraps [once], the action compiled with its `TIMES` clause removed, so it runs N times per
     * evaluation. The body is invoked N times, so every repetition goes through the same
     * path a hand-written `BUY ...; BUY ...` would — its own order id, its own bracket, its own
     * stack or STACK_AT registration. The count is evaluated at fire time: a fraction truncates,
     * zero or less emits nothing, an undefined value (indicator still warming) emits nothing and
     * logs once, and a count above [ActionCompiler.MAX_TIMES] is suppressed rather than sent, since no
     * strategy means a thousand-order burst by accident.
     */
    fun compile(
        stream: String,
        timesExpr: ExprAst,
        once: (EvalContext) -> List<Signal>,
    ): (EvalContext) -> List<Signal> {
        val compiledTimes = exprCompiler.compile(timesExpr)
        val skipLog = WarmupSkipLog(strategyLogger)
        return repeat@{ ctx ->
            val symbol = ctx.streams[stream]?.qktSymbol ?: error("Unknown stream alias: $stream")
            val count =
                when (val v = compiledTimes.evaluate(ctx)) {
                    is Value.Num -> v.v.setScale(0, RoundingMode.DOWN)
                    Value.Undefined -> {
                        skipLog.skipped("TIMES count", ctx, stream)
                        return@repeat emptyList()
                    }
                    else -> error("TIMES must be numeric, got $v")
                }
            if (count.signum() <= 0) return@repeat emptyList()
            if (count > ActionCompiler.MAX_TIMES) {
                return@repeat listOf(
                    Signal.Suppressed(
                        symbol = symbol,
                        reason =
                            "TIMES evaluated to ${count.toPlainString()}, above the " +
                                "${ActionCompiler.MAX_TIMES} repetition cap",
                    ),
                )
            }
            val out = ArrayList<Signal>()
            repeat(count.toInt()) { out.addAll(once(ctx)) }
            out
        }
    }
}
