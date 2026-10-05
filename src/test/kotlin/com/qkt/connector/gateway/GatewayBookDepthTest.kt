package com.qkt.connector.gateway

import com.qkt.cli.Args
import com.qkt.cli.Config
import com.qkt.cli.ExitCodes
import com.qkt.cli.FetchCommand
import com.qkt.cli.openAccounts
import com.qkt.marketdata.depth.BookDepthStore
import com.qkt.marketdata.depth.BookDepthSymbol
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

/** A gateway's depth reaches `qkt fetch --depth` and the live feed only when the gateway declares it. */
class GatewayBookDepthTest {
    private val code = "BTC_USDC-PERPETUAL"
    private val symbol = "DERIBIT:BTC_USDC_PERPETUAL"
    private val dayStart = 1_790_812_800_000L // 2026-10-01T00:00Z
    private val step = 10_000L
    private val times = (1..5).map { dayStart + it * step }
    private val reads = CopyOnWriteArrayList<String>()

    @Volatile private var capabilities = listOf("bars", "depth", "quotes")
    private val server =
        MockWebServer().apply {
            dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = serve(request)
                }
            start()
        }

    private fun snapshot(t: Long) =
        """{"time":$t,"bids":[["86490.6","1$t"],["86200","0.002"]],"asks":[["86490.7","10"]]}"""

    private fun serve(request: RecordedRequest): MockResponse {
        val url = requireNotNull(request.requestUrl)
        return when (url.encodedPath) {
            "/v1/health" -> FakeWire.ok(FakeWire.health("7", false, 0, capabilities))
            "/v1/instruments" -> FakeWire.ok("[" + perpetual() + "]")
            "/v1/depth" -> {
                reads += url.query.orEmpty()
                val from = url.queryParameter("from")!!.toLong()
                val to = url.queryParameter("to")!!.toLong()
                val inRange = times.filter { it in from..to }
                val next = inRange.getOrNull(2)?.let { ""","next":$it""" }.orEmpty()
                FakeWire.ok("""{"depth":[${inRange.take(2).joinToString(",") { snapshot(it) }}]$next}""")
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
                    "--depth",
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
    fun `qkt fetch --depth stores every page of a gateway's snapshots in the venue-neutral day file`(
        @TempDir tmp: Path,
    ) {
        assertThat(fetch(tmp)).isEqualTo(ExitCodes.SUCCESS)
        assertThat(fetch(tmp)).isEqualTo(ExitCodes.SUCCESS)

        val stored = BookDepthStore(tmp.resolve("data")).snapshots(symbol, dayStart, dayStart + 86_400_000)
        assertThat(stored.map { it.timeMs }).containsExactlyElementsOf(times)
        assertThat(stored.first().bids.map { it.price.toPlainString() to it.amount.toPlainString() })
            .containsExactly("86490.6" to "1${times.first()}", "86200" to "0.002")
        assertThat(Files.exists(tmp.resolve("data/depth/DERIBIT/BTC_USDC_PERPETUAL/2026-10-01.csv.gz"))).isTrue
        assertThat(reads.first()).startsWith("symbol=$code&from=$dayStart&to=")
        assertThat(reads).hasSize(6)
    }

    @Test
    fun `a gateway that does not declare depth is refused naming the account, before any read`(
        @TempDir tmp: Path,
    ) {
        capabilities = listOf("bars", "quotes")
        val source =
            Config
                .load(config(tmp))
                .openAccounts()
                .byName("deribit")!!
                .bookDepth!!

        assertThat(fetch(tmp)).isEqualTo(ExitCodes.USER_ERROR)
        assertThatThrownBy { source.snapshots(symbol, dayStart, dayStart + step) }
            .hasMessageContaining("deribit: its gateway does not declare depth")
            .hasMessageContaining(symbol)
        assertThat(reads).isEmpty()
    }

    @Test
    fun `the live depth streams read the account's gateway once at start and are refused when undeclared`(
        @TempDir tmp: Path,
    ) {
        val streams = BookDepthSymbol.FIELDS.map { BookDepthSymbol.of(it, symbol) }
        val live =
            Config
                .load(
                    config(tmp),
                ).openAccounts()
                .marketDataRoutes()
                .first { it.first.matches(streams[0]) }
                .second

        live.liveTicks(streams).close()
        capabilities = emptyList()
        val undeclared = Config.load(config(tmp)).openAccounts().marketDataRoutes()
        val refused = undeclared.first { (pattern, _) -> pattern.matches(streams[0]) }.second

        assertThat(reads).hasSize(1)
        assertThatThrownBy { refused.liveTicks(streams) }.hasMessageContaining("does not declare depth")
    }
}
