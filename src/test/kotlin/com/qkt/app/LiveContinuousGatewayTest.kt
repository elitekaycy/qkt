package com.qkt.app

import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A continuous futures stream traded live on a gateway account across a roll, end to end: the strategy
 * enters on the front contract, the roll instant passes, the stream measures the roll from the gateway's
 * closed minute bars and appends it to the history on disk, and the stream's lane carries the position
 * with two legs to the gateway: a reduce-only close of the old contract and an open of the new one.
 */
class LiveContinuousGatewayTest {
    @Test
    fun `a live position is carried across a roll measured from the gateway's own bars`(
        @TempDir dir: Path,
    ) {
        LiveContinuousGatewayFixture(dir).use { f ->
            val codes = f.codes
            val session = f.start()
            try {
                f.quote()
                f.await(150) { f.fake.submits.any { it.symbol == codes[1] } }
                val entry = f.fake.submits.first { it.symbol == codes[1] }
                f.fake.act { fill(entry.clientOrderId, "f-entry", entry.quantity, "70000", System.currentTimeMillis()) }

                f.await(240) { f.fake.submits.count { it.clientOrderId.startsWith("roll:") } >= 1 }
                val close = f.fake.submits.first { it.clientOrderId.startsWith("roll:") && it.symbol == codes[1] }
                f.fake.act { fill(close.clientOrderId, "f-close", close.quantity, "70000", System.currentTimeMillis()) }
                f.await(30) { f.fake.submits.any { it.clientOrderId.startsWith("roll:") && it.symbol == codes[2] } }
                val open = f.fake.submits.first { it.clientOrderId.startsWith("roll:") && it.symbol == codes[2] }

                assertThat(close.symbol to close.side).isEqualTo(codes[1] to "sell")
                assertThat(close.reduceOnly).isTrue()
                assertThat(open.symbol to open.side).isEqualTo(codes[2] to "buy")
                assertThat(open.quantity.toBigDecimal()).isEqualByComparingTo(entry.quantity.toBigDecimal())
                val measured = f.store.read("BINANCE_UM:BTCUSDT")!!.find(f.rollAt, codes[1], codes[2])
                assertThat(measured?.fromPrice).isEqualTo("70000")
                assertThat(measured?.toPrice).isEqualTo("70500")
            } finally {
                session.stop()
            }
        }
    }
}
