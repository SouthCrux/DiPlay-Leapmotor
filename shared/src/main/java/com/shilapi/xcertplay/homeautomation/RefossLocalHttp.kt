package com.shilapi.xcertplay.homeautomation

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** MSG100's signed local HTTP protocol, restricted to one explicitly configured private IPv4.
 * Uses the supplied Wi-Fi socket, without changing the application's global cleartext policy.
 * No redirects, proxies, cookies, connection reuse or automatic retries.
 */
object RefossLocalHttp {
    private const val LIMIT = 65_536

    fun post(socket: Socket, address: InetAddress, body: ByteArray): ByteArray {
        require(isPrivateIpv4(address) && body.size <= LIMIT)
        socket.soTimeout = 1_000
        socket.connect(InetSocketAddress(address, 80), 1_000)
        val header = "POST /config HTTP/1.1\r\nHost: ${address.hostAddress}\r\n" +
            "Content-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(body); flush() }
        return readResponse(socket.getInputStream())
    }

    fun isPrivateIpv4(address: InetAddress): Boolean {
        if (address !is Inet4Address) return false
        val b = address.address.map { it.toInt() and 255 }
        return b[0] == 10 || b[0] == 172 && b[1] in 16..31 || b[0] == 192 && b[1] == 168
    }

    internal fun readResponse(input: InputStream): ByteArray {
        var headerBytes = 0
        fun line(): String {
            val out = ByteArrayOutputStream()
            while (true) {
                val next = input.read()
                if (next < 0) throw EOFException("Incomplete local HTTP header")
                if (++headerBytes > 8192) throw IOException("Local HTTP header bound")
                if (next == 10) {
                    val bytes = out.toByteArray()
                    if (bytes.isEmpty() || bytes.last() != 13.toByte()) throw IOException("Local HTTP framing")
                    return String(bytes, 0, bytes.size - 1, Charsets.US_ASCII)
                }
                out.write(next)
            }
        }
        if (!Regex("HTTP/1\\.[01] 200(?: .*)?").matches(line())) throw IOException("Local HTTP status")
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = line()
            if (line.isEmpty()) break
            val split = line.indexOf(':')
            if (split <= 0) throw IOException("Local HTTP header")
            val name = line.substring(0, split).lowercase(java.util.Locale.ROOT)
            if (headers.put(name, line.substring(split + 1).trim()) != null) throw IOException("Duplicate local HTTP header")
        }
        if (headers["content-encoding"]?.let { it != "identity" } == true) throw IOException("Local HTTP encoding")
        val length = headers["content-length"]
        val transfer = headers["transfer-encoding"]
        if (length != null && transfer != null) throw IOException("Ambiguous local HTTP framing")
        val out = ByteArrayOutputStream()
        fun copy(count: Int) {
            if (count < 0 || count > LIMIT - out.size()) throw IOException("Local HTTP body bound")
            val buffer = ByteArray(2048)
            var remaining = count
            while (remaining > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (n < 0) throw EOFException("Incomplete local HTTP body")
                if (n == 0) continue
                out.write(buffer, 0, n); remaining -= n
            }
        }
        when {
            transfer != null -> {
                if (!transfer.equals("chunked", true)) throw IOException("Local HTTP transfer encoding")
                while (true) {
                    val size = line().substringBefore(';').trim().toIntOrNull(16)
                        ?: throw IOException("Local HTTP chunk size")
                    if (size == 0) {
                        while (line().isNotEmpty()) { /* bounded trailers */ }
                        break
                    }
                    copy(size)
                    if (line().isNotEmpty()) throw IOException("Local HTTP chunk framing")
                }
            }
            length != null -> copy(length.toIntOrNull() ?: throw IOException("Local HTTP length"))
            else -> {
                while (true) {
                    val next = input.read()
                    if (next < 0) break
                    if (out.size() >= LIMIT) throw IOException("Local HTTP body bound")
                    out.write(next)
                }
            }
        }
        return out.toByteArray()
    }
}
