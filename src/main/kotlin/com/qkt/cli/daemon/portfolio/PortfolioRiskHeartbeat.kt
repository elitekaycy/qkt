package com.qkt.cli.daemon.portfolio

import com.qkt.app.EngineSnapshotTimeoutException
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(PortfolioRiskHeartbeat::class.java)

/**
 * One book's risk evaluations and what their failures mean. A read that errors means the book
 * cannot be judged, so [ERROR_LIMIT] in a row fail it closed. A read the engine answers late
 * says only that the engine is busy, so it does not count as an error; the book halts on slow
 * reads only after [SLOW_READ_LIMIT] in a row, each at least the 5s query timeout, about two
 * minutes in which no book limit was checked.
 */
internal class PortfolioRiskHeartbeat(
    private val evaluate: () -> Unit,
    private val failClosed: (String) -> Unit,
) {
    private var errors = 0
    private var slowReads = 0

    /** Runs one evaluation. */
    fun beat() {
        try {
            evaluate()
            errors = 0
            slowReads = 0
        } catch (e: EngineSnapshotTimeoutException) {
            slowReads++
            log.warn("portfolio risk not evaluated, engine busy ({}/{}): {}", slowReads, SLOW_READ_LIMIT, e.message)
            if (slowReads >= SLOW_READ_LIMIT) {
                failClosed("portfolio risk unevaluated for $slowReads consecutive slow reads: ${e.message}")
            }
        } catch (e: Exception) {
            errors++
            log.warn("portfolio risk eval failed ({}/{}): {}", errors, ERROR_LIMIT, e.message, e)
            if (errors >= ERROR_LIMIT) {
                failClosed("portfolio risk evaluation failed $errors consecutive times: ${e.message}")
            }
        }
    }

    companion object {
        const val ERROR_LIMIT = 3
        const val SLOW_READ_LIMIT = 20
    }
}

/**
 * Takes one bar-aligned book sample on the market-feed thread. A failed sample is logged and
 * skipped: letting it escape would end that thread, and with it the book's gates.
 */
internal fun sampleBook(
    timestampMs: Long,
    sample: (Long) -> Unit,
) {
    try {
        sample(timestampMs)
    } catch (e: Exception) {
        log.warn("portfolio book sample at {} skipped: {}", timestampMs, e.message)
    }
}
