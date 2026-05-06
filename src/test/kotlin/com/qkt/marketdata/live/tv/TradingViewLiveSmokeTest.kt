package com.qkt.marketdata.live.tv

import com.qkt.common.SystemClock
import com.qkt.marketdata.Tick
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("e2e")
class TradingViewLiveSmokeTest {
    @Test
    fun `subscribes to OANDA EURUSD and receives at least one tick within 30 seconds`() {
        val ws = TradingViewWebSocket.connect()
        val source =
            TradingViewMarketSource(
                webSocket = ws,
                clock = SystemClock(),
            )

        val latch = CountDownLatch(1)
        val captured = mutableListOf<Tick>()
        val feed = source.liveTicks(listOf("OANDA:EURUSD"))

        val reader =
            Thread {
                while (latch.count > 0) {
                    val tick = feed.next() ?: break
                    captured.add(tick)
                    latch.countDown()
                }
            }.apply {
                isDaemon = true
                start()
            }

        val received = latch.await(30, TimeUnit.SECONDS)

        try {
            assertThat(received).isTrue()
            assertThat(captured).isNotEmpty()
            assertThat(captured.first().symbol).isEqualTo("OANDA:EURUSD")
            assertThat(captured.first().price.signum()).isPositive()
        } finally {
            feed.close()
            source.close()
            reader.interrupt()
            reader.join(Duration.ofSeconds(2).toMillis())
        }
    }
}
