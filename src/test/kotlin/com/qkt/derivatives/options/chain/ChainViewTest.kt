package com.qkt.derivatives.options.chain

import com.qkt.derivatives.options.chain.OptionChainFixture.Companion.ms
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ChainViewTest {
    @Test
    fun `the latest snapshot at or before an instant, looking back across midnight and never ahead`(
        @TempDir dir: Path,
    ) {
        val f = OptionChainFixture(dir)
        f.store(
            Triple("2026-09-30T23:00:00Z", "90", 0L),
            Triple("2026-10-01T01:00:00Z", "100", 0L),
            Triple("2026-10-01T02:00:00Z", "110", 0L),
        )
        val view = ChainView(f.registry)

        assertThat(view.latest(f.root.root, ms("2026-10-01T00:30:00Z"))?.atMs).isEqualTo(ms("2026-09-30T23:00:00Z"))
        assertThat(view.latest(f.root.root, ms("2026-10-01T01:59:59Z"))?.atMs).isEqualTo(ms("2026-10-01T01:00:00Z"))
        assertThat(view.latest(f.root.root, ms("2026-10-01T02:00:00Z"))?.atMs).isEqualTo(ms("2026-10-01T02:00:00Z"))
        assertThat(view.latest(f.root.root, ms("2026-09-30T22:00:00Z"))).isNull()
        assertThat(view.latest("DERIBIT:ETH_USDC", ms("2026-10-01T02:00:00Z"))).isNull()
    }
}
