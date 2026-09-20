package com.qkt.connector.mt5

/**
 * Converts between the venue's wall-clock epochs and UTC. MT5 reports times in the broker server's
 * zone, so every timestamp crossing the gateway boundary goes through here.
 */
internal class MT5VenueTime(
    private val serverTimeZone: MT5ServerTimeZone,
) {
    /**
     * The gateway returns MT5 epochs whose fields encode the broker's wall clock, not UTC
     * (measured on IC Markets: deal `time_msc` runs exactly one server offset ahead of the
     * engine's own fill timestamp for the same ticket). Every epoch that leaves this client
     * is corrected here, once — the same translation the live tick feed applies — so deals,
     * positions, pending orders, and ticks agree with the engine clock. Identity for UTC venues.
     */
    fun venueEpochToUtc(epoch: Long): Long {
        if (epoch <= 0L) return epoch
        // Gateway fields mix second and millisecond epochs (`time` vs `time_msc`); keep the unit.
        val seconds = epoch < EPOCH_MS_THRESHOLD
        val ms = if (seconds) epoch * 1_000L else epoch
        val utc = serverTimeZone.serverEpochToUtc(ms)
        return if (seconds) utc / 1_000L else utc
    }

    fun venueIso(utcMs: Long): String =
        serverTimeZone
            .toServerLocal(java.time.Instant.ofEpochMilli(utcMs))
            .toInstant(java.time.ZoneOffset.UTC)
            .toString()

    private companion object {
        /** Epochs below this are seconds, not milliseconds (100_000_000_000 ms is 1973). */
        const val EPOCH_MS_THRESHOLD = 100_000_000_000L
    }
}
