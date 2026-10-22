package com.qkt.connector.gateway

import com.qkt.broker.VenueAccountId
import com.qkt.observe.insights.InsightsJournal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * No venue envelope names an account by its credentials: every `accountId` is the opaque
 * [VenueAccountId], and the insights journal qkt keeps on disk holds no login or server.
 */
internal class ConnectorEnvelopeIdentityTest : ConnectorEnvelopeHarness() {
    @Test
    fun `every venue envelope names its account by the opaque id and the journal on disk holds no login or server`(
        @TempDir dir: Path,
    ) {
        val mt5Envelopes = emitted(mt5Broker(), deployed = setOf("hedge_straddle"))
        val vgpEnvelopes = emitted(vgpBroker(), deployed = setOf("a"))

        val ids =
            (mt5Envelopes + vgpEnvelopes).filter { it.type in TYPES }.map { it.type to it.payload["accountId"] }
        assertThat(ids.map { it.first }.toSet()).containsAll(TYPES)
        for ((type, id) in ids) assertThat(id as String).describedAs(type).matches("(mt5|vgp):[0-9a-f]{16}")
        assertThat(mt5Envelopes.filter { it.type in TYPES }.map { it.payload["accountId"] }.toSet())
            .containsExactly(VenueAccountId.mt5(MT5_LOGIN, MT5_SERVER))
        assertThat(vgpEnvelopes.filter { it.type in TYPES }.map { it.payload["accountId"] }.toSet())
            .containsExactly(VenueAccountId.vgp("fake", "7"))
        assertThat(vgpEnvelopes.single { it.type == "state.account" }.payload["login"]).isEqualTo("•••7")

        // What the insights sink keeps on disk until the collector acknowledges it.
        InsightsJournal(dir, "qkt-live").append((mt5Envelopes + vgpEnvelopes).map { it.toJson("qkt-live") })
        val files = Files.walk(dir).use { paths -> paths.filter(Files::isRegularFile).toList() }
        assertThat(files).isNotEmpty
        for (file in files) {
            val text = String(Files.readAllBytes(file), Charsets.UTF_8)
            assertThat(text).describedAs(file.toString()).doesNotContain(MT5_LOGIN).doesNotContain(MT5_SERVER)
        }
    }
}
