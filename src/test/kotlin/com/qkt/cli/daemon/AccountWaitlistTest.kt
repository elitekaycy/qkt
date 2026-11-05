package com.qkt.cli.daemon

import com.qkt.broker.BrokerFactory
import com.qkt.broker.GatedBrokerFactory
import com.qkt.broker.LogBroker
import com.qkt.common.FixedClock
import com.qkt.common.SymbolCalendars
import com.qkt.common.TradingCalendar
import com.qkt.connectivity.AccountConfig
import com.qkt.connectivity.AccountProfile
import com.qkt.connectivity.AccountType
import com.qkt.connectivity.TradingAccount
import java.net.UnknownHostException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** An account unreachable at boot keeps its strategies stopped until it answers, then lets them start (#1642). */
class AccountWaitlistTest {
    private val clock = FixedClock(1_000L)

    @Volatile private var answer: () -> AccountProfile = { throw unreachable() }

    private val alpaca =
        object : TradingAccount {
            override val config = AccountConfig("alpaca", "gateway", mapOf("type" to "gateway"))
            override val orderEntry: BrokerFactory = { bus, clock, _, _, _ -> LogBroker(bus, clock) }
            override val marketData = null
            override val tradingHours = SymbolCalendars(emptyList(), TradingCalendar.crypto())

            override fun verify(): AccountProfile = answer()

            override fun close() {}
        }

    private fun unreachable() =
        IllegalStateException("/v1/health: no answer after 3 attempts", UnknownHostException("gw-alpaca"))

    private val paper = AccountProfile("alpaca", "PA1", "gw", AccountType.DEMO, "USD", null, "alpaca: paper")
    private val exnessFactory: BrokerFactory = { bus, clock, _, _, _ -> LogBroker(bus, clock) }

    private fun waitlist() = AccountWaitlist(listOf(alpaca to unreachable()), clock, 2_000L, 30_000L)

    @Test
    fun `only the unreachable account's factory refuses, naming the account and why`() {
        val gated = waitlist().gate(mapOf("exness" to exnessFactory, "alpaca" to alpaca.orderEntry))

        assertThat(gated["exness"]).isSameAs(exnessFactory)
        assertThatThrownBy { (gated["alpaca"] as GatedBrokerFactory).ensureOpen() }
            .hasMessageContaining("account alpaca is not verified")
            .hasMessageContaining("no answer after 3 attempts")
    }

    @Test
    fun `the account is retried with growing gaps and opens when it answers`() {
        val list = waitlist()
        val verified = mutableListOf<String>()
        list.onVerified = { account, _ -> verified += account.config.name }
        val gated = list.gate(mapOf("alpaca" to alpaca.orderEntry))["alpaca"] as GatedBrokerFactory

        assertThat(list.retryDue(2_999L)).isEmpty()
        assertThat(list.retryDue(3_000L)).isEmpty()
        assertThat(list.waiting().single().nextAttemptAtMs).isEqualTo(7_000L)
        assertThat(list.waiting().single().attempts).isEqualTo(2)

        answer = { paper }
        assertThat(list.retryDue(7_000L)).containsExactly("alpaca")

        assertThat(verified).containsExactly("alpaca")
        assertThat(list.waiting()).isEmpty()
        gated.ensureOpen()
    }

    @Test
    fun `an account that answers with the wrong identity stays refused and is not retried`() {
        val list = waitlist()
        answer = { error("alpaca: expected account PA1, gateway reports PA2") }

        list.retryDue(3_000L)
        answer = { paper }

        assertThat(list.retryDue(1_000_000L)).isEmpty()
        assertThat(list.waiting().single().retrying).isFalse()
        assertThat(list.refusal("ALPACA")).contains("expected account PA1")
    }
}
