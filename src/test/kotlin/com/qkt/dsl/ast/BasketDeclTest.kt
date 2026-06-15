package com.qkt.dsl.ast

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BasketDeclTest {
    @Test
    fun `BasketDecl captures alias weighting constituents timeframe`() {
        val b =
            BasketDecl(
                alias = "antipodean",
                weighting = BasketWeighting.EqualWeight,
                constituents = listOf("aud", "nzd"),
                timeframe = "1h",
            )
        assertThat(b.alias).isEqualTo("antipodean")
        assertThat(b.weighting).isEqualTo(BasketWeighting.EqualWeight)
        assertThat(b.constituents).containsExactly("aud", "nzd")
        assertThat(b.timeframe).isEqualTo("1h")
    }

    @Test
    fun `BasketDecl rejects fewer than two constituents`() {
        assertThatThrownBy {
            BasketDecl(
                alias = "antipodean",
                weighting = BasketWeighting.EqualWeight,
                constituents = listOf("aud"),
                timeframe = "1h",
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `BasketDecl rejects duplicate constituents`() {
        assertThatThrownBy {
            BasketDecl(
                alias = "antipodean",
                weighting = BasketWeighting.EqualWeight,
                constituents = listOf("aud", "aud"),
                timeframe = "1h",
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `BasketDecl rejects blank alias`() {
        assertThatThrownBy {
            BasketDecl(
                alias = "",
                weighting = BasketWeighting.EqualWeight,
                constituents = listOf("aud", "nzd"),
                timeframe = "1h",
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `BasketDecl rejects blank timeframe`() {
        assertThatThrownBy {
            BasketDecl(
                alias = "antipodean",
                weighting = BasketWeighting.EqualWeight,
                constituents = listOf("aud", "nzd"),
                timeframe = "",
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
