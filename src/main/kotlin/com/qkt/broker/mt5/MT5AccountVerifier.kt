package com.qkt.broker.mt5

/** Fail-closed gateway and account-identity verification for MT5 cutovers. */
object MT5AccountVerifier {
    /** Fetches `/account`, rejects an unreachable gateway, and verifies configured expectations. */
    fun fetchAndVerify(
        profile: MT5BrokerProfile,
        client: MT5Client =
            MT5Client(
                gatewayUrl = profile.gatewayUrl,
                tzOffsetHours = profile.serverTzOffsetHours,
                httpTimeoutMs = profile.httpTimeoutMs,
                retryAttempts = profile.retryAttempts,
                apiKey = profile.apiKey,
            ),
    ): MT5AccountInfo {
        val account = client.getAccount() ?: error("MT5 profile '${profile.name}' gateway/account is unreachable")
        verify(profile, account)
        return account
    }

    /** Rejects every mismatch between [profile]'s expected account and the venue snapshot. */
    fun verify(
        profile: MT5BrokerProfile,
        account: MT5AccountInfo,
    ) {
        profile.expectedAccountLogin?.let { expected ->
            require(account.login == expected) {
                "MT5 profile '${profile.name}' account login mismatch: expected $expected, got ${account.login}"
            }
        }
        profile.expectedAccountServer?.let { expected ->
            require(account.server.trim().equals(expected.trim(), ignoreCase = true)) {
                "MT5 profile '${profile.name}' account server mismatch: expected '$expected', got '${account.server}'"
            }
        }
        profile.expectedTradeMode?.let { expected ->
            require(account.tradeMode == expected.wireValue) {
                "MT5 profile '${profile.name}' trade mode mismatch: expected ${expected.name.lowercase()}, " +
                    "got ${describeTradeMode(account.tradeMode)}"
            }
        }
        profile.expectedAccountCurrency?.let { expected ->
            require(account.currency.equals(expected, ignoreCase = true)) {
                "MT5 profile '${profile.name}' account currency mismatch: expected $expected, got ${account.currency}"
            }
        }
        profile.expectedLeverage?.let { expected ->
            require(account.leverage == expected) {
                "MT5 profile '${profile.name}' leverage mismatch: expected $expected, got ${account.leverage}"
            }
        }
    }

    /** Operator-readable identity suitable for startup logs and notifications. */
    fun describe(
        profile: MT5BrokerProfile,
        account: MT5AccountInfo,
    ): String =
        "${profile.name}: login=${account.login} server=${account.server.ifBlank { "unknown" }} " +
            "mode=${describeTradeMode(account.tradeMode)} currency=${account.currency} leverage=${account.leverage}"

    private fun describeTradeMode(value: Int): String =
        MT5TradeMode.fromWire(value)?.name?.lowercase() ?: "unknown($value)"
}
