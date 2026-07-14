package com.qkt.cli

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ArgsTest {
    @Test
    fun `extracts subcommand and positional args`() {
        val a = Args(arrayOf("backtest", "foo.qkt"))
        assertThat(a.subcommand).isEqualTo("backtest")
        assertThat(a.positional(0)).isEqualTo("foo.qkt")
        assertThat(a.tokens).containsExactly("backtest", "foo.qkt")
    }

    @Test
    fun `extracts boolean flags`() {
        val a = Args(arrayOf("backtest", "foo.qkt", "--json"))
        assertThat(a.flag("json")).isTrue
        assertThat(a.flag("debug")).isFalse
    }

    @Test
    fun `extracts options with values`() {
        val a = Args(arrayOf("backtest", "foo.qkt", "--from", "2024-01-01", "--to", "2024-06-01"))
        assertThat(a.option("from")).isEqualTo("2024-01-01")
        assertThat(a.option("to")).isEqualTo("2024-06-01")
        assertThat(a.option("missing")).isNull()
    }

    @Test
    fun `requireOption throws on missing`() {
        val a = Args(arrayOf("backtest", "foo.qkt"))
        assertThatThrownBy { a.requireOption("from") }
            .isInstanceOf(ArgError::class.java)
            .hasMessageContaining("--from")
    }

    @Test
    fun `options collects every occurrence of a repeated flag in order`() {
        val a = Args(arrayOf("sweep", "s.qkt", "--param", "fast=5", "--param", "slow=10,20"))
        assertThat(a.options("param")).containsExactly("fast=5", "slow=10,20")
    }

    @Test
    fun `options is empty when the flag is absent`() {
        assertThat(Args(arrayOf("backtest", "s.qkt")).options("param")).isEmpty()
    }

    @Test
    fun `positional does not read an option value as a positional`() {
        val a = Args(arrayOf("positions", "--json", "--config", "./qkt.config.yaml"))
        assertThat(a.positional(0)).isNull()
    }

    @Test
    fun `positional stops at the first flag`() {
        val a = Args(arrayOf("buy", "0.01", "EXNESS:XAUUSD", "--sl", "3993", "trailing"))
        assertThat(a.positional(0)).isEqualTo("0.01")
        assertThat(a.positional(1)).isEqualTo("EXNESS:XAUUSD")
        assertThat(a.positional(2)).isNull()
    }
}
