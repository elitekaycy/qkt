package com.qkt.broker

import java.time.ZoneId

/**
 * Opt-in ability of an order-entry session: the time zone of the broker's server clock, which
 * `SCHEDULE … BROKER` rules are written against (e.g. an MT5 server on New York close time).
 */
interface ServerTimeZoneProvider {
    /** The broker server clock's zone. */
    fun serverTimeZone(): ZoneId
}
