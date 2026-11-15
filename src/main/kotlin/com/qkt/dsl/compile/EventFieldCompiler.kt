package com.qkt.dsl.compile

import com.qkt.common.Money
import com.qkt.dsl.DslVocabulary
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.instrument.EventTerms
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Compiles a binary event contract's fields off its [EventTerms]: `floor_strike` and `cap_strike` (the predicate's
 * bounds), `close_at`, `expiry_at` and `occurs_at` (epoch ms), `minutes_left` and `minutes_to_occurs` (minutes from
 * evaluation time, the bar's close, to the close and to the occasion, six decimals, negative once passed), `title`
 * (a string: compare with `=` or `!=`) and `taker_fee` / `maker_fee`.
 *
 * The fee fields are what ONE contract pays at the stream's current ask (taker) or bid (maker) under the contract's
 * [com.qkt.instrument.ProbabilityFee]: `rate × payout × q × (1 − q)` with `q = price / payout` (on a 1-payout
 * contract, `rate × p × (1 − p)`), **unrounded**: the venue rounds per order, so one contract's share of an order's fee
 * is this value, not the rounded single-contract fee. A fee with no maker rate reads 0 for `maker_fee`.
 *
 * A bound, time or label the contract does not carry is Undefined, and so is a fee while the stream has no quote on
 * that side. A stream that is not an event contract, or a fee field on one whose fee is unknown, is refused before
 * the strategy starts ([com.qkt.app.requireEventTerms]), so neither reads as a silent Undefined or zero.
 */
internal object EventFieldCompiler {
    /** The fields this compiler owns. */
    val fields: Set<String> = DslVocabulary.eventFields.toSet()

    /** The fields that need the contract's fee model. */
    val feeFields: Set<String> = setOf("taker_fee", "maker_fee")

    private val minute = BigDecimal(60_000)

    fun compile(ref: StreamFieldRef): CompiledExpr {
        val quote = if (ref.field in feeFields) quoteSide(ref) else null
        return CompiledExpr { ctx ->
            val key = ctx.streams[ref.stream] ?: error("Unknown stream alias: ${ref.stream}")
            val terms =
                ctx.strategyContext.instruments
                    .lookup(key.qktSymbol)
                    ?.derivative as? EventTerms ?: return@CompiledExpr Value.Undefined
            val now = ctx.nowMs()
            when (ref.field) {
                "floor_strike" -> num(terms.predicate.floor)
                "cap_strike" -> num(terms.predicate.cap)
                "close_at" -> Value.Num(BigDecimal.valueOf(terms.closeAtMs))
                "expiry_at" -> Value.Num(BigDecimal.valueOf(terms.expiryMs))
                "occurs_at" -> num(terms.occursAtMs?.let { BigDecimal.valueOf(it) })
                "minutes_left" -> Value.Num(minutes(terms.closeAtMs - now))
                "minutes_to_occurs" -> num(terms.occursAtMs?.let { minutes(it - now) })
                "title" -> terms.title?.let(Value::Str) ?: Value.Undefined
                "taker_fee", "maker_fee" -> fee(ref.field, terms, requireNotNull(quote).evaluate(ctx))
                else -> error("unreachable: ${ref.field}")
            }
        }
    }

    /**
     * One contract's fee at [price] under [terms]' fee model: unrounded, `rate × payout × q × (1 − q)`, `q` the
     * price as a fraction of the payout. Undefined without a price; a missing fee model is refused at start.
     */
    private fun fee(
        field: String,
        terms: EventTerms,
        price: Value,
    ): Value {
        val model = terms.fee ?: return Value.Undefined
        val p = (price as? Value.Num)?.v ?: return Value.Undefined
        val rate = if (field == "taker_fee") model.takerRate else model.makerRate ?: BigDecimal.ZERO
        val q = if (terms.payout.compareTo(BigDecimal.ONE) == 0) p else p.divide(terms.payout, Money.CONTEXT)
        return Value.Num(rate.multiply(terms.payout).multiply(q).multiply(BigDecimal.ONE.subtract(q)))
    }

    /** The side a fee field prices at: the ask for a taker, the bid for a maker, read as `<alias>.ask` / `.bid`. */
    private fun quoteSide(ref: StreamFieldRef): CompiledExpr =
        StreamFieldCompiler.compile(StreamFieldRef(ref.stream, if (ref.field == "taker_fee") "ask" else "bid"))

    private fun num(value: BigDecimal?): Value = value?.let(Value::Num) ?: Value.Undefined

    private fun minutes(ms: Long): BigDecimal = BigDecimal(ms).divide(minute, 6, RoundingMode.HALF_EVEN)
}
