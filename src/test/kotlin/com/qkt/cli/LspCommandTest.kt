package com.qkt.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LspCommandTest {
    @Test
    fun `lsp answers an initialize request on stdout and exits when stdin closes`() {
        val request = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"capabilities":{}}}"""
        val framed = "Content-Length: ${request.toByteArray().size}\r\n\r\n$request"
        val savedOut = System.out

        val run = captureCliRun(stdin = framed) { LspCommand().run() }

        assertThat(run.code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(run.out).startsWith("Content-Length: ").contains("\"id\":1").contains("\"hoverProvider\":true")
        assertThat(System.out).isSameAs(savedOut)
    }
}
