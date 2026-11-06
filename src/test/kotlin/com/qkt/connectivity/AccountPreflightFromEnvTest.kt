package com.qkt.connectivity

import com.qkt.common.SystemClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AccountPreflightFromEnvTest {
    @Test
    fun `the process environment preflight verifies reachable accounts`() {
        val context = ConnectorContext(stateRoot = null, env = emptyMap(), clock = SystemClock())
        val account =
            FakeConnector(
                "mt5",
            ).open(listOf(AccountConfig("a", "mt5", mapOf("type" to "mt5"))), context).single()

        val boot = AccountPreflight.fromEnv().verifyReachable(listOf(account))

        assertThat(boot.verified.map { it.second.description }).containsExactly("a: fake")
        assertThat(boot.unreachable).isEmpty()
    }
}
