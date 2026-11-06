package com.daviddokakis.karoointerval

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * Lets the rider paste the Stride token on a phone instead of typing it on the Karoo: while
 * running, a tiny web server on the Karoo's Wi-Fi address serves a form at a random path
 * (e.g. http://192.168.1.23:8080/k7q2). The phone must be on the same Wi-Fi. Submitting the
 * form hands the token (and optional FTP) to [onReceived] on the main thread.
 *
 * The random path is the only secret, so the server runs only while the page is open and
 * stops after one successful submit.
 */
class PhoneEntry(private val onReceived: (token: String, ftp: String) -> Unit) {

    private var server: ServerSocket? = null
    private val main = Handler(Looper.getMainLooper())

    /** Starts the server; returns the address to open on the phone, or null without Wi-Fi. */
    fun start(): String? {
        stop()
        val ip = wifiAddress() ?: return null
        val path = randomPath()
        val socket = (8080..8090).firstNotNullOfOrNull { port ->
            runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) } }.getOrNull()
        } ?: return null
        server = socket
        Thread({ serve(socket, path) }, "phone-entry").apply { isDaemon = true }.start()
        return "http://$ip:${socket.localPort}/$path"
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun serve(socket: ServerSocket, path: String) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            runCatching { handle(client, path) }.onFailure { Timber.w(it, "Phone entry request failed") }
            runCatching { client.close() }
        }
    }

    private fun handle(client: Socket, path: String) {
        client.soTimeout = 15_000
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input) ?: return
        var length = 0
        while (true) {
            val header = readLine(input) ?: break
            if (header.isEmpty()) break
            if (header.startsWith("content-length:", ignoreCase = true)) {
                length = header.substringAfter(':').trim().toIntOrNull()?.coerceIn(0, 16_384) ?: 0
            }
        }
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0).orEmpty()
        val target = parts.getOrNull(1).orEmpty().substringBefore('?').trim('/')
        val out = client.getOutputStream()
        fun reply(code: String, html: String) {
            val body = page(html).toByteArray(Charsets.UTF_8)
            out.write(
                ("HTTP/1.1 $code\r\nContent-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(),
            )
            out.write(body)
            out.flush()
        }
        if (target != path) return reply("404 Not Found", "<p>Nie znaleziono. Sprawdź adres pokazany na Karoo.</p>")
        if (method == "GET") return reply("200 OK", FORM)
        if (method != "POST") return reply("405 Method Not Allowed", "<p>Niedozwolone.</p>")

        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(bytes, read, length - read)
            if (n < 0) break
            read += n
        }
        val fields = String(bytes, 0, read, Charsets.UTF_8).split('&').mapNotNull { pair ->
            val key = pair.substringBefore('=', "")
            if (key.isEmpty()) null else key to URLDecoder.decode(pair.substringAfter('='), "UTF-8").trim()
        }.toMap()
        val token = fields["token"].orEmpty()
        if (token.isBlank()) return reply("200 OK", "<p class=err>Token jest pusty.</p>$FORM")
        val ftp = fields["ftp"].orEmpty().filter { it.isDigit() }
        reply("200 OK", "<h1>Wysłano ✓</h1><p>Token jest już na Karoo. Możesz zamknąć tę stronę.</p>")
        main.post { onReceived(token, ftp) }
        main.post { stop() }
    }

    /** One header line (without CRLF), or null at end of stream. */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length < 8_192) sb.append(c.toChar())
        }
    }

    companion object {
        private const val FORM = """
<h1>Interval Screen Stride</h1>
<p>Wklej token API ze Stride (stride.is → Settings → API).</p>
<form method="post">
<label>Token API<br><input name="token" autocomplete="off" autocapitalize="off" spellcheck="false" required></label>
<label>FTP w watach (opcjonalnie, puste = auto)<br><input name="ftp" inputmode="numeric" pattern="[0-9]*"></label>
<button type="submit">Wyślij na Karoo</button>
</form>"""

        private fun page(body: String) = """<!doctype html><html lang="pl"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Interval Screen Stride</title>
<style>body{font-family:system-ui,sans-serif;margin:24px;max-width:480px;line-height:1.4}
label{display:block;margin:16px 0;font-weight:600}input{width:100%;box-sizing:border-box;font-size:17px;padding:10px;margin-top:6px}
button{font-size:17px;padding:12px 20px;font-weight:600}.err{color:#b00020}</style></head><body>$body</body></html>"""

        private fun randomPath(): String {
            val chars = "abcdefghjkmnpqrstuvwxyz23456789"
            val random = SecureRandom()
            return (1..5).map { chars[random.nextInt(chars.length)] }.joinToString("")
        }

        /** The Karoo's IPv4 address on Wi-Fi (wlan*), or null when not on Wi-Fi. */
        private fun wifiAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && it.name.startsWith("wlan") }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address }?.hostAddress
        }.getOrNull()
    }
}

/**
 * "Enter from phone" button with a status line under it, for the Account and intro pages.
 */
fun Context.phoneEntryBlock(entry: PhoneEntry): LinearLayout {
    val status = text("", 15f).apply { visibility = android.view.View.GONE }
    val block = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    block.addView(
        button(getString(R.string.phone_entry), primary = false) {
            val url = entry.start()
            status.visibility = android.view.View.VISIBLE
            status.text = if (url != null) getString(R.string.phone_entry_open, url) else getString(R.string.phone_entry_no_wifi)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(12) }
        },
    )
    block.addView(status.apply { setPadding(0, dp(8), 0, 0) })
    block.tag = status
    return block
}

/** Shows "Received" in a block made by [phoneEntryBlock]. */
fun Context.phoneEntryReceived(block: LinearLayout) {
    (block.tag as? TextView)?.text = getString(R.string.phone_entry_received)
}
