package com.qkt.cli.fetch

import com.qkt.cli.Args
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ChainFetchFlagsTest {
    private fun misplaced(vararg argv: String) =
        ChainFetch.misplacedFlag(Args(arrayOf("fetch", "DERIBIT:BTC_USDC", *argv)))

    @Test
    fun `chain flags are refused outside a chain fetch and bar or catalog flags inside one`() {
        assertThat(misplaced("--tf", "1m", "--every", "1h")).contains("--every")
        assertThat(misplaced("--catalog", "--live")).contains("--live")
        assertThat(misplaced("--chains", "--catalog")).contains("--catalog")
        assertThat(misplaced("--chains", "--tf", "1m")).contains("--tf")
        assertThat(misplaced("--chains", "--from", "2026-09-01", "--to", "2026-09-02", "--every", "1h")).isNull()
        assertThat(misplaced("--tf", "1m", "--last", "3d")).isNull()
    }
}
