package com.qkt.connector.mt5

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Deal-history and quote reads through the client: failed reads, odd shapes and sparse rows. */
class MT5HistoryReadsTest {
    private val gateway = Mt5RoutedGateway()
    private val client = MT5Client(gateway.url, MT5ServerTimeZone.UTC, httpTimeoutMs = 2_000L, retryAttempts = 0)
    private val day = 86_400_000L

    @AfterEach
    fun stop() = gateway.close()

    @Test
    fun `a closing deal volume-weights the exits and totals the venue costs`() {
        gateway.on(
            "/history_deals_get",
            "[" +
                listOf(
                    Mt5RoutedGateway.deal(1, 7, entry = 0, volume = "0.3", price = "2400", commission = "-1"),
                    Mt5RoutedGateway.deal(2, 7, entry = 1, volume = "0.1", price = "2410", commission = "-0.5"),
                    Mt5RoutedGateway.deal(3, 7, entry = 1, volume = "0.2", price = "2420"),
                    Mt5RoutedGateway.deal(4, 7, entry = 1, volume = "0", price = "2500"),
                    Mt5RoutedGateway.deal(5, 7, entry = 1, volume = "0.1", price = "0"),
                    Mt5RoutedGateway.deal(6, 8, entry = 1, volume = "5", price = "9999", commission = "-9"),
                ).joinToString(",") + "]",
        )

        val closing = client.getClosingDeal(7L, 10 * day, 11 * day)!!

        assertThat(
            closing.price.toDouble(),
        ).isEqualTo(
            (0.1 * 2410 + 0.2 * 2420) / 0.3,
            org.assertj.core.data.Offset
                .offset(1e-9),
        )
        assertThat(closing.costs).isEqualByComparingTo("1.5")
        assertThat(closing.deals.map { it.ticket }).containsExactly(1L, 2L, 3L, 4L, 5L)
        assertThat(gateway.requests.single().first)
            .isEqualTo("GET /history_deals_get?from_date=1970-01-10T00:00:00Z&to_date=1970-01-13T00:00:00Z&position=7")
    }

    @Test
    fun `there is no closing deal when the position only opened or the read failed`() {
        gateway.on("/history_deals_get", "[${Mt5RoutedGateway.deal(1, 7, entry = 0, volume = "0.3", price = "2400")}]")
        assertThat(client.getClosingDeal(7L, 0L, day)).isNull()

        gateway.fail("/history_deals_get")
        assertThat(client.getClosingDeal(7L, 0L, day)).isNull()
        assertThat(client.getPositionDeals(7L, 0L, day)).isNull()
    }

    @Test
    fun `a deal history that is not a list is unknown, an enveloped one is read`() {
        gateway.on("/history_deals_get", """{"error":"busy"}""")
        assertThat(client.getDeals(0L, day)).isNull()
        assertThat(client.getPositionDeals(7L, 0L, day)).isNull()

        gateway.on(
            "/history_deals_get",
            """{"data":[${Mt5RoutedGateway.deal(1, 7, entry = 1, volume = "1", price = "5")}]}""",
        )
        assertThat(client.getDeals(0L, day)!!.single().ticket).isEqualTo(1L)
        assertThat(client.getPositionDeals(7L, 0L, day)!!.single().ticket).isEqualTo(1L)
    }

    @Test
    fun `a quote takes millisecond time when sent and second time otherwise`() {
        gateway.on(
            "/symbol_info_tick/XAUUSDm",
            """{"bid":"2400.1","ask":"2400.3","time":1700000000,"time_msc":1700000000123}""",
            """{"bid":"2400.1","ask":"2400.3","time":1700000000}""",
            """{"bid":null,"ask":null,"time":null,"time_msc":null}""",
            """{"bid":"x","ask":"x","time":"x","time_msc":"x"}""",
            "{}",
        )

        val withMs = client.getTick("XAUUSDm")!!
        val secondsOnly = client.getTick("XAUUSDm")!!
        val blanks = List(3) { client.getTick("XAUUSDm")!! }

        assertThat(withMs.timeMs).isEqualTo(1_700_000_000_123L)
        assertThat(withMs.time).isEqualTo(1_700_000_000L)
        assertThat(withMs.bid).isEqualByComparingTo("2400.1")
        assertThat(withMs.ask).isEqualByComparingTo("2400.3")
        assertThat(secondsOnly.timeMs).isEqualTo(1_700_000_000_000L)
        assertThat(blanks).allSatisfy { tick ->
            assertThat(tick.bid).isEqualByComparingTo("0")
            assertThat(tick.ask).isEqualByComparingTo("0")
            assertThat(tick.timeMs).isZero()
            assertThat(tick.symbol).isEqualTo("XAUUSDm")
        }
        assertThat(client.getTick("UNLISTED")).isNull()
    }

    @Test
    fun `a tick range reads sparse rows and treats a failed or non-list answer as unknown`() {
        gateway.on(
            "/copy_ticks_range",
            """{"data":[{"bid":"1.1","ask":"1.2","time":1700000000,"time_msc":1700000000500},
            {"bid":"1.3","ask":"1.4","time":1700000001},{"bid":null,"ask":null,"time":null,"time_msc":null},
            {"bid":"x","ask":"x","time":"x","time_msc":"x"},{}]}""",
            """{"error":"no ticks"}""",
        )

        val ticks = client.getTicksRange("EURUSDm", 1_700_000_000_000L, 1_700_000_002_000L)!!

        assertThat(ticks.map { it.timeMs }).containsExactly(1_700_000_000_500L, 1_700_000_001_000L, 0L, 0L, 0L)
        assertThat(ticks.map { it.bid.toPlainString() }).containsExactly("1.1", "1.3", "0", "0", "0")
        assertThat(ticks.map { it.ask.toPlainString() }).containsExactly("1.2", "1.4", "0", "0", "0")
        assertThat(gateway.requests.single().first).isEqualTo(
            "GET /copy_ticks_range?symbol=EURUSDm&from_date=2023-11-14T22%3A13%3A20Z&to_date=2023-11-14T22%3A13%3A22Z",
        )
        assertThat(client.getTicksRange("EURUSDm", 0L, 1L)).isNull()
        gateway.fail("/copy_ticks_range")
        assertThat(client.getTicksRange("EURUSDm", 0L, 1L)).isNull()
    }

    @Test
    fun `a tick range that ends before it starts is refused before any request`() {
        assertThatThrownBy { client.getTicksRange("EURUSDm", 2L, 1L) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("ends before it starts")
        assertThat(gateway.requests).isEmpty()
    }
}
