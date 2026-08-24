package com.qkt.cli.daemon

import com.qkt.common.FixedClock
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AutoDeployRetrierTest {
    private val file: Path = Path.of("/tmp/forward_bench.qkt")

    @Test
    fun `a failed auto-deploy is retried on the backoff schedule until it succeeds`() {
        val clock = FixedClock(1_000L)
        var failuresLeft = 2
        val attempts = mutableListOf<Long>()
        val retrier =
            AutoDeployRetrier(
                deploy = { _, _ ->
                    attempts += clock.now()
                    if (failuresLeft-- > 0) error("MT5 time-base mismatch for XAGUSD")
                },
                alreadyDeployed = { false },
                clock = clock,
                backoffMs = listOf(60_000L, 120_000L, 300_000L),
                log = {},
            )
        retrier.schedule("forward_bench", file, "MT5 time-base mismatch for XAGUSD")
        assertThat(retrier.pending()).singleElement().extracting { it.nextAttemptAtMs }.isEqualTo(61_000L)

        // Not due yet: nothing happens.
        assertThat(retrier.retryDue(60_999L)).isEmpty()
        assertThat(attempts).isEmpty()

        // First retry fails -> second backoff step (120s) from the retry instant.
        clock.advanceTo(61_000L)
        assertThat(retrier.retryDue(clock.now())).isEmpty()
        assertThat(retrier.pending().single().attempts).isEqualTo(2)
        assertThat(retrier.pending().single().nextAttemptAtMs).isEqualTo(181_000L)

        // Second retry fails -> third step (300s).
        clock.advanceTo(181_000L)
        assertThat(retrier.retryDue(clock.now())).isEmpty()
        assertThat(retrier.pending().single().nextAttemptAtMs).isEqualTo(481_000L)

        // Third retry succeeds and the entry clears.
        clock.advanceTo(481_000L)
        assertThat(retrier.retryDue(clock.now())).containsExactly("forward_bench")
        assertThat(retrier.pending()).isEmpty()
        assertThat(attempts).containsExactly(61_000L, 181_000L, 481_000L)
    }

    @Test
    fun `the last backoff step repeats instead of growing without bound`() {
        val clock = FixedClock(0L)
        val retrier =
            AutoDeployRetrier(
                deploy = { _, _ -> error("still closed") },
                alreadyDeployed = { false },
                clock = clock,
                backoffMs = listOf(10L, 20L),
                log = {},
            )
        retrier.schedule("p", file, "boot")
        var now = 0L
        repeat(5) {
            now = retrier.pending().single().nextAttemptAtMs
            clock.advanceTo(now)
            retrier.retryDue(now)
        }
        assertThat(retrier.pending().single().attempts).isEqualTo(6)
        assertThat(retrier.pending().single().nextAttemptAtMs - now).isEqualTo(20L)
    }

    @Test
    fun `an operator deploy of the same name cancels the pending retry`() {
        val clock = FixedClock(0L)
        var deployCalls = 0
        val retrier =
            AutoDeployRetrier(
                deploy = { _, _ -> deployCalls++ },
                alreadyDeployed = { name -> name == "p" },
                clock = clock,
                backoffMs = listOf(10L),
                log = {},
            )
        retrier.schedule("p", file, "boot")
        clock.advanceTo(10L)

        assertThat(retrier.retryDue(10L)).isEmpty()
        assertThat(retrier.pending()).isEmpty()
        assertThat(deployCalls).isZero()
    }

    @Test
    fun `pending drops an operator-deployed name immediately, not at the next backoff tick`() {
        val clock = FixedClock(0L)
        var deployed = false
        val retrier =
            AutoDeployRetrier(
                deploy = { _, _ -> error("boot failure") },
                alreadyDeployed = { deployed },
                clock = clock,
                backoffMs = listOf(900_000L),
                log = {},
            )
        retrier.schedule("p", file, "boot failure")
        assertThat(retrier.pending()).hasSize(1)

        // Operator deploys the name by hand: /health must clear on the next read (#1060),
        // 15 minutes before the retry would have noticed.
        deployed = true
        assertThat(retrier.pending()).isEmpty()
        assertThat(retrier.retryDue(900_000L)).isEmpty()
    }
}
