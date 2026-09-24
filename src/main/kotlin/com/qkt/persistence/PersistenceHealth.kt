package com.qkt.persistence

/** Point-in-time durability health exposed to live-session controls and operator status. */
data class PersistenceHealth(
    val enabled: Boolean,
    val totalWrites: Long = 0L,
    val slowWrites: Long = 0L,
    val failedWrites: Long = 0L,
    val consecutiveFailures: Long = failedWrites,
    val failureEpisodes: Long = if (failedWrites == 0L) 0L else 1L,
    val queueSize: Int = 0,
    val callerRunsTotal: Long = 0L,
) {
    companion object {
        /** Health for in-memory/no-op persistence where no durable writes are expected. */
        val DISABLED = PersistenceHealth(enabled = false)
    }
}
