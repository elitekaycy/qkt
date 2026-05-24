package com.qkt.dsl.compile

import com.qkt.dsl.ast.EntryQty
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ExprCompilerEntryQtyTest {
    @Test
    fun `EntryQty outside STACK_AT SIZING fails compile with a pointed message`() {
        assertThatThrownBy { ExprCompiler().compile(EntryQty) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("ENTRY_QTY")
            .hasMessageContaining("STACK_AT SIZING")
    }
}
