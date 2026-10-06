package com.qkt.connector.mt5

import com.qkt.common.FixedClock
import com.qkt.common.SystemClock
import com.qkt.connectivity.AccountConfig
import com.qkt.connectivity.AccountDirectory
import com.qkt.connectivity.AccountPreflight
import com.qkt.connectivity.AccountUnreachableException
import com.qkt.connectivity.ConnectorContext
import com.qkt.connectivity.ConnectorRegistry
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The daemon's boot preflight against a real MT5 gateway HTTP surface that is down, then back. */
class Mt5BootPreflightTest {
    private lateinit var server: HttpServer
    private val accountCalls = AtomicInteger()

    @Volatile
    private var unavailableCalls = 0

    @Volatile
    private var login = 435898347L

    private val clock = FixedClock(1_000L)
    private val sleeps = CopyOnWriteArrayList<Long>()
    private val logs = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/account") { exchange ->
            val call = accountCalls.incrementAndGet()
            val (status, body) =
                if (call <= unavailableCalls) {
                    503 to """{"error":"terminal not connected"}"""
                } else {
                    200 to
                        """{"login":$login,"server":"Exness-MT5Trial9","trade_mode":0,"balance":10000,""" +
                        """"equity":10000,"currency":"USD","leverage":100,"margin_mode":2}"""
                }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `an unreachable gateway is retried with doubling backoff until it answers`() {
        unavailableCalls = 3

        val verified = preflight(deadlineMs = 300_000L).verifyAll(directory())

        assertThat(verified.single().second.accountId).isEqualTo("435898347")
        assertThat(accountCalls.get()).isEqualTo(4)
        assertThat(sleeps).containsExactly(2_000L, 4_000L, 8_000L)
        assertThat(logs).hasSize(3).allMatch { it.contains("exness_s0 unreachable") }
    }

    @Test
    fun `an identity mismatch is refused at once without a retry`() {
        login = 999L

        assertThatThrownBy { preflight(deadlineMs = 300_000L).verifyAll(directory()) }
            .isNotInstanceOf(AccountUnreachableException::class.java)
            .hasMessageContaining("account login mismatch")
        assertThat(accountCalls.get()).isEqualTo(1)
        assertThat(sleeps).isEmpty()
    }

    @Test
    fun `a gateway still down at the deadline fails the preflight as before`() {
        unavailableCalls = Int.MAX_VALUE

        assertThatThrownBy { preflight(deadlineMs = 60_000L).verifyAll(directory()) }
            .isInstanceOf(AccountUnreachableException::class.java)
            .hasMessageContaining("gateway/account is unreachable")
            .hasMessageContaining("still unreachable after 60s")
        // 2 + 4 + 8 + 16 + 30 would pass the deadline: the last wait is cut to what is left.
        assertThat(sleeps).containsExactly(2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
        assertThat(sleeps.sum()).isEqualTo(60_000L)
    }

    @Test
    fun `a zero deadline keeps the old refuse-at-once behaviour`() {
        unavailableCalls = Int.MAX_VALUE

        assertThatThrownBy { preflight(deadlineMs = 0L).verifyAll(directory()) }
            .isInstanceOf(AccountUnreachableException::class.java)
        assertThat(accountCalls.get()).isEqualTo(1)
        assertThat(sleeps).isEmpty()
    }

    @Test
    fun `the deadline is read from the environment`() {
        unavailableCalls = Int.MAX_VALUE
        assertThatThrownBy {
            AccountPreflight
                .fromEnv(
                    mapOf(AccountPreflight.ENV_RETRY_SECONDS to "0"),
                ).verifyAll(directory())
        }.isInstanceOf(AccountUnreachableException::class.java)
        assertThatThrownBy { AccountPreflight.fromEnv(mapOf(AccountPreflight.ENV_RETRY_SECONDS to "soon")) }
            .hasMessageContaining(AccountPreflight.ENV_RETRY_SECONDS)
    }

    private fun preflight(deadlineMs: Long) =
        AccountPreflight(
            deadlineMs = deadlineMs,
            clock = clock,
            sleep = { ms ->
                sleeps += ms
                clock.advanceTo(clock.now() + ms)
            },
            log = { logs += it },
        )

    private fun directory(): AccountDirectory =
        AccountDirectory.open(
            listOf(
                AccountConfig(
                    name = "exness_s0",
                    type = "mt5",
                    settings =
                        mapOf(
                            "type" to "mt5",
                            "extends" to "exness",
                            "gateway_url" to "http://127.0.0.1:${server.address.port}",
                            "magic" to "1001",
                            "retry_attempts" to "0",
                            "http_timeout_ms" to "2000",
                            "expected_account_login" to "435898347",
                        ),
                ),
            ),
            ConnectorRegistry(listOf(Mt5Connector())),
            ConnectorContext(stateRoot = null, env = emptyMap(), clock = SystemClock()),
        )
}
