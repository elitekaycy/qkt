package com.qkt.dsl.parse

import com.qkt.dsl.ast.StrategyAst
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A hub stream must be able to name every scope the store writes -- a currency, a venue instrument,
 * an issuer -- and a name the store could never hold must fail at the line that wrote it.
 */
class ParserHubStreamNameTest {
    private fun parse(stream: String): ParseResult<StrategyAst> =
        Dsl.parse(
            """
            STRATEGY s VERSION 1
            SYMBOLS
                gold = EXNESS:XAUUSD EVERY 5m
                cal = $stream EVERY 1d
            RULES
                WHEN cal.value > 0 AND POSITION.gold = 0 THEN BUY gold SIZING 0.01
            """.trimIndent(),
        )

    private fun symbolOf(stream: String): String =
        (parse(stream) as ParseResult.Success)
            .value.streams
            .first { it.broker == "HUB" }
            .symbol
            .substringBefore('/')

    @Test
    fun `currency, venue and issuer scopes all parse`() {
        assertThat(symbolOf("HUB:cal.us_high.USD")).isEqualTo("cal.us_high.USD")
        assertThat(symbolOf("HUB:pos.funding.DERIBIT:BTC-PERPETUAL")).isEqualTo("pos.funding.DERIBIT:BTC-PERPETUAL")
        assertThat(symbolOf("HUB:corp.earnings.ISSUER:BRK-B")).isEqualTo("corp.earnings.ISSUER:BRK-B")
        assertThat(symbolOf("HUB:rates.crypto.btc_funding.BTCUSD")).isEqualTo("rates.crypto.btc_funding.BTCUSD")
        assertThat(symbolOf("HUB:rates.us.dfii10")).isEqualTo("rates.us.dfii10")
    }

    @Test
    fun `a dataset name the store cannot hold is a parse error naming the rule`() {
        val failure = parse("HUB:Cal.us_high.USD") as ParseResult.Failure
        assertThat(failure.errors.joinToString { it.message }).contains("hub dataset").contains("lower-case")
    }

    @Test
    fun `a mixed-case scope is refused rather than read as part of the dataset`() {
        val failure = parse("HUB:cal.us_high.Usd") as ParseResult.Failure
        assertThat(failure.errors.joinToString { it.message }).contains("Usd")
    }

    @Test
    fun `a space inside a scope ends the name, so the declaration does not parse`() {
        assertThat(parse("HUB:pos.funding.DERIBIT: BTC")).isInstanceOf(ParseResult.Failure::class.java)
    }

    @Test
    fun `chain and options names keep their own dotted rule`() {
        val ast =
            Dsl.parse(
                """
                STRATEGY s VERSION 1
                SYMBOLS
                    btc = DERIBIT:BTC_PERPETUAL EVERY 1m
                    iv = CHAIN:DERIBIT.BTC_USDC.atm_iv.30d EVERY 1h
                RULES
                    WHEN iv.value > 0 AND POSITION.btc = 0 THEN BUY btc SIZING 0.01
                """.trimIndent(),
            )
        assertThat(ast).isInstanceOf(ParseResult.Success::class.java)
    }
}
