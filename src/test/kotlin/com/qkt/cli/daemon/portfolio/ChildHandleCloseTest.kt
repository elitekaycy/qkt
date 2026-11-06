package com.qkt.cli.daemon.portfolio

import com.qkt.app.SessionPnl
import com.qkt.cli.daemon.StateDir
import com.qkt.cli.daemon.StrategyRegistryPortfolioFixture
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A child wrapper tears its session down; a book's PnL sums its children and holds no per-symbol mark. */
class ChildHandleCloseTest : StrategyRegistryPortfolioFixture() {
    @Test
    fun `closing a child stops its session`(
        @TempDir tmp: Path,
    ) {
        val events = mutableListOf<String>()
        val handle = childHandle(StateDir.resolve(tmp.toString()), events, "book", "a", 1)

        ChildHandle(parent = "book", alias = "a", hold = false, handle = handle).close()

        assertThat(events).contains("stop:book/a:v1")
        assertThat(handle.isRunning()).isFalse
    }

    @Test
    fun `a book's pnl sums its children and has no per-symbol unrealized`() {
        val pnl = SessionPnl(BigDecimal("100"), BigDecimal("90"), BigDecimal("4"), BigDecimal("6"))
        val book = BookPnLProvider(listOf({ pnl }, { pnl }))

        assertThat(book.realizedTotal()).isEqualByComparingTo("8")
        assertThat(book.unrealizedTotal()).isEqualByComparingTo("12")
        assertThat(book.unrealizedFor("EXNESS:XAUUSD")).isEqualByComparingTo("0")
    }
}
