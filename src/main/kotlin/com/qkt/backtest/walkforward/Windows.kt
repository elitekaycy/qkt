package com.qkt.backtest.walkforward

import com.qkt.backtest.EquitySample
import com.qkt.common.TimeRange
import java.time.Duration

internal fun concatenate(curves: List<List<EquitySample>>): List<EquitySample> {
    val out = mutableListOf<EquitySample>()
    for (curve in curves) {
        if (curve.isEmpty()) continue
        val foldStart = curve.first().equity
        val stitchedStart = out.lastOrNull()?.equity ?: foldStart
        for (sample in curve) {
            val foldPnl = sample.equity.subtract(foldStart)
            out.add(EquitySample(sample.timestamp, stitchedStart.add(foldPnl)))
        }
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
