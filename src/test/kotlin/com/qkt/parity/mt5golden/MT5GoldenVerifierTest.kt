package com.qkt.parity.mt5golden

import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MT5GoldenVerifierTest {
    private val syntheticFixture =
        Path.of("src/test/resources/parity/mt5-golden-synthetic-schema-smoke.json")

    @Test
    fun `synthetic fixture exercises the schema and replay verifier`() {
        val fixture = MT5GoldenFixture.load(syntheticFixture, requireAuthentic = false)

        val result = MT5GoldenVerifier.verify(fixture, requireAuthentic = false)

        assertThat(result.submissionsCompared).isEqualTo(2)
        assertThat(result.dealGroupsCompared).isEqualTo(2)
        assertThat(result.capturedVenueCost).isEqualByComparingTo("-0.70")
    }

    @Test
    fun `synthetic fixture cannot satisfy the authentic MT5 evidence gate`() {
        assertThatThrownBy { MT5GoldenFixture.load(syntheticFixture) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("authentic demo-session capture")
    }

    @Test
    fun `verifier rejects venue prices outside the declared tolerance`() {
        val fixture = MT5GoldenFixture.load(syntheticFixture, requireAuthentic = false)
        val drifted =
            fixture.copy(
                venueDeals =
                    fixture.venueDeals.map { deal ->
                        if (deal.clientOrderId == "market-buy-1") deal.copy(price = "1.10030") else deal
                    },
            )

        assertThatThrownBy { MT5GoldenVerifier.verify(drifted, requireAuthentic = false) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("market-buy-1: simulated price")
    }
}
