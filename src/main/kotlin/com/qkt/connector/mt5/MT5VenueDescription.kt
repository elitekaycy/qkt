package com.qkt.connector.mt5

import com.qkt.broker.CloseReason
import com.qkt.broker.VenueAccountId
import com.qkt.broker.VenueDescribed
import com.qkt.broker.VenueIdentity
import com.qkt.broker.VenueInstrument

/**
 * How an MT5 account describes itself: connector `mt5`, the venue named by its trade server's first
 * word (`Exness-MT5Trial9` is `exness`), label the profile name uppercased, account id
 * `mt5:<login>@<server>` from the last `/account` read [client] made. A fill's acknowledged ticket is its
 * position ticket, so MT5 names its own positions.
 */
internal class MT5VenueDescription(
    private val profile: MT5BrokerProfile,
    private val client: MT5Client,
) : VenueDescribed {
    private val symbols = MT5Symbol(profile.symbolPolicy)

    override fun venueIdentity(): VenueIdentity {
        val account = client.lastAccount
        val login = account?.login?.takeIf { it != 0L } ?: profile.expectedAccountLogin
        val server = account?.server?.takeIf { it.isNotEmpty() } ?: profile.expectedAccountServer
        return identity(profile.name, login?.toString(), server, account?.tradeMode)
    }

    override fun fillPositionId(brokerOrderId: String?): String? = brokerOrderId?.takeIf { it.isNotBlank() }

    override fun instrument(qktSymbol: String): VenueInstrument? {
        val prefix = "${profile.name.uppercase()}:"
        if (!qktSymbol.startsWith(prefix)) return null
        return VenueInstrument(symbols.toBroker(qktSymbol.removePrefix(prefix)), kind = "cfd")
    }

    companion object {
        /**
         * The identity of MT5 account [login] on [server] configured as [name]; no account id until both are
         * known. [tradeMode] is the account's `ACCOUNT_TRADE_MODE` (0 demo, 1 contest, 2 real), when read.
         */
        fun identity(
            name: String,
            login: String?,
            server: String?,
            tradeMode: Int? = null,
        ): VenueIdentity =
            VenueIdentity(
                connector = "mt5",
                adapter = server?.substringBefore('-')?.lowercase()?.takeIf { it.isNotBlank() } ?: "mt5",
                label = name.uppercase(),
                accountId = if (login != null && !server.isNullOrBlank()) VenueAccountId.mt5(login, server) else null,
                tradeMode = tradeMode?.let { MT5TradeMode.fromWire(it) }?.name?.lowercase(),
            )

        /**
         * Why an MT5 closing deal closed, from its `ENUM_DEAL_REASON`: 4 SL, 5 TP, 6 stop-out, 0-2 a person
         * at a terminal; for 3 (an expert: qkt) what qkt's order id [label] says, else the strategy's signal.
         */
        fun closeReason(
            reason: Int?,
            label: String?,
        ): CloseReason? =
            when (reason) {
                REASON_SL -> CloseReason.STOP
                REASON_TP -> CloseReason.TAKE_PROFIT
                REASON_STOP_OUT -> CloseReason.STOP_OUT
                REASON_CLIENT, REASON_MOBILE, REASON_WEB -> CloseReason.MANUAL
                REASON_EXPERT, null -> CloseReason.fromOrderId(label) ?: CloseReason.SIGNAL
                else -> null
            }

        private const val REASON_CLIENT = 0
        private const val REASON_MOBILE = 1
        private const val REASON_WEB = 2
        private const val REASON_EXPERT = 3
        private const val REASON_SL = 4
        private const val REASON_TP = 5
        private const val REASON_STOP_OUT = 6
    }
}
