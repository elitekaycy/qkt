package com.qkt.cli.observe

import okhttp3.OkHttpClient

abstract class ObservabilityServerFixture {
    protected val client = OkHttpClient()

    protected fun server(
        ring: EventRing = EventRing(),
        statusProvider: () -> StatusSnapshot = { error("not implemented in this test") },
        running: () -> Boolean = { true },
        onStop: (Boolean) -> Unit = {},
    ): ObservabilityServer =
        ObservabilityServer(
            ring = ring,
            statusProvider = statusProvider,
            running = running,
            onStop = onStop,
            bind = "127.0.0.1",
            port = 0,
        )
}
