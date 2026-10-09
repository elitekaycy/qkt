package com.qkt.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ArgsDefaultsTest {
    @Test
    fun `options fall back to defaults without touching tokens`() {
        val args = Args(arrayOf("backtest", "s.qkt")).withDefaults(mapOf("from" to "2025-09-01"))
        assertThat(args.option("from")).isEqualTo("2025-09-01")
        assertThat(args.tokens).containsExactly("backtest", "s.qkt")
    }

    @Test
    fun `explicit flags beat defaults`() {
        val args = Args(arrayOf("backtest", "--from", "2025-10-01")).withDefaults(mapOf("from" to "2025-09-01"))
        assertThat(args.option("from")).isEqualTo("2025-10-01")
    }

    @Test
    fun `truthy defaults enable boolean flags`() {
        val on = Args(arrayOf("backtest")).withDefaults(mapOf("no-fetch" to "true"))
        assertThat(on.flag("no-fetch")).isTrue()
        val off = Args(arrayOf("backtest")).withDefaults(mapOf("no-fetch" to "false"))
        assertThat(off.flag("no-fetch")).isFalse()
        val absent = Args(arrayOf("backtest"))
        assertThat(absent.flag("no-fetch")).isFalse()
    }

    @Test
    fun `repeatable options concatenate explicit then configured`() {
        val args =
            Args(arrayOf("backtest", "--param", "a=1")).withDefaults(
                emptyMap(),
                mapOf("param" to listOf("b=2")),
            )
        assertThat(args.options("param")).containsExactly("a=1", "b=2")
    }

    @Test
    fun `explicit checks ignore defaults`() {
        val args = Args(arrayOf("backtest")).withDefaults(mapOf("from" to "2025-09-01"))
        assertThat(args.hasExplicitOption("from")).isFalse()
        assertThat(args.hasExplicitFlag("bars")).isFalse()
        val explicit = Args(arrayOf("backtest", "--from", "2025-10-01", "--bars"))
        assertThat(explicit.hasExplicitOption("from")).isTrue()
        assertThat(explicit.hasExplicitFlag("bars")).isTrue()
    }
}
