package com.qkt.marketdata

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BinaryTickFormatTest {
    @Test
    fun `header round-trips through a buffer`() {
        val header =
            BinaryTickFormat.Header(
                symbol = "XAUUSD",
                tickCount = 1234,
                presenceFlags = (1 shl BinaryTickFormat.COL_PRICE) or (1 shl BinaryTickFormat.COL_BID),
            )
        val buf = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
        BinaryTickFormat.writeHeader(buf, header)
        buf.flip()
        val read = BinaryTickFormat.readHeader(buf)
        assertEquals(header, read)
        assertEquals(BinaryTickFormat.SCALE, read.scale)
    }

    @Test
    fun `column present check reads the flag bits`() {
        val flags = 1 shl BinaryTickFormat.COL_ASK
        assertTrue(BinaryTickFormat.isPresent(flags, BinaryTickFormat.COL_ASK))
        assertFalse(BinaryTickFormat.isPresent(flags, BinaryTickFormat.COL_BID))
    }
}
