package com.qkt.backtest.walkforward

import com.qkt.backtest.Backtest
import com.qkt.backtest.BacktestResult
import com.qkt.backtest.sweep.BacktestSweep
import com.qkt.common.Money
import com.qkt.common.TimeRange
import java.math.BigDecimal
import java.time.Duration

class WalkForwardHarness<C>(
    private val configs: List<Pair<String, C>>,
    private val backtestFactory: (label: String, config: C, range: TimeRange) -> Backtest,
    private val totalRange: TimeRange,
    private val trainSize: Duration,
    private val testSize: Duration,
    private val stepSize: Duration,
    private val scoreOf: (BacktestResult) -> BigDecimal,
    private val parallelism: Int = 1,
    private val topN: Int = 3,
) {
    init {
        require(parallelism >= 1) { "parallelism must be >= 1, got $parallelism" }
        require(topN >= 1) { "topN must be >= 1, got $topN" }
        require(!trainSize.isZero && !trainSize.isNegative) {
            "trainSize must be positive, got $trainSize"
        }
        require(!testSize.isZero && !testSize.isNegative) {
            "testSize must be positive, got $testSize"
        }
        require(!stepSize.isZero && !stepSize.isNegative) {
            "stepSize must be positive, got $stepSize"
        }
        require(configs.isNotEmpty()) { "configs must not be empty" }
        require(configs.map { it.first }.toSet().size == configs.size) {
            "config labels must be unique: ${configs.map { it.first }}"
        }
        require(configs.all { it.first.isNotBlank() }) { "config labels must be non-blank" }
        require(trainSize.plus(testSize).toMillis() <= totalRange.durationMs) {
            "totalRange duration (${totalRange.durationMs}ms) too short for trainSize + testSize"
        }
    }

    fun run(): WalkForwardResult<C> {
        val windows = rollingWindows(totalRange, trainSize, testSize, stepSize)
        val folds =
            windows.map { (trainRange, testRange) ->
                val sweep =
                    BacktestSweep(
                        configs = configs,
                        backtestFactory = { label, config -> backtestFactory(label, config, trainRange) },
                        parallelism = parallelism,
                    )
                val sweepResult = sweep.run()
                val ranked = sweepResult.rankedBy(scoreOf)
                val winner = ranked.first()
                val winnerScore = scoreOf(winner.result)
                val top = ranked.take(topN).map { it.label to scoreOf(it.result) }
                val testBacktest = backtestFactory(winner.label, winner.config, testRange)
                val testResult = testBacktest.run()
                WalkForwardFold(
                    trainRange = trainRange,
                    testRange = testRange,
                    winnerLabel = winner.label,
                    winnerConfig = winner.config,
                    trainScore = winnerScore,
                    testResult = testResult,
                    topConfigs = top,
                )
            }

        val trainScores = folds.map { it.trainScore }
        val testScores = folds.map { scoreOf(it.testResult) }

        return WalkForwardResult(
            folds = folds,
            winnerCounts = folds.groupingBy { it.winnerLabel }.eachCount(),
            meanTrainScore = mean(trainScores),
            meanTestScore = mean(testScores),
            concatenatedTestCurve = concatenate(folds.map { it.testResult.global.equityCurve }),
        )
    }
}

private fun mean(values: List<BigDecimal>): BigDecimal {
    if (values.isEmpty()) return Money.ZERO
    return values
        .fold(Money.ZERO) { a, v -> a.add(v) }
        .divide(BigDecimal(values.size), Money.CONTEXT)
        .setScale(Money.SCALE, Money.ROUNDING)
}
