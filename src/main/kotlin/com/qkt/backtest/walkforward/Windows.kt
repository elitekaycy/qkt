package com.qkt.backtest.walkforward

import com.qkt.backtest.EquitySample
import com.qkt.common.Money
import com.qkt.common.TimeRange
import java.math.BigDecimal
import java.time.Duration

internal fun concatenate(curves: List<List<EquitySample>>): List<EquitySample> {
    val out = mutableListOf<EquitySample>()
    var runningOffset: BigDecimal = Money.ZERO
    for (curve in curves) {
        if (curve.isEmpty()) continue
        for (sample in curve) {
            out.add(EquitySample(sample.timestamp, sample.equity.add(runningOffset)))
        }
        runningOffset = runningOffset.add(curve.last().equity)
    }
    return out
}

internal fun rollingWindows(
    total: TimeRange,
    trainSize: Duration,
    testSize: Duration,
    stepSize: Duration,
): List<Pair<TimeRange, TimeRange>> {
    val folds = mutableListOf<Pair<TimeRange, TimeRange>>()
    var trainStart = total.from
    while (true) {
        val trainEnd = trainStart.plus(trainSize)
        val testEnd = trainEnd.plus(testSize)
        if (testEnd > total.to) break
        folds.add(TimeRange(trainStart, trainEnd) to TimeRange(trainEnd, testEnd))
        trainStart = trainStart.plus(stepSize)
    }
    return folds
}
