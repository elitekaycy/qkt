package com.qkt.cli

import com.qkt.common.SymbolCalendars
import com.qkt.connectivity.ConnectorRegistry
import com.qkt.instrument.InstrumentRegistry

/**
 * The calendars of every venue qkt knows, by lower-case name, from config alone: each `brokers:` entry through its
 * connector's [com.qkt.connectivity.Connector.tradingHours] (the calendars the live account trades on), then each
 * connector's built-in profiles no entry of that name overrides. An entry whose connector is not installed is left out.
 */
internal fun Config.venueCalendars(
    registry: ConnectorRegistry = ConnectorRegistry.discover(),
): Map<String, SymbolCalendars> {
    val configured =
        accountConfigs()
            .groupBy { it.type }
            .flatMap { (type, entries) ->
                registry
                    .find(type)
                    ?.tradingHours(entries)
                    ?.entries
                    .orEmpty()
            }.associate { (name, calendars) -> name.lowercase() to calendars }
    return builtInVenueCalendars(registry) + configured
}

/**
 * The backtest [CalendarResolver] for this config and `--venue` [venue]: venue names come from `brokers:` and the
 * connectors' built-in profiles; their calendars are read only when a stream's prefix or [venue] names one, and then all together: a
 * config whose broker calendars cannot be read is refused naming the reason, as the live daemon would refuse it.
 */
internal fun Config.backtestCalendars(
    venue: String?,
    instruments: InstrumentRegistry?,
): CalendarResolver {
    val names = brokers.keys.map { it.lowercase() }.toSet() + builtInVenueCalendars(ConnectorRegistry.discover()).keys
    val venues =
        lazy {
            try {
                venueCalendars()
            } catch (e: RuntimeException) {
                val unreadable =
                    e is IllegalArgumentException || e is IllegalStateException || e is java.time.DateTimeException
                if (!unreadable) throw e
                throw BacktestContext.Companion.SetupError(
                    "broker calendars in the config cannot be read: ${e.message}",
                )
            }
        }
    return CalendarResolver.backtest(names, venues, venue, instruments)
}

/** Every installed connector's built-in venue profiles' calendars, by lower-case name. */
private fun builtInVenueCalendars(registry: ConnectorRegistry): Map<String, SymbolCalendars> =
    registry.types
        .mapNotNull(registry::find)
        .flatMap { it.builtInTradingHours().entries }
        .associate { (name, calendars) -> name.lowercase() to calendars }
