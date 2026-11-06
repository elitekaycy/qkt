package com.qkt.connector.mt5

import com.qkt.broker.PositionAccountingMode
import com.qkt.common.FixedClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** The account mode and margin level a broker reads from `/account`, with a five-second margin cache. */
class MT5BrokerAccountViewTest {
    private val gateway = Mt5RoutedGateway()
    private val profile = MT5DefaultProfiles.exness.copy(gatewayUrl = gateway.url, retryAttempts = 0)
    private val clock = FixedClock(time = 1_000_000L)
    private val view =
        MT5BrokerAccountView(
            profile,
            MT5Client(gateway.url, MT5ServerTimeZone.UTC, httpTimeoutMs = 2_000L, retryAttempts = 0),
            clock,
        )

    @AfterEach
    fun stop() = gateway.close()

    private fun account(
        marginMode: Int,
        marginLevel: String,
    ) = """{"balance":1000,"equity":1000,"currency":"USD","margin_mode":$marginMode,"margin_level":$marginLevel}"""

    @Test
    fun `a netting account is read as netting and remembered`() {
        gateway.on("/account", account(MARGIN_MODE_NETTING, "900"))

        assertThat(view.positionAccountingMode()).isEqualTo(PositionAccountingMode.NETTING)
        assertThat(view.positionAccountingMode()).isEqualTo(PositionAccountingMode.NETTING)
        assertThat(gateway.calls("/account")).hasSize(1)
    }

    @Test
    fun `a stale margin level is refreshed and a fresh one is left alone`() {
        gateway.on("/account", account(MARGIN_MODE_HEDGING, "900"), account(MARGIN_MODE_HEDGING, "450"))
        view.refreshMarginLevelIfStale()
        assertThat(gateway.requests).isEmpty()

        assertThat(view.marginLevel()).isEqualByComparingTo("900")
        clock.time += 1_000L
        view.refreshMarginLevelIfStale()
        assertThat(gateway.calls("/account")).hasSize(1)

        clock.time += 5_000L
        view.refreshMarginLevelIfStale()

        assertThat(gateway.calls("/account")).hasSize(2)
        assertThat(view.marginLevel()).isEqualByComparingTo("450")
    }
}
