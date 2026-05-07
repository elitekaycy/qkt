package com.qkt.backtest.walkforward

import com.qkt.backtest.Backtest
import com.qkt.backtest.BacktestResult
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

    fun run(): WalkForwardResult<C> = error("not yet implemented; see Task 6")
}
