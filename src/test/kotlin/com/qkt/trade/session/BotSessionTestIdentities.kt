package com.qkt.trade.session

/** The streams a one-symbol 1m test session declares. */
internal fun testStreams(symbol: String = "EXNESS:XAUUSD"): BotSessionStreams =
    BotSessionStreams(listOf(symbol), listOf("1m"))

/** A compiled session identity over [testStreams], for tests that drive a session directly. */
internal fun testBridge(
    identity: String = "brain",
    symbol: String = "EXNESS:XAUUSD",
    history: BarHistory? = null,
): BotIntentStrategy =
    BotSessionIdentities.compile(testStreams(symbol), listOf(identity), history = history).getValue(identity)
