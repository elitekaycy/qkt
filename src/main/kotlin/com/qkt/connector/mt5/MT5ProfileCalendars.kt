package com.qkt.connector.mt5

import com.qkt.common.SymbolCalendars
import com.qkt.connectivity.AccountConfig

/**
 * The calendars an MT5 profile trades on, built in one place for the loaded profile ([MT5BrokerProfileLoader]) and
 * for a config-only read ([Mt5Connector.tradingHours]), so a backtest and the live account agree on every rule.
 */
internal object MT5ProfileCalendars {
    /**
     * [base]'s calendars (all-FX when the profile extends nothing) with profile [name]'s `calendars:` [rules]
     * layered on: adding `BTC*: crypto` keeps an inherited metals break, and `*` replaces only the fallback.
     */
    fun layer(
        name: String,
        base: SymbolCalendars?,
        rules: List<Pair<String, String>>,
    ): SymbolCalendars =
        (base ?: SymbolCalendars.fxDefault()).overriddenBy(
            rules.map { (pattern, cal) -> SymbolCalendars.Rule(pattern, MT5ProfileSettings.calendarByName(name, cal)) },
        )

    /**
     * Each `type: mt5` entry of [accounts]'s calendars, by name, resolving `extends:` through earlier entries and
     * [defaults] exactly as [MT5BrokerProfileLoader.load] does: an entry extending nothing inherits the built-in
     * profile of its own name when there is one. An entry whose `extends:` names nothing known is left out; loading
     * the profile refuses it.
     */
    fun resolve(
        accounts: List<AccountConfig>,
        defaults: Map<String, MT5BrokerProfile>,
    ): Map<String, SymbolCalendars> {
        val resolved = mutableMapOf<String, SymbolCalendars>()
        val pending = accounts.filter { it.type == "mt5" }.toMutableList()
        var madeProgress = true
        while (pending.isNotEmpty() && madeProgress) {
            madeProgress = false
            val it = pending.iterator()
            while (it.hasNext()) {
                val account = it.next()
                val extendsName = account.setting("extends")
                val base: SymbolCalendars? =
                    when {
                        extendsName != null -> resolved[extendsName] ?: defaults[extendsName]?.symbolCalendars
                        account.name in defaults -> defaults.getValue(account.name).symbolCalendars
                        else -> null
                    }
                if (extendsName != null && base == null) continue
                resolved[account.name] = layer(account.name, base, account.tradingHours)
                it.remove()
                madeProgress = true
            }
        }
        return resolved
    }
}
