package com.qkt.connectivity

/**
 * What [AccountPreflight.verifyReachable] found at boot: the accounts that answered and matched their config
 * ([verified]), and those whose venue could not be reached ([unreachable], with the failure). An account that
 * answered with the wrong identity is in neither: the preflight throws for it.
 */
data class BootVerification(
    val verified: List<Pair<TradingAccount, AccountProfile>>,
    val unreachable: List<Pair<TradingAccount, Exception>>,
)
