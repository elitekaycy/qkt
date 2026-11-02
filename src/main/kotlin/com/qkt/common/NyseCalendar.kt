package com.qkt.common

import java.time.LocalTime

/**
 * New York Stock Exchange regular hours: 09:30-16:00 America/New_York on trading days, 09:30-13:00 on the early-close
 * days, closed at weekends, on exchange holidays and on the one-off closures [NyseSessions] lists. Daylight saving is
 * followed through the zone, so the open is 13:30 UTC in summer and 14:30 UTC in winter. Pre-market and after-hours
 * trading are not part of the session; a venue quoting other hours on NYSE days uses [NyseWindowCalendar].
 */
object NyseCalendar : NyseWindowCalendar(LocalTime.of(9, 30), NyseSessions.REGULAR_CLOSE, "nyse")
