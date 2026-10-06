package com.qkt.app

import com.qkt.derivatives.options.chain.OptionRootSymbol
import com.qkt.dsl.compile.HubKey

/**
 * The broker label [key]'s stream trades through: an option root feed trades on its venue's account
 * (`OPTIONS:DERIBIT.BTC_USDC` → `DERIBIT`), every other stream on its own prefix.
 */
internal fun tradingBroker(key: HubKey): String {
    val root = OptionRootSymbol.parse(key.qktSymbol).getOrNull() ?: return key.broker
    return root.root.substringBefore(':')
}
