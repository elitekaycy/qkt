package com.qkt.cli.daemon.logging

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC

class MdcTest {
    @AfterEach
    fun clear() {
        MDC.clear()
    }

    @Test
    fun `withMdc restores prior value after the block`() {
        MDC.put("strategy", "outer")
        withMdc("strategy", "inner") {
            assertThat(MDC.get("strategy")).isEqualTo("inner")
        }
        assertThat(MDC.get("strategy")).isEqualTo("outer")
    }

    @Test
    fun `withMdc removes the key when no prior value existed`() {
        assertThat(MDC.get("strategy")).isNull()
        withMdc("strategy", "transient") {
            assertThat(MDC.get("strategy")).isEqualTo("transient")
        }
        assertThat(MDC.get("strategy")).isNull()
    }

    @Test
    fun `withMdc restores prior value even when the block throws`() {
        MDC.put("strategy", "outer")
        org.assertj.core.api.Assertions
            .assertThatThrownBy {
                withMdc("strategy", "inner") {
                    throw RuntimeException("boom")
                }
            }.isInstanceOf(RuntimeException::class.java)
        assertThat(MDC.get("strategy")).isEqualTo("outer")
    }

    @Test
    fun `withMdc returns the block result`() {
        val result =
            withMdc("strategy", "x") {
                42
            }
        assertThat(result).isEqualTo(42)
    }
}
