package com.shilapi.xcertplay.homeautomation

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import org.junit.Assert.*
import org.junit.Test

class RefossLocalHttpTest {
    private fun response(raw: String) = RefossLocalHttp.readResponse(ByteArrayInputStream(raw.toByteArray()))

    @Test fun handlesFixedChunkedAndConnectionCloseResponses() {
        assertEquals("{}", String(response("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}")))
        assertEquals("{}", String(response("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n1\r\n{\r\n1\r\n}\r\n0\r\n\r\n")))
        assertEquals("{}", String(response("HTTP/1.0 200 OK\r\n\r\n{}")))
    }

    @Test fun malformedTruncatedOversizeAndRedirectRepliesAreRejected() {
        for (raw in listOf(
            "HTTP/1.1 302 Found\r\nLocation: https://example.org\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\n{}",
            "HTTP/1.1 200 OK\r\nContent-Length: 65537\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: -1\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Length: 2\r\n\r\n{}",
            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nTransfer-Encoding: chunked\r\n\r\n{}",
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n10001\r\n",
            "HTTP/1.1 200 OK\r\nX: " + "x".repeat(8192) + "\r\n\r\n",
            "HTTP/1.1 200 OK\r\n\r\n" + "x".repeat(65537),
        )) {
            try { response(raw); fail("Reply must be rejected") } catch (_: IOException) { }
        }
    }

    @Test fun usesOnlySuppliedSocketAndPrivateTargetWithoutHttpUrlConnection() {
        val sent = ByteArrayOutputStream()
        var connects = 0
        val socket = object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) {
                assertEquals("192.168.1.2", (endpoint as InetSocketAddress).address.hostAddress)
                assertEquals(80, endpoint.port)
                assertEquals(1000, timeout)
                connects++
            }
            override fun setSoTimeout(timeout: Int) { assertEquals(1000, timeout) }
            override fun getOutputStream() = sent
            override fun getInputStream() = ByteArrayInputStream("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}".toByteArray())
        }
        assertEquals("{}", String(RefossLocalHttp.post(socket, InetAddress.getByName("192.168.1.2"), "{}".toByteArray())))
        assertEquals(1, connects)
        assertTrue(sent.toString().startsWith("POST /config HTTP/1.1\r\nHost: 192.168.1.2\r\n"))
        assertTrue(sent.toString().contains("Content-Length: 2\r\nConnection: close\r\n\r\n{}"))
        for (host in listOf("127.0.0.1", "169.254.1.2", "8.8.8.8", "::1")) {
            try { RefossLocalHttp.post(socket, InetAddress.getByName(host), byteArrayOf()); fail("Public or loopback target") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(1, connects)
    }
}
