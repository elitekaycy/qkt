package com.qkt.connector.mt5

import com.qkt.broker.AdjustmentKind
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Every MT5 deal that is not a trade is reported, classified, never dropped: what this Exness demo account books
 * (deposits and withdrawals as balance deals, an "Archived deals" correction) and what a stock CFD position can (a
 * dividend, as MT5's own type or as a balance deal named a dividend; a charge; a type MT5 has not defined).
 */
class MT5AdjustmentsTest {
    private lateinit var server: MockWebServer
    private lateinit var adjustments: MT5Adjustments

    @BeforeEach
    fun setup() {
        server = MockWebServer().apply { start() }
        val url = server.url("/").toString().trimEnd('/')
        val profile =
            MT5BrokerProfile(name = "exness", gatewayUrl = url, symbolPolicy = SymbolPolicy(suffix = "m"), magic = 1)
        adjustments = MT5Adjustments(profile, MT5Client(url, MT5ServerTimeZone.UTC, retryAttempts = 0))
    }

    @AfterEach
    fun teardown() = server.shutdown()

    private fun deal(
        ticket: Long,
        type: Int,
        profit: String,
        symbol: String = "",
        position: Long = 0,
        comment: String = "",
    ) = """{"ticket":$ticket,"order":0,"position_id":$position,"symbol":"$symbol","type":$type,"entry":0,""" +
        """"volume":0,"price":0,"profit":$profit,"commission":0,"swap":0,"fee":0,"magic":0,""" +
        """"comment":"$comment","time_msc":${1_793_000_000_000L + ticket}}"""

    @Test
    fun `non-trade deals are classified with their money, and trades are left to the deal reader`() {
        server.enqueue(
            MockResponse().setBody(
                listOf(
                    deal(1, 0, "0", "AAPLm", 900),
                    deal(2, 2, "1000.0", comment = "D-trial-USD-b747cce13fe340"),
                    deal(3, 5, "0", comment = "Archived deals"),
                    deal(4, 15, "0.27", "AAPLm", 900, "Dividend AAPL"),
                    deal(5, 2, "-0.31", "TSLAm", 901, "adj"),
                    deal(6, 2, "0.12", comment = "Dividend NVDA 0.01 lots"),
                    deal(7, 4, "-1.5", "NVDAm", 902, "charge"),
                    deal(8, 17, "-0.04", "AAPLm", 900),
                    deal(9, 99, "-0.01", "AAPLm", 900),
                ).joinToString(",", "[", "]"),
            ),
        )

        val read = adjustments.adjustments(0L, 1L).associateBy { it.dealTicket }

        assertThat(read.keys).containsExactlyInAnyOrder("2", "3", "4", "5", "6", "7", "8", "9")
        assertThat(read.getValue("2").kind).isEqualTo(AdjustmentKind.BALANCE)
        assertThat(read.getValue("2").accountLevel).isTrue()
        assertThat(read.getValue("3").kind).isEqualTo(AdjustmentKind.CORRECTION)
        with(read.getValue("4")) {
            assertThat(kind).isEqualTo(AdjustmentKind.DIVIDEND)
            assertThat(amount).isEqualByComparingTo("0.27")
            assertThat(symbol).isEqualTo("EXNESS:AAPL")
            assertThat(positionTicket).isEqualTo("900")
            assertThat(accountLevel).isFalse()
        }
        assertThat(read.getValue("5").kind).isEqualTo(AdjustmentKind.DIVIDEND) // a balance deal on a symbol
        assertThat(read.getValue("6").kind).isEqualTo(AdjustmentKind.DIVIDEND) // named in the comment
        assertThat(read.getValue("7").kind).isEqualTo(AdjustmentKind.CHARGE)
        assertThat(read.getValue("8").kind).isEqualTo(AdjustmentKind.TAX)
        assertThat(read.getValue("9").kind).isEqualTo(AdjustmentKind.UNKNOWN)
    }

    @Test
    fun `an unreadable history throws instead of reading as no adjustments`() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertThatThrownBy { adjustments.adjustments(0L, 1L) }.hasMessageContaining("adjustments: gateway read failed")
    }
}
