package com.qkt.dsl.parse

import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildPct
import com.qkt.dsl.ast.ChildPriceAst
import com.qkt.dsl.ast.ChildRr
import com.qkt.dsl.ast.Day
import com.qkt.dsl.ast.Fok
import com.qkt.dsl.ast.Gtc
import com.qkt.dsl.ast.Gtd
import com.qkt.dsl.ast.Ioc
import com.qkt.dsl.ast.Limit
import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.OrderTypeAst
import com.qkt.dsl.ast.Stop
import com.qkt.dsl.ast.StopLimit
import com.qkt.dsl.ast.TifAst
import com.qkt.dsl.ast.TrailingBy
import com.qkt.dsl.ast.TrailingPct
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserOrderTypeTest {
    private fun ot(s: String): OrderTypeAst = Parser(Lexer(s).tokenize()).parseOrderType()

    private fun tif(s: String): TifAst = Parser(Lexer(s).tokenize()).parseTif()

    private fun cp(s: String): ChildPriceAst = Parser(Lexer(s).tokenize()).parseChildPrice()

    @Test
    fun `market order`() {
        assertThat(ot("MARKET")).isEqualTo(Market)
    }

    @Test
    fun `limit at price`() {
        val r = ot("LIMIT AT 100") as Limit
        assertThat(r.price).isNotNull
    }

    @Test
    fun `stop at price`() {
        assertThat(ot("STOP AT 99")).isInstanceOf(Stop::class.java)
    }

    @Test
    fun `stop-limit at stop limit at limit`() {
        val r = ot("STOP AT 99 LIMIT AT 98") as StopLimit
        assertThat(r.stopPrice).isNotNull
        assertThat(r.limitPrice).isNotNull
    }

    @Test
    fun `trailing by distance`() {
        assertThat(ot("TRAILING BY 0.5")).isInstanceOf(TrailingBy::class.java)
    }

    @Test
    fun `trailing pct frac`() {
        assertThat(ot("TRAILING PCT 0.01")).isInstanceOf(TrailingPct::class.java)
    }

    @Test
    fun `tif gtc`() {
        assertThat(tif("GTC")).isEqualTo(Gtc)
    }

    @Test
    fun `tif ioc`() {
        assertThat(tif("IOC")).isEqualTo(Ioc)
    }

    @Test
    fun `tif fok`() {
        assertThat(tif("FOK")).isEqualTo(Fok)
    }

    @Test
    fun `tif day`() {
        assertThat(tif("DAY")).isEqualTo(Day)
    }

    @Test
    fun `tif gtd expr`() {
        assertThat(tif("GTD 100")).isInstanceOf(Gtd::class.java)
    }

    @Test
    fun `child at`() {
        assertThat(cp("AT 100")).isInstanceOf(ChildAt::class.java)
    }

    @Test
    fun `child by`() {
        assertThat(cp("BY 0.5")).isInstanceOf(ChildBy::class.java)
    }

    @Test
    fun `child pct`() {
        assertThat(cp("PCT 0.01")).isInstanceOf(ChildPct::class.java)
    }

    @Test
    fun `child rr`() {
        assertThat(cp("RR 3")).isInstanceOf(ChildRr::class.java)
    }
}
