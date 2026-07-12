package com.qkt.broker.mt5

internal data class MT5RecoverySnapshot(
    val pendingOrders: List<MT5PendingOrder>,
    val positions: List<MT5Position>,
)

internal fun readMT5RecoverySnapshot(
    attempts: Int,
    backoffMs: Long,
    onFailedAttempt: (attempt: Int, reason: String) -> Unit,
    readPendingOrders: () -> List<MT5PendingOrder>?,
    readPositions: () -> List<MT5Position>?,
): MT5RecoverySnapshot {
    require(attempts > 0) { "attempts must be > 0: $attempts" }
    require(backoffMs >= 0L) { "backoffMs must be >= 0: $backoffMs" }
    var lastReason = "unknown read failure"
    for (attempt in 1..attempts) {
        val pendingRead = runCatching(readPendingOrders)
        val positionRead = runCatching(readPositions)
        val pending = pendingRead.getOrNull()
        val positions = positionRead.getOrNull()
        if (pending != null && positions != null) return MT5RecoverySnapshot(pending, positions)

        val failures = mutableListOf<String>()
        if (pending == null) failures += "pending orders: ${pendingRead.exceptionOrNull()?.message ?: "unavailable"}"
        if (positions == null) failures += "positions: ${positionRead.exceptionOrNull()?.message ?: "unavailable"}"
        lastReason = failures.joinToString()
        onFailedAttempt(attempt, lastReason)
        if (attempt < attempts && backoffMs > 0L) Thread.sleep(backoffMs * attempt)
    }
    error("MT5 recovery venue reads failed $attempts times: $lastReason")
}
