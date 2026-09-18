package com.qkt.connectivity

import com.qkt.common.Clock
import java.nio.file.Path

/**
 * What qkt hands a connector when it opens accounts. Connectors take environment, credentials,
 * time and storage from here instead of reaching for globals.
 *
 * [stateRoot] is null for one-shot commands that keep no state. [strategiesTrading] answers
 * "which deployed strategies trade the account named X" — MT5 uses it to attribute positions it
 * finds at startup — and is empty outside the daemon.
 */
class ConnectorContext(
    val stateRoot: Path?,
    val env: Map<String, String>,
    val clock: Clock,
    val secrets: SecretResolver = SecretResolver(env),
    val strategiesTrading: (accountName: String) -> List<String> = { emptyList() },
)
