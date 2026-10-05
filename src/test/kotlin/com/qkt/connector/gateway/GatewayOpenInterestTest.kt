package com.qkt.connector.gateway

import com.qkt.cli.Args
import com.qkt.cli.Config
import com.qkt.cli.ExitCodes
import com.qkt.cli.FetchCommand
import com.qkt.cli.openAccounts
import com.qkt.marketdata.openinterest.OpenInterestStore
import com.qkt.marketdata.openinterest.OpenInterestSymbol
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A gateway's open interest reaches `qkt fetch --open-interest` and the live feed only when the gateway declares it. */
class GatewayOpenInterestTest {
    private val code = "BTC_USDC-PERPETUAL"
    private val symbol = "DERIBIT:BTC_USDC_PERPETUAL"
    private val dayStart = 1_790_812_800_000L // 2026-10-01T00:00Z
    private val minute = 60_000L
    private val figures = (1..5).map { dayStart + it * minute to "147$it.6341" }
    private val reads = CopyOnWriteArrayList<String>()

    @Volatile private var capabilities = listOf("bars", "open_interest", "quotes")
    private val server =
        MockWebServer().apply {
            dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = serve(request)
                }
            start()
        }

    private fun serve(request: RecordedRequest): MockResponse {
        val url = requireNotNull(request.requestUrl)
        return when (url.encodedPath) {
            "/v1/health" -> FakeWire.ok(FakeWire.health("7", false, 0, capabilities))
            "/v1/instruments" -> FakeWire.ok("[" + perpetual() + "]")
            "/v1/open-interest" -> {
                reads += url.query.orEmpty()
                val from = url.queryParameter("from")!!.toLong()
                val to = url.queryParameter("to")!!.toLong()
                val inRange = figures.filter { (t, _) -> t in from..to }
                val page = inRange.take(2).joinToString(",") { (t, v) -> """{"time":$t,"open_interest":"$v"}""" }
                val next =
                    inRange
                        .getOrNull(2)
                        ?.first
                        ?.let { ""","next":$it""" }
                        .orEmpty()
                FakeWire.ok("""{"open_interest":[$page]$next}""")
            }
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun perpetual() =
        """{"code":"$code","kind":"perpetual","currency":"USDC","contract_size":"1","tick_size":"0.1",""" +
            """"volume_step":"0.0001","volume_min":"0.0001"}"""

    @AfterEach
    fun stop() = server.shutdown()

    private fun config(tmp: Path): Path =
        tmp.resolve("qkt.config.yaml").also {
            Files.writeString(
                it,
                "brokers:\n  deribit:\n    type: gateway\n    gateway_url: ${server.url(
                    "/",
                ).toString().trimEnd('/')}\n" +
                    "    api_key: secret\n    expected_adapter: fake\n    expected_account_login: \"7\"\n" +
                    "    expected_trade_mode: demo\n",
            )
        }

    private fun fetch(tmp: Path): Int =
        FetchCommand(
            Args(
                arrayOf(
                    "fetch",
                    symbol,
                    "--open-interest",
                    "--from",
                    "2026-10-01",
                    "--to",
                    "2026-10-01",
                    "--config",
                    config(tmp).toString(),
                    "--data-root",
                    tmp.resolve("data").toString(),
                ),
            ),
        ).run()

    @Test
    fun `qkt fetch --open-interest stores every page of a gateway's figures in the venue-neutral file`(
        @TempDir tmp: Path,
    ) {
        assertThat(fetch(tmp)).isEqualTo(ExitCodes.SUCCESS)
        assertThat(fetch(tmp)).isEqualTo(ExitCodes.SUCCESS)

        val stored = OpenInterestStore(tmp.resolve("data")).read(symbol)!!
        assertThat(stored.map { it.timeMs to it.openInterest.toPlainString() }).containsExactlyElementsOf(figures)
        assertThat(Files.readAllLines(tmp.resolve("data/open_interest/DERIBIT/BTC_USDC_PERPETUAL.csv")).first())
            .isEqualTo("time,open_interest")
        assertThat(reads.first()).startsWith("symbol=$code&from=$dayStart&to=")
        assertThat(reads).hasSize(6)
    }

    @Test
    fun `a gateway that does not declare open_interest is refused naming the account, before any read`(
        @TempDir tmp: Path,
    ) {
        capabilities = listOf("bars", "quotes")
        val accounts = Config.load(config(tmp)).openAccounts()
        val source = accounts.byName("deribit")!!.openInterest!!

        assertThat(fetch(tmp)).isEqualTo(ExitCodes.USER_ERROR)
        assertThatThrownBy { source.figures(symbol, dayStart, dayStart + 10 * minute) }
            .hasMessageContaining("deribit: its gateway does not declare open_interest")
            .hasMessageContaining(symbol)
        assertThat(reads).isEmpty()
    }

    @Test
    fun `the live open-interest stream reads the account's gateway at start and is refused when undeclared`(
        @TempDir tmp: Path,
    ) {
        val routes = Config.load(config(tmp)).openAccounts().marketDataRoutes()
        val stream = OpenInterestSymbol.of(symbol)
        val live = routes.first { (pattern, _) -> pattern.matches(stream) }.second

        live.liveTicks(listOf(stream)).close()
        capabilities = emptyList()
        val undeclared = Config.load(config(tmp)).openAccounts().marketDataRoutes()
        val refused = undeclared.first { (pattern, _) -> pattern.matches(stream) }.second

        assertThat(reads).hasSize(1)
        assertThatThrownBy { refused.liveTicks(listOf(stream)) }.hasMessageContaining("does not declare open_interest")
    }
}
