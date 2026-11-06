package com.qkt.connector.gateway

import com.qkt.common.SymbolCalendars
import com.qkt.connectivity.AccountConfig
import com.qkt.connectivity.Connector
import com.qkt.connectivity.ConnectorContext
import com.qkt.connectivity.ConnectorSpec
import com.qkt.connectivity.ProductType
import com.qkt.connectivity.TradingAccount

/**
 * Any venue served by a VGP v1 gateway (`docs/superpowers/specs/2026-10-01-vgp-v1-wire.md`): futures,
 * perpetuals, spot and options. Each `type: gateway` entry is one account on one gateway; several
 * strategies may share it through one [GatewaySession] (fills are attributed by client order id, and a
 * settlement closes each strategy's own holding). See [GatewaySettings] for the entry.
 */
class GatewayConnector : Connector {
    override val spec: ConnectorSpec =
        ConnectorSpec(
            type = "gateway",
            displayName = "VGP gateway",
            productTypes =
                setOf(
                    ProductType.FUTURE,
                    ProductType.PERPETUAL,
                    ProductType.SPOT,
                    ProductType.OPTION,
                    ProductType.EQUITY,
                    ProductType.EVENT,
                ),
            settings = GatewaySettings.KEYS,
        )

    /** Each entry's `calendars` rules over the around-the-clock default, as each opened account trades on. */
    override fun tradingHours(accounts: List<AccountConfig>): Map<String, SymbolCalendars> =
        accounts.associate { it.name to GatewaySettings.calendars(it) }

    override fun open(
        accounts: List<AccountConfig>,
        context: ConnectorContext,
    ): List<TradingAccount> =
        accounts.map { account ->
            val settings = GatewaySettings.of(account, context)
            GatewayTradingAccount(
                account,
                settings,
                context.clock,
                { context.strategiesTrading(account.name).toSet() },
                GatewayChainRecording(context.instruments, settings.chainSnapshotMs),
                context.instruments,
                context.deploysUnderway,
                context.stateRoot,
            )
        }
}
