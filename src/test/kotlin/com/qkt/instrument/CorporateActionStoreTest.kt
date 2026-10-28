package com.qkt.instrument

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Dividends and splits are stored per symbol, read oldest first, and anything ambiguous is refused by name. */
class CorporateActionStoreTest {
    private val dividend =
        CorporateAction.Dividend(
            LocalDate.parse("2026-11-09"),
            BigDecimal("0.26"),
            "USD",
            LocalDate.parse("2026-11-13"),
        )
    private val split = CorporateAction.Split(LocalDate.parse("2024-06-10"), BigDecimal(10))

    @Test
    fun `actions merge into one file per symbol and read back oldest first`(
        @TempDir root: Path,
    ) {
        val store = CorporateActionStore(root)
        assertThat(store.actions("EXNESS:AAPL")).isEmpty()
        assertThat(store.merge("EXNESS:AAPL", listOf(dividend, split))).isEqualTo(2)
        assertThat(store.actions("EXNESS:AAPL")).containsExactly(split, dividend)
        assertThat(Files.readString(store.path("EXNESS:AAPL")))
            .isEqualTo(
                "ex_date,kind,value,currency,pay_date\n2024-06-10,split,10,,\n2026-11-09,dividend,0.26,USD,2026-11-13\n",
            )
        // The same kind on the same date replaces: a corrected amount, not a second dividend.
        store.merge("EXNESS:AAPL", listOf(dividend.copy(amount = BigDecimal("0.27"))))
        assertThat(
            store
                .actions("EXNESS:AAPL")
                .filterIsInstance<CorporateAction.Dividend>()
                .single()
                .amount,
        ).isEqualByComparingTo("0.27")
    }

    @Test
    fun `a duplicate, a bad kind, a missing currency or a non-positive value is refused naming the line`(
        @TempDir root: Path,
    ) {
        val store = CorporateActionStore(root)
        assertThatThrownBy { store.merge("X:Y", listOf(dividend, dividend.copy(amount = BigDecimal.ONE))) }
            .hasMessageContaining("two dividend actions on 2026-11-09")

        fun file(body: String) {
            Files.createDirectories(store.path("X:Y").parent)
            Files.writeString(store.path("X:Y"), "ex_date,kind,value,currency,pay_date\n$body")
        }
        file("2026-01-02,bonus,1,,\n")
        assertThatThrownBy { store.actions("X:Y") }.hasMessageContaining("line 2").hasMessageContaining("'bonus'")
        file("2026-01-02,dividend,0.5,,\n")
        assertThatThrownBy { store.actions("X:Y") }.hasMessageContaining("needs its currency")
        file("2026-01-02,split,0,,\n")
        assertThatThrownBy { store.actions("X:Y") }.hasMessageContaining("ratio must be positive")
        file("2026-01-02,dividend,0.5,USD,2026-01-01\n")
        assertThatThrownBy { store.actions("X:Y") }.hasMessageContaining("pay date 2026-01-01 is before it")
    }
}
