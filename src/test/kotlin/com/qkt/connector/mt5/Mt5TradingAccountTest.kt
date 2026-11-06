package com.qkt.connector.mt5

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.connectivity.AccountConfig
import com.qkt.connectivity.AccountType
import com.qkt.connectivity.ConnectorContext
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class Mt5TradingAccountTest {
    private val gateway = Mt5RoutedGateway()
    private val asked = CopyOnWriteArrayList<String>()
    private val clock = FixedClock(time = 1_800_000_000_000L)

    @AfterEach
    fun stop() = gateway.close()

    private fun open(tradeMode: Int = MT5TradeMode.DEMO.wireValue): Mt5TradingAccount {
        val info =
            MT5AccountInfo(
                balance = BigDecimal("1000"),
                equity = BigDecimal("1000"),
                currency = "USD",
                leverage = 500,
                marginMode = MARGIN_MODE_NETTING,
                login = 7L,
                server = "Exness-MT5Trial9",
                tradeMode = tradeMode,
            )
        val context =
            ConnectorContext(
                stateRoot = null,
                env = emptyMap(),
                clock = clock,
                strategiesTrading = { account ->
                    asked.add(account)
                    listOf("alpha", "beta")
                },
            )
        val config =
            AccountConfig(
                name = "exness_s0",
                type = "mt5",
                settings =
                    mapOf(
                        "type" to "mt5",
                        "extends" to "exness",
                        "gateway_url" to gateway.url,
                        "magic" to "1001",
                        "retry_attempts" to "0",
                        "poll_interval_ms" to "3600000",
                    ),
            )
        return Mt5Connector(accountFetcher = { info }).open(listOf(config), context).single() as Mt5TradingAccount
    }

    private fun broker(
        account: Mt5TradingAccount,
        strategy: String?,
    ) = account.orderEntry(
        EventBus(clock, MonotonicSequenceGenerator()),
        clock,
        MarketPriceTracker(),
        StrategyPositionTracker().account,
        strategy,
    )

    @Test
    fun `a strategy's broker asks the account which other strategies share its login`() {
        gateway.on("/get_positions", "[]")
        gateway.on("/orders", "[]")
        val account = open()

        val broker = broker(account, "alpha")
        broker.shutdown()

        assertThat(asked).containsExactly("exness_s0")
        assertThat(gateway.calls("/get_positions")).isNotEmpty()
    }

    @Test
    fun `a session broker trades for no strategy and asks for no siblings`() {
        gateway.on("/get_positions", "[]")
        gateway.on("/orders", "[]")

        broker(open(), null).shutdown()

        assertThat(asked).isEmpty()
    }

    @Test
    fun `an instrument spec comes from the venue's symbol info and is absent when the venue has none`() {
        gateway.on(
            "/symbol_info/XAUUSDm",
            """{"ask":2400.5,"bid":2400,"digits":2,"point":0.01,"trade_stops_level":0,"volume_min":0.01,""" +
                """"volume_step":0.01,"trade_contract_size":100}""",
        )
        val account = open()

        val spec = account.instrumentSpec("EXNESS_S0:XAUUSD")

        assertThat(spec?.meta?.contractSize).isEqualByComparingTo("100")
        assertThat(spec?.meta?.digits).isEqualTo(2)
        assertThat(account.instrumentSpec("EXNESS_S0:EURUSD")).isNull()
    }

    @Test
    fun `contest and unreported trade modes map to their account types`() {
        assertThat(open(MT5TradeMode.CONTEST.wireValue).verify().type).isEqualTo(AccountType.CONTEST)
        assertThat(open(MT5TradeMode.DEMO.wireValue).verify().type).isEqualTo(AccountType.DEMO)
        assertThat(open(-1).verify().type).isEqualTo(AccountType.UNKNOWN)
    }
}
