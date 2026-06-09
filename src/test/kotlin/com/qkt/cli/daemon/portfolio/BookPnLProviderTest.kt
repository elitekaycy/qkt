package com.qkt.cli.daemon.portfolio

import com.qkt.app.SessionPnl
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BookPnLProviderTest {
    private fun pnl(
        realized: String,
        unrealized: String,
    ) = SessionPnl(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal(realized), BigDecimal(unrealized))

    @Test
    fun `sums realized and unrealized across children`() {
        val book = BookPnLProvider(listOf({ pnl("100", "10") }, { pnl("-30", "5") }))
        assertThat(book.realizedTotal()).isEqualByComparingTo(BigDecimal("70"))
        assertThat(book.unrealizedTotal()).isEqualByComparingTo(BigDecimal("15"))
        assertThat(book.totalPnL()).isEqualByComparingTo(BigDecimal("85"))
    }

    @Test
    fun `empty book is zero`() {
        val book = BookPnLProvider(emptyList())
        assertThat(book.totalPnL()).isEqualByComparingTo(BigDecimal.ZERO)
    }
}
