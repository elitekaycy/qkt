package com.qkt.trade.venue

import com.qkt.cli.Config
import com.qkt.cli.accountConfigs
import com.qkt.common.SystemClock
import com.qkt.connectivity.AccountDirectory
import com.qkt.connectivity.ConnectorContext
import com.qkt.connectivity.ConnectorRegistry
import com.qkt.connectivity.DirectVenue

/**
 * One configured broker opened for a one-shot command: [venue] answers it, [broker] is its config name.
 * Only that account is opened, so a broker that is down elsewhere in the config cannot stop the command.
 */
class OpenedVenue(
    val broker: String,
    val venue: DirectVenue,
    private val accounts: AccountDirectory,
) : AutoCloseable {
    /** The calendar [qktSymbol] trades on at this account, resolved as a deployed strategy's is. */
    fun calendarFor(qktSymbol: String): com.qkt.common.TradingCalendar =
        com.qkt.cli.CalendarResolver
            .live(accounts, null)
            .calendarFor(qktSymbol)

    override fun close() = accounts.close()
}

/**
 * Resolves the broker a `qkt bot` one-shot command acts on, whatever its `type`: the prefix of a
 * `BROKER:SYMBOL`, or a `--broker` name, matched case-insensitively against the `brokers:` names. Fails
 * closed on anything it cannot name exactly: a bot order never guesses its venue.
 */
object BotVenues {
    fun forSymbol(
        cfg: Config,
        qktSymbol: String,
    ): OpenedVenue {
        val brokerKey = qktSymbol.substringBefore(':', "")
        require(brokerKey.isNotBlank() && qktSymbol.contains(':')) {
            "symbol must be BROKER:SYMBOL (e.g. EXNESS:XAUUSD), got '$qktSymbol'"
        }
        return forBroker(cfg, brokerKey)
    }

    /** [brokerName] null is allowed only when exactly one broker is configured. */
    fun forBroker(
        cfg: Config,
        brokerName: String?,
    ): OpenedVenue {
        val configs = cfg.accountConfigs()
        val names = configs.joinToString { it.name }.ifEmpty { "none" }
        val account =
            if (brokerName != null) {
                configs.firstOrNull { it.name.equals(brokerName, ignoreCase = true) }
                    ?: error("no broker '$brokerName' in config; configured: $names")
            } else {
                configs.singleOrNull()
                    ?: error("config has ${configs.size} brokers; pass --broker <name> (configured: $names)")
            }
        val accounts =
            AccountDirectory.open(
                listOf(account),
                ConnectorRegistry.discover(),
                ConnectorContext(stateRoot = null, env = System.getenv(), clock = SystemClock()),
            )
        return try {
            OpenedVenue(account.name, accounts.accounts.single().directVenue(), accounts)
        } catch (e: Exception) {
            accounts.close()
            throw e
        }
    }
}

/**
 * A one-shot order's id, also its venue `client_order_id` where the venue takes one: the `--as` name,
 * the submit time and four random hex digits, at most 64 characters, so two commands in one
 * millisecond never share an id.
 */
fun botOrderId(
    asName: String,
    nowMs: Long,
): String =
    "bot-${asName.take(40)}-$nowMs-%04x".format(
        java.util.concurrent.ThreadLocalRandom
            .current()
            .nextInt(0x10000),
    )

/**
 * Whether an order or position labelled [label] (an MT5 comment, a gateway client order id) was placed by
 * `qkt bot` as [asName]. MT5 keeps 29 characters of a comment, so the id prefix is compared to that length.
 */
fun ownedBy(
    asName: String,
    label: String?,
): Boolean = label != null && label.startsWith("bot-${asName.take(40)}-".take(29))
