package com.qkt.connector.gateway

import com.qkt.common.SymbolCalendars
import com.qkt.common.TradingCalendar
import com.qkt.connectivity.AccountConfig
import com.qkt.connectivity.ConnectorContext

/**
 * One `type: gateway` broker entry: the gateway at [url] with token [apiKey], and the identity it must
 * report before anything trades ([adapter], [accountLogin], [tradeMode] `demo` or `real`), and how
 * often the live chain of a `chains: book` option root is snapshotted ([chainSnapshotMs]).
 *
 * ```yaml
 * deribit_main:
 *   type: gateway
 *   gateway_url: https://venue-gateway.internal:8443
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
    val chainSnapshotMs: Long,
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
                "chain_snapshot_seconds",
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
                chainSnapshotMs = chainSnapshotSeconds(account) * MS_PER_S,
            )
        }

        /** The entry's live chain cadence: at least the 5 s a live chain stream polls at, so none is skipped. */
        private fun chainSnapshotSeconds(account: AccountConfig): Long {
            val seconds = account.setting("chain_snapshot_seconds")?.toLongOrNull() ?: DEFAULT_CHAIN_S
            require(seconds >= MIN_CHAIN_S) {
                "brokers.${account.name}.chain_snapshot_seconds must be at least $MIN_CHAIN_S: $seconds"
            }
            return seconds
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
        private const val DEFAULT_CHAIN_S = 300L
        private const val MIN_CHAIN_S = 5L
        private const val MS_PER_S = 1_000L
    }
}
