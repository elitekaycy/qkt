package com.qkt.parity

import com.qkt.parity.BookRiskRaceScenario.runBook
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A book-level exposure cap must hold across children that enter inside ONE book-risk sample.
 *
 * Found live on 2026-09-10 against the local Exness demo gateway: a three-child book under a
 * max_gross_exposure cap of 1,998.53 opened all three 0.01-lot entries (about 3,239 of notional)
 * with no refusal. The children submitted within 197 ms -- two of them 1 ms apart -- and every
 * check ran before any sibling's fill had been folded into book state.
 *
 * The mechanism: [com.qkt.risk.rules.BookExposureLimit] checks each order against
 * `BookRiskController.state()`, and the controller's gross exposure changes only when
 * PortfolioSupervisor samples filled positions, once per `riskIntervalMs` (1,000 ms by default).
 * Nothing reserves an approved-but-unsampled order, so every entry inside one sample window sees the
 * same stale exposure. The backtest samples on its single-threaded bus as fills land, which is why
 * it refused the same book correctly -- a backtest/live divergence in a risk control.
 *
 * Two children each order 2 units at 100 (notional 200) against a cap of 0.3 x 1,000 = 300: one
 * entry fits, two do not. A 60 s risk interval guarantees both entries land inside one sample.
 */
class PortfolioBookRiskRaceTest {
    @Test
    fun `book gross cap holds when both children enter at the same moment`(
        @TempDir tmp: Path,
    ) {
        val result = runBook(tmp, concurrent = true)
        assertThat(result.fills).describedAs(result.describe()).isEqualTo(1)
        assertThat(result.journals).describedAs(result.describe()).contains("book gross exposure")
    }

    @Test
    fun `book gross cap holds for sequential entries between two risk samples`(
        @TempDir tmp: Path,
    ) {
        val result = runBook(tmp, concurrent = false)
        assertThat(result.fills).describedAs(result.describe()).isEqualTo(1)
        assertThat(result.journals).describedAs(result.describe()).contains("book gross exposure")
    }
}
