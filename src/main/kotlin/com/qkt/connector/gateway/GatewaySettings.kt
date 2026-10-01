package com.qkt.connector.gateway

import com.qkt.common.SymbolCalendars
import com.qkt.common.TradingCalendar
import com.qkt.connectivity.AccountConfig
import com.qkt.connectivity.ConnectorContext

/**
 * One `type: gateway` broker entry: the gateway at [url] with token [apiKey], and the identity it must
 * report before anything trades ([adapter], [accountLogin], [tradeMode] `demo` or `real`).
 *
 * ```yaml
 * deribit_main:
 *   type: gateway
 *   gateway_url: https://venued.internal:8443
 *   api_key: env:DERIBIT_GATEWAY_KEY
 *   expected_adapter: deribit
 *   expected_account_login: "4421"
 *   expected_trade_mode: demo
 * ```
 */
internal data class GatewaySettings(
    val url: String,
    val apiKey: String,
    val adapter: String,
    val accountLogin: String,
    val tradeMode: String,
    val httpTimeoutMs: Long,
    val retryAttempts: Int,
) {
    companion object {
        /** Every setting a gateway entry may carry. */
        val KEYS =
            setOf(
                "gateway_url",
                "api_key",
                "expected_adapter",
                "expected_account_login",
                "expected_trade_mode",
                "http_timeout_ms",
                "retry_attempts",
            )

        /** The settings of [account]; every identity check is required, so a gateway is never trusted unchecked. */
        fun of(
            account: AccountConfig,
            context: ConnectorContext,
        ): GatewaySettings {
            fun required(key: String): String =
                account.setting(key)?.takeIf { it.isNotBlank() } ?: error("brokers.${account.name}.$key is required")
            val apiKey =
                context.secrets
                    .resolve(account, "api_key")
                    ?.reveal()
                    ?.takeIf { it.isNotBlank() }
            val mode = required("expected_trade_mode")
            require(
                mode == "demo" || mode == "real",
            ) { "brokers.${account.name}.expected_trade_mode must be demo or real: $mode" }
            return GatewaySettings(
                url = required("gateway_url").trimEnd('/'),
                apiKey = apiKey ?: error("brokers.${account.name}.api_key is required"),
                adapter = required("expected_adapter"),
                accountLogin = required("expected_account_login"),
                tradeMode = mode,
                httpTimeoutMs = account.setting("http_timeout_ms")?.toLong() ?: DEFAULT_TIMEOUT_MS,
                retryAttempts = account.setting("retry_attempts")?.toInt() ?: DEFAULT_ATTEMPTS,
            )
        }

        /** The entry's `calendars` rules, by calendar name; with none, the venue trades around the clock. */
        fun calendars(account: AccountConfig): SymbolCalendars {
            val rules =
                account.tradingHours.map { (pattern, name) ->
                    val calendar =
                        TradingCalendar.named(name)
                            ?: error("brokers.${account.name}.calendars: unknown calendar '$name'")
                    SymbolCalendars.Rule(pattern, calendar)
                }
            return SymbolCalendars(emptyList(), TradingCalendar.crypto()).overriddenBy(rules)
        }

        private const val DEFAULT_TIMEOUT_MS = 5_000L
        private const val DEFAULT_ATTEMPTS = 3
    }
}
