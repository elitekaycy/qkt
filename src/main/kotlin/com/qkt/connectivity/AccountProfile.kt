package com.qkt.connectivity

/**
 * What the venue reported about an account when [TradingAccount.verify] connected.
 *
 * [accountId] is a string because venues disagree: MT5 logins are numbers, Rithmic and exchange
 * account ids are not. [description] is the connector's one-line operator summary, printed at
 * daemon startup and in the daemon-started notification, e.g.
 * `prop_s01: login=26645824 server=FivePercentOnline-Real mode=real currency=USD leverage=100`.
 */
data class AccountProfile(
    val accountName: String,
    val accountId: String,
    val server: String,
    val type: AccountType,
    val currency: String?,
    val leverage: Int?,
    val description: String,
)

/** Whether an account trades real money. */
enum class AccountType {
    /** Real money. */
    LIVE,

    /** Practice money. */
    DEMO,

    /** A trading-competition account. */
    CONTEST,

    /** The venue did not say. Treat as live wherever money is at stake. */
    UNKNOWN,
}
