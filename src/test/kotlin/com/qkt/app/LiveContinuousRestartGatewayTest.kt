package com.qkt.app

import com.qkt.persistence.FileStatePersistor
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A live session stopped mid-roll, with its closing leg out at the gateway, and started again on the
 * same state: the new session takes the leg back from the gateway without sending it again, and carries
 * the position to the new contract once the gateway fills it, end to end.
 */
class LiveContinuousRestartGatewayTest {
    @Test
    fun `a roll interrupted by a restart is carried through by the next session`(
        @TempDir dir: Path,
    ) {
        LiveContinuousGatewayFixture(dir.resolve("rolls")).use { f ->
            val codes = f.codes
            val state = dir.resolve("state")
            val first = f.start(FileStatePersistor(state))
            var stopped = false
            try {
                f.quote()
                f.await(150) { f.fake.submits.any { it.symbol == codes[1] } }
                val entry = f.fake.submits.first { it.symbol == codes[1] }
                f.fake.act { fill(entry.clientOrderId, "f-entry", entry.quantity, "70000", System.currentTimeMillis()) }
                f.await(240) { f.fake.submits.any { it.clientOrderId.startsWith("roll:") } }
                val close = f.fake.submits.first { it.clientOrderId.startsWith("roll:") }
                first.stop()
                stopped = true

                val second = f.start(FileStatePersistor(state))
                try {
                    f.await(30) { f.fake.quotes.open > 1 }
                    f.fake.act {
                        fill(
                            close.clientOrderId,
                            "f-close",
                            close.quantity,
                            "70000",
                            System.currentTimeMillis(),
                        )
                    }
                    f.await(30) { f.fake.submits.any { it.clientOrderId.startsWith("roll:") && it.symbol == codes[2] } }
                    val open = f.fake.submits.first { it.clientOrderId.startsWith("roll:") && it.symbol == codes[2] }

                    assertThat(close.symbol to close.side).isEqualTo(codes[1] to "sell")
                    assertThat(f.fake.submits.count { it.clientOrderId == close.clientOrderId }).isEqualTo(1)
                    assertThat(open.symbol to open.side).isEqualTo(codes[2] to "buy")
                    assertThat(open.quantity.toBigDecimal()).isEqualByComparingTo(entry.quantity.toBigDecimal())
                    assertThat(
                        f.fake.submits.count { it.symbol == codes[1] && !it.clientOrderId.startsWith("roll:") },
                    ).isEqualTo(1)
                } finally {
                    second.stop()
                }
            } finally {
                if (!stopped) first.stop()
            }
        }
    }
}
