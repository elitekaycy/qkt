package com.qkt.connectivity

import com.qkt.broker.BrokerFactory
import com.qkt.broker.LogBroker
import com.qkt.common.SymbolCalendars
import com.qkt.common.TradingCalendar
import java.net.UnknownHostException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** The daemon's boot check does not let one unreachable account hold every other one (#1642). */
class AccountPreflightReachableTest {
    private class Account(
        name: String,
        private val answer: () -> AccountProfile,
    ) : TradingAccount {
        override val config = AccountConfig(name, "fake", mapOf("type" to "fake"))
        override val orderEntry: BrokerFactory = { bus, clock, _, _, _ -> LogBroker(bus, clock) }
        override val marketData = null
        override val tradingHours = SymbolCalendars(emptyList(), TradingCalendar.crypto())

        override fun verify(): AccountProfile = answer()

        override fun close() {}
    }

    private fun profile(name: String) = AccountProfile(name, "id", "srv", AccountType.DEMO, "USD", null, "$name: ok")

    private val down =
        Account("alpaca") {
            throw IllegalStateException("/v1/health: no answer after 3 attempts", UnknownHostException("gw-alpaca"))
        }

    @Test
    fun `an unreachable account is reported, not waited for, and the others verify`() {
        var slept = 0L
        val preflight = AccountPreflight(deadlineMs = 600_000L, sleep = { slept += it })

        val boot =
            preflight.verifyReachable(
                listOf(Account("exness") { profile("exness") }, down, Account("deribit") { profile("deribit") }),
            )

        assertThat(boot.verified.map { it.first.config.name }).containsExactly("exness", "deribit")
        assertThat(boot.unreachable.map { it.first.config.name }).containsExactly("alpaca")
        assertThat(boot.unreachable.single().second).hasMessageContaining("no answer after 3 attempts")
        assertThat(slept).isZero()
    }

    @Test
    fun `an identity mismatch still refuses the boot at once`() {
        val wrong = Account("exness") { error("MT5 profile 'exness' account login mismatch: expected 1, got 2") }

        assertThatThrownBy { AccountPreflight().verifyReachable(listOf(down, wrong)) }
            .hasMessageContaining("account login mismatch")
    }
}
