package com.qkt.dsl.compile

import com.qkt.common.Money
import com.qkt.dsl.ast.AggFn
import java.math.BigDecimal

sealed interface AggregateState {
    fun update(v: BigDecimal)

    fun read(): BigDecimal?

    fun reset()

    companion object {
        fun sinceOpen(fn: AggFn): AggregateState = SinceOpenState(fn)

        fun sinceT(
            fn: AggFn,
            n: Int,
        ): AggregateState {
            require(n > 0) { "sinceT N must be > 0: $n" }
            return SinceTState(fn, n)
        }
    }
}

private class SinceOpenState(
    private val fn: AggFn,
) : AggregateState {
    private var min: BigDecimal? = null
    private var max: BigDecimal? = null
    private var sum: BigDecimal = BigDecimal.ZERO
    private var count: Int = 0

    override fun update(v: BigDecimal) {
        min = min?.let { it.min(v) } ?: v
        max = max?.let { it.max(v) } ?: v
        sum = sum.add(v, Money.CONTEXT)
        count += 1
    }

    override fun read(): BigDecimal? {
        if (count == 0) return null
        return when (fn) {
            AggFn.MIN -> min
            AggFn.MAX -> max
            AggFn.SUM -> sum
            AggFn.MEAN -> sum.divide(BigDecimal(count), Money.CONTEXT)
        }
    }

    override fun reset() {
        min = null
        max = null
        sum = BigDecimal.ZERO
        count = 0
    }
}

private class SinceTState(
    private val fn: AggFn,
    private val n: Int,
) : AggregateState {
    private val buffer: ArrayDeque<BigDecimal> = ArrayDeque(n)

    override fun update(v: BigDecimal) {
        buffer.addLast(v)
        while (buffer.size > n) buffer.removeFirst()
    }

    override fun read(): BigDecimal? {
        if (buffer.size < n) return null
        return when (fn) {
            AggFn.MIN -> buffer.reduce { a, b -> a.min(b) }
            AggFn.MAX -> buffer.reduce { a, b -> a.max(b) }
            AggFn.SUM -> buffer.fold(BigDecimal.ZERO) { acc, x -> acc.add(x, Money.CONTEXT) }
            AggFn.MEAN ->
                buffer
                    .fold(BigDecimal.ZERO) { acc, x -> acc.add(x, Money.CONTEXT) }
                    .divide(BigDecimal(n), Money.CONTEXT)
        }
    }

    override fun reset() {
        buffer.clear()
    }
}
