package com.qkt.observe.insights

/**
 * Translates qkt bus events into [InsightsEnvelope]s matching the collector's contract.
 * Pure functions, no I/O — cheap enough for the engine thread. Returns null only for
 * source events that have no useful insights representation.
 *
 * Envelope ids combine the event identity fields so a re-sent batch dedupes at the
 * collector without colliding with another strategy session's bus sequence.
 *
 * Each event family's translators live in their own interface ([SignalInsights],
 * [OrderInsights], [TradeInsights], ...); this object mixes them into the single entry
 * point callers use.
 */
object InsightsTranslate :
    SignalInsights,
    OrderInsights,
    TradeInsights,
    RiskInsights,
    BrokerInsights,
    MarketDataInsights,
    VenueStateInsights,
    SessionInsights,
    PortfolioInsights,
    EquityInsights
