package com.qkt.cli.observe

import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpContext
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpPrincipal
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URI

/** An exchange with no socket behind it: [body] is the response stream, so a test can make writes fail. */
class FakeHttpExchange(
    private val method: String,
    private val body: OutputStream,
    private val uri: String = "/",
) : HttpExchange() {
    private val requestHeaders = Headers()
    private val responseHeaders = Headers()
    private var code = -1
    private var requestBody: InputStream = InputStream.nullInputStream()
    private var responseBody: OutputStream = body
    private val attributes = mutableMapOf<String, Any?>()

    override fun getRequestHeaders(): Headers = requestHeaders

    override fun getResponseHeaders(): Headers = responseHeaders

    override fun getRequestURI(): URI = URI.create(uri)

    override fun getRequestMethod(): String = method

    override fun getHttpContext(): HttpContext? = null

    override fun close() = Unit

    override fun getRequestBody(): InputStream = requestBody

    override fun getResponseBody(): OutputStream = responseBody

    override fun sendResponseHeaders(
        rCode: Int,
        responseLength: Long,
    ) {
        code = rCode
    }

    override fun getRemoteAddress(): InetSocketAddress = InetSocketAddress(0)

    override fun getResponseCode(): Int = code

    override fun getLocalAddress(): InetSocketAddress = InetSocketAddress(0)

    override fun getProtocol(): String = "HTTP/1.1"

    override fun getAttribute(name: String): Any? = attributes[name]

    override fun setAttribute(
        name: String,
        value: Any?,
    ) {
        attributes[name] = value
    }

    override fun setStreams(
        i: InputStream?,
        o: OutputStream?,
    ) {
        if (i != null) requestBody = i
        if (o != null) responseBody = o
    }

    override fun getPrincipal(): HttpPrincipal? = null
}
