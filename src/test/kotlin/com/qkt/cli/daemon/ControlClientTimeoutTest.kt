package com.qkt.cli.daemon

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Deploy/resync of a multi-child portfolio runs for minutes on the daemon while the CLI
 * waits on one synchronous control call. OkHttp's stock 10s read timeout made the CLI
 * exit nonzero mid-operation while the daemon completed anyway — a false failure that
 * automation (qkt-forge forward-resync) treated as a real one.
 */
class ControlClientTimeoutTest {
    @Test
    fun `default control http client tolerates long-running deploy and resync calls`() {
        val http = ControlClient.defaultHttp()
        assertThat(http.readTimeoutMillis).isEqualTo(30 * 60 * 1000)
        assertThat(http.connectTimeoutMillis).isEqualTo(10_000)
    }
}
