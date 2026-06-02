package com.qkt.dsl.portfolio

import com.qkt.common.Money
import com.qkt.dsl.ast.AlwaysRun
import com.qkt.dsl.ast.PortfolioAst
import com.qkt.dsl.ast.WhenRun
import java.math.BigDecimal

/**
 * Capital each child receives under a weighted portfolio, keyed by import alias.
 *
 * A weighted portfolio declares a total `CAPITAL` on its header and a `WEIGHT`
 * fraction on every `RUN`; a child's allocation is `CAPITAL * weight`.
 * e.g. `CAPITAL 100000` with `RUN hs WEIGHT 0.6` -> `{"hs": 60000}`.
 *
 * A portfolio with no `CAPITAL`/`WEIGHT` returns an empty map: each child
 * self-sizes off its own basis, unchanged. The AST is already validated
 * (all-or-none, sum <= 1.0) by the time it reaches here, so this only does math.
 */
fun capitalAllocations(ast: PortfolioAst): Map<String, BigDecimal> {
    val capital = ast.capital ?: return emptyMap()
    val out = LinkedHashMap<String, BigDecimal>()
    for (rule in ast.rules) {
        val alias =
            when (rule) {
                is WhenRun -> rule.alias
                is AlwaysRun -> rule.alias
            }
        val weight =
            when (rule) {
                is WhenRun -> rule.weight
                is AlwaysRun -> rule.weight
            } ?: continue
        out[alias] = capital.multiply(weight).setScale(Money.SCALE, Money.ROUNDING)
    }
    return out
}
