package com.qkt.cli.observe

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PortPrinterTest {
    @Test
    fun `announce emits both INFO and QKT_PORT lines`() {
        val buf = ByteArrayOutputStream()
        val out = PrintStream(buf)
        PortPrinter.announce(host = "127.0.0.1", port = 47815, portFile = null, out = out)
        val printed = buf.toString(Charsets.UTF_8)
        assertThat(printed).contains("[INFO] observability: http://127.0.0.1:47815")
        assertThat(printed).contains("QKT_PORT=47815")
    }

    @Test
    fun `announce writes port-file atomically when path supplied`() {
        val tmp = Files.createTempFile("qkt-port", ".txt")
        try {
            val buf = ByteArrayOutputStream()
            val out = PrintStream(buf)
            PortPrinter.announce(host = "127.0.0.1", port = 33321, portFile = tmp, out = out)
            assertThat(Files.readString(tmp).trim()).isEqualTo("33321")
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}
