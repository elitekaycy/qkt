package com.qkt.broker.mt5

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Comment-correlation logic for orphan position recovery.
 *
 * MT5 truncates order comments to 16 chars venue-side. Original comments take the shape
 * `dsl-<strategy>-<n>` (or `oco:<parent>/dsl-<strategy>-<n>` for OCO legs). These tests
 * pin down which truncated forms each strategy claims, where it skips, and where it
 * warns about ambiguity.
 */
class MT5StateRecoveryMatchTest {
    @Test
    fun `null comment is not ours`() {
        assertThat(matchOrphan(null, "hedge-straddle")).isEqualTo(OrphanMatch.NotOurs)
    }

    @Test
    fun `empty comment is not ours`() {
        assertThat(matchOrphan("", "hedge-straddle")).isEqualTo(OrphanMatch.NotOurs)
    }

    @Test
    fun `manual non-dsl comment is not ours`() {
        assertThat(matchOrphan("user trade", "hedge-straddle")).isEqualTo(OrphanMatch.NotOurs)
    }

    @Test
    fun `exact full comment with counter matches`() {
        assertThat(matchOrphan("dsl-ema-cross-3", "ema-cross")).isEqualTo(OrphanMatch.Match)
    }

    @Test
    fun `dsl prefix with no tail matches`() {
        assertThat(matchOrphan("dsl-ema-cross", "ema-cross")).isEqualTo(OrphanMatch.Match)
    }

    @Test
    fun `dsl prefix with trailing dash matches`() {
        assertThat(matchOrphan("dsl-ema-cross-", "ema-cross")).isEqualTo(OrphanMatch.Match)
    }

    @Test
    fun `truncated hedge-straddle comment is ambiguous-truncation and seeded`() {
        assertThat(matchOrphan("dsl-hedge-stradd", "hedge-straddle"))
            .isEqualTo(OrphanMatch.AmbiguousTruncation)
    }

    @Test
    fun `ema-cross strategy does not falsely claim ema-cross-v2 position`() {
        val result = matchOrphan("dsl-ema-cross-v2", "ema-cross")
        assertThat(result).isInstanceOf(OrphanMatch.AmbiguousOverlap::class.java)
        assertThat((result as OrphanMatch.AmbiguousOverlap).tail).isEqualTo("-v2")
    }

    @Test
    fun `different strategy is not ours`() {
        assertThat(matchOrphan("dsl-other-strat", "ema-cross")).isEqualTo(OrphanMatch.NotOurs)
    }

    @Test
    fun `oco leg with full inner dsl matches`() {
        assertThat(matchOrphan("oco:dsl-ema-x/dsl-ema-cross-7", "ema-cross"))
            .isEqualTo(OrphanMatch.Match)
    }

    @Test
    fun `oco leg truncated below the slash is ambiguous-truncation`() {
        assertThat(matchOrphan("oco:dsl-hedge-st", "hedge-straddle"))
            .isEqualTo(OrphanMatch.AmbiguousTruncation)
    }

    @Test
    fun `oco leg with truncated slash-less prefix strips oco`() {
        assertThat(matchOrphan("oco:dsl-ema-cros", "ema-cross"))
            .isEqualTo(OrphanMatch.AmbiguousTruncation)
    }

    @Test
    fun `oco leg whose inner is a different strategy is not ours`() {
        assertThat(matchOrphan("oco:dsl-X/dsl-othr-1", "ema-cross"))
            .isEqualTo(OrphanMatch.NotOurs)
    }
}
