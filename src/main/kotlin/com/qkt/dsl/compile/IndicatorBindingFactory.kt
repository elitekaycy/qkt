package com.qkt.dsl.compile

import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.stdlib.IndicatorInput
import com.qkt.indicators.IndicatorOutput

/**
 * Builds an [IndicatorBinding] for each way an indicator can be fed: straight from a stream
 * field, from another indicator's output, from a compiled series expression, from an aligned
 * pair of series, or from an aligned tuple of k series.
 */
internal object IndicatorBindingFactory {
    fun streamFed(
        call: IndicatorCall,
        indicator: IndicatorOutput,
        streamAlias: String,
        field: String?,
        inputKind: IndicatorInput,
    ): IndicatorBinding =
        IndicatorBinding(call, indicator, streamAlias, field, inputKind, source = null, seriesExpr = null)

    fun indicatorFed(
        call: IndicatorCall,
        indicator: IndicatorOutput,
        source: IndicatorBinding,
    ): IndicatorBinding =
        IndicatorBinding(
            call,
            indicator,
            streamAlias = null,
            field = null,
            inputKind = IndicatorInput.NUMERIC_SERIES,
            source = source,
            seriesExpr = null,
        )

    fun expressionFed(
        call: IndicatorCall,
        indicator: IndicatorOutput,
        primaryAlias: String,
        seriesExpr: CompiledExpr,
        inputKind: IndicatorInput,
    ): IndicatorBinding =
        IndicatorBinding(
            call,
            indicator,
            streamAlias = primaryAlias,
            field = null,
            inputKind = inputKind,
            source = null,
            seriesExpr = seriesExpr,
        )

    fun seriesFedPair(
        call: IndicatorCall,
        indicator: IndicatorOutput,
        primaryAlias: String,
        seriesExprA: CompiledExpr,
        seriesExprB: CompiledExpr,
    ): IndicatorBinding =
        IndicatorBinding(
            call,
            indicator,
            streamAlias = primaryAlias,
            field = null,
            inputKind = IndicatorInput.NUMERIC_SERIES,
            source = null,
            seriesExpr = seriesExprA,
            seriesExprB = seriesExprB,
        )

    fun seriesFedMulti(
        call: IndicatorCall,
        indicator: IndicatorOutput,
        primaryAlias: String,
        seriesExprs: List<CompiledExpr>,
    ): IndicatorBinding =
        IndicatorBinding(
            call,
            indicator,
            streamAlias = primaryAlias,
            field = null,
            inputKind = IndicatorInput.NUMERIC_SERIES,
            source = null,
            seriesExpr = null,
            seriesExprsMulti = seriesExprs,
        )
}
