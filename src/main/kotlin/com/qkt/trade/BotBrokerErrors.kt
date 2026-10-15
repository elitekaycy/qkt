package com.qkt.trade

/**
 * Why `qkt bot` cannot use a broker named on the command line. The bot commands drive MT5 gateways
 * only; a configured broker of another type (a `type: gateway` venue such as paper or Deribit) gets
 * that said plainly, with the way to trade it today, instead of a bare "not found".
 */
internal object BotBrokerErrors {
    /** The message for [name], which is not among the [mt5] brokers loaded from [brokers]. */
    fun missing(
        brokers: Map<String, Map<String, String>>,
        name: String,
        mt5: List<String>,
    ): String {
        val type =
            brokers.entries
                .firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.value
                ?.get("type")
        if (type != null && !type.equals("mt5", ignoreCase = true)) {
            return "broker '$name' is type: $type; qkt bot drives MT5 gateways only. To trade it from the " +
                "command line, deploy a one-rule strategy with `qkt deploy` (see docs/reference/bot-cli.md)"
        }
        return "no mt5 broker '$name' in config; configured: " + mt5.ifEmpty { listOf("none") }.joinToString()
    }
}
