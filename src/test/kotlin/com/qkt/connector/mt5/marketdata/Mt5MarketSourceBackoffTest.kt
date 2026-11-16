package com.qkt.connector.mt5.marketdata

import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.SymbolCalendars
import com.qkt.common.TimeRange
import com.qkt.common.TradingCalendar
import com.qkt.connector.mt5.MT5BrokerProfile
import com.qkt.connector.mt5.MT5ServerTimeZone
import com.qkt.connector.mt5.SymbolPolicy
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** The wait between history re-reads: a real backoff, one an interrupt cuts short, and the source's own defaults. */
class Mt5MarketSourceBackoffTest {
    private val range = TimeRange(Instant.parse("2026-07-15T08:00:00Z"), Instant.parse("2026-07-15T08:05:00Z"))
    private val window = TimeWindow.parse("5m")
    private val tickMs = Instant.parse("2026-07-15T11:05:00Z").toEpochMilli()
    private val bar =
        """[{"open":1,"high":1,"low":1,"close":1,"spread":0,"tick_volume":1,"time":"2026-07-15T11:00:00Z"}]"""
    private val reads =
        java.util.concurrent.atomic
            .AtomicInteger()
    private val server = MockWebServer()

    @AfterEach
    fun stop() = server.shutdown()

    private fun serve(firstBars: String) {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.contains("symbol_info_tick") ->
                            MockResponse().setBody("""{"bid":1,"ask":1,"last":1,"flags":6,"time_msc":$tickMs}""")
                        path.contains("fetch_data") ->
                            MockResponse().setBody(if (reads.getAndIncrement() == 0) firstBars else bar)
                        else -> MockResponse().setBody("""{"point":"0.00001"}""")
                    }
                }
            }
        server.start()
    }

    private fun profile(): MT5BrokerProfile {
        val calendars = SymbolCalendars(emptyList(), TradingCalendar.crypto())
        return MT5BrokerProfile(
            name = "test",
            gatewayUrl = server.url("/").toString().trimEnd('/'),
            symbolPolicy = SymbolPolicy(),
            serverTimeZone = MT5ServerTimeZone.NEW_YORK_CLOSE,
            magic = 10,
            symbolCalendars = calendars,
            retryAttempts = 1,
        )
    }

    private fun source(backoffMs: Long) =
        Mt5MarketSource(
            profile = profile(),
            clock = FixedClock(range.to.toEpochMilli()),
            symbolCalendars = profile().symbolCalendars,
            retryBackoffMs = backoffMs,
        )

    @Test
    fun `an empty window is re-read after a real backoff`() {
        serve("[]")

        val bars = source(backoffMs = 1L).bars("TEST:EURUSD", window, range).toList()

        assertThat(bars).hasSize(1)
        assertThat(reads.get()).isEqualTo(2)
    }

    @Test
    fun `an interrupt during the backoff stops the read and keeps the interrupt`() {
        serve("[]")
        val failure = AtomicReference<Throwable>()
        val interruptedAfter = AtomicReference<Boolean>()
        val reader =
            Thread {
                try {
                    source(backoffMs = 600_000L).bars("TEST:EURUSD", window, range).toList()
                } catch (e: InterruptedException) {
                    failure.set(e)
                    interruptedAfter.set(Thread.currentThread().isInterrupted)
                }
            }.apply { start() }

        while (reader.stackTrace.none { it.methodName == "sleepBeforeRetry" }) Thread.onSpinWait()
        reader.interrupt()
        reader.join(10_000)

        assertThat(failure.get()).isInstanceOf(InterruptedException::class.java)
        assertThat(interruptedAfter.get()).isTrue()
    }

    @Test
    fun `a source built with defaults serves its own symbols and closes cleanly`() {
        serve(bar)
        Mt5MarketSource(profile()).use { source ->
            assertThat(source.supports("TEST:EURUSD")).isTrue()
            assertThat(source.name).isEqualTo("MT5:test")
        }
    }
}
