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

    // ───────────── #154 — sibling-aware disambiguation ─────────────

    @Test
    fun `truncated comment with sibling sharing the same prefix is a conflict`() {
        // Both `hedge_straddle_a` and `hedge_straddle_b` truncate to `dsl-hedge_straddl`
        // (16 chars). Recovery for either must refuse to seed.
        val result = matchOrphan("dsl-hedge_straddl", "hedge_straddle_a", siblings = listOf("hedge_straddle_b"))
        assertThat(result).isInstanceOf(OrphanMatch.ConflictWithSibling::class.java)
        assertThat((result as OrphanMatch.ConflictWithSibling).siblings).containsExactly("hedge_straddle_b")
    }

    @Test
    fun `truncated comment with sibling that has different prefix is not a conflict`() {
        // `hedge_straddle` and `pairs_xau_xag` don't share a truncation — siblings
        // present, but no conflict.
        val result = matchOrphan("dsl-hedge-stradd", "hedge-straddle", siblings = listOf("pairs_xau_xag"))
        assertThat(result).isEqualTo(OrphanMatch.AmbiguousTruncation)
    }

    @Test
    fun `unambiguous match is never downgraded by a sibling`() {
        // `dsl-ema-cross-7` is unambiguous for `ema-cross` even if a sibling
        // `ema-cross-v2` exists. Match wins.
        assertThat(matchOrphan("dsl-ema-cross-7", "ema-cross", siblings = listOf("ema-cross-v2")))
            .isEqualTo(OrphanMatch.Match)
    }

    @Test
    fun `overlap with sibling that fully owns the comment downgrades to not-ours`() {
        // `dsl-ema-cross-v2` would be AmbiguousOverlap for `ema-cross` alone, but with
        // sibling `ema-cross-v2` as an unambiguous Match, the position belongs to the sibling.
        assertThat(matchOrphan("dsl-ema-cross-v2", "ema-cross", siblings = listOf("ema-cross-v2")))
            .isEqualTo(OrphanMatch.NotOurs)
    }

    @Test
    fun `overlap with sibling that does not own the comment stays as overlap`() {
        // Sibling exists but doesn't claim this comment — keep the existing WARN behaviour.
        val result = matchOrphan("dsl-ema-cross-v2", "ema-cross", siblings = listOf("other-strategy"))
        assertThat(result).isInstanceOf(OrphanMatch.AmbiguousOverlap::class.java)
    }

    @Test
    fun `self in siblings list is ignored`() {
        // Defensive: the caller may pass the full deployed-list including self.
        val result = matchOrphan("dsl-hedge-stradd", "hedge-straddle", siblings = listOf("hedge-straddle"))
        assertThat(result).isEqualTo(OrphanMatch.AmbiguousTruncation)
    }

    @Test
    fun `empty siblings preserves pre-existing behaviour`() {
        // Regression guard: the old behaviour is the default when no siblings are supplied.
        assertThat(matchOrphan("dsl-hedge-stradd", "hedge-straddle"))
            .isEqualTo(OrphanMatch.AmbiguousTruncation)
    }
}
