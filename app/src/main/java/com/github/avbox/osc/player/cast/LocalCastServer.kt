package com.github.avbox.osc.player.cast

import com.github.avbox.osc.util.AppLog
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 极简本地 HTTP 文件服务：把下载好的文件暴露成 http://<手机局域网IP>:<端口>/<文件名>，
 * 供 DLNA 电视主动拉流。
 *
 * 为什么需要它：在线投屏是把 URL 交给电视、电视自己去拉；本地文件是 file:// 路径，
 * 电视认不了，必须由手机在局域网里起一个 HTTP 服务。
 *
 * 只服务「当前投屏的那一个文件」（路径白名单），不暴露目录，避免电视侧探测到别的文件。
 * 手写 ServerSocket 而不引 NanoHTTPD：这里只需要 GET/HEAD + Range 三种情况，
 * 一百多行足够，少一个依赖就少一处与构建配置打架的机会。
 *
 * Range 是硬要求：电视拖进度条会发 `Range: bytes=x-`，不回 206 + Content-Range
 * 就拖不动，只能从头播到尾。
 */
object LocalCastServer {

    private const val TAG = "LocalCastServer"
    private const val CONN_BACKLOG = 8
    private const val THREADS = 4
    private const val BUF = 64 * 1024

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var pool: ExecutorService? = null

    @Volatile
    private var currentFile: File? = null

    /** 已处理的请求数，只用来决定要不要写第一条请求日志 */
    @Volatile
    private var served = 0

    var port: Int = 0
        private set

    /** 起服务并把文件暴露成 URL；失败返回 null */
    fun start(file: File): String? {
        // 已经在服务同一个文件就直接复用：重复打开面板不能换端口，
        // 否则电视手里那个旧 URL 会瞬间失效
        val alive = server
        if (alive != null && !alive.isClosed && currentFile?.absolutePath == file.absolutePath) {
            val host = localIpv4()
            if (host != null) return buildUrl(host, port, file.name)
        }
        stop()
        if (!file.isFile || file.length() <= 0L) return null
        val host = localIpv4()
        if (host == null) {
            AppLog.e(TAG + " no local ipv4, 投屏需要连 Wi-Fi")
            return null
        }
        val sock = try {
            ServerSocket(0, CONN_BACKLOG, InetAddress.getByName("0.0.0.0"))
        } catch (th: Throwable) {
            AppLog.e(TAG + " bind failed: " + th.message)
            return null
        }
        currentFile = file
        server = sock
        port = sock.localPort
        val exec = Executors.newFixedThreadPool(THREADS)
        pool = exec
        Thread(Runnable { acceptLoop(sock, exec) }, "avbox-local-cast").apply {
            isDaemon = true
            start()
        }
        val url = buildUrl(host, port, file.name)
        AppLog.i(TAG + " serve " + file.name + " size=" + file.length() + " -> " + url)
        return url
    }

    fun stop() {
        try {
            server?.close()
        } catch (ignored: Throwable) {
        }
        server = null
        try {
            pool?.shutdownNow()
        } catch (ignored: Throwable) {
        }
        pool = null
        currentFile = null
        port = 0
        served = 0
    }

    // ---------- 内部 ----------

    private fun acceptLoop(sock: ServerSocket, exec: ExecutorService) {
        while (!sock.isClosed) {
            val client = try {
                sock.accept()
            } catch (th: Throwable) {
                break
            }
            try {
                exec.execute { handle(client) }
            } catch (ignored: Throwable) {
                try {
                    client.close()
                } catch (e: Throwable) {
                }
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.use { socket ->
                socket.soTimeout = 15_000
                val input = BufferedInputStream(socket.getInputStream())
                val out = BufferedOutputStream(socket.getOutputStream())
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val path = parts[1].substringBefore('?')
                var rangeHeader: String? = null
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", true)) rangeHeader = line.substring(6).trim()
                }

                if (served == 0) {
                    AppLog.i(TAG + " first-req " + method + " " + path + " range=" + (rangeHeader ?: "-"))
                }
                val file = currentFile
                if (file == null || !file.isFile) {
                    writeSimple(out, "404 Not Found", "no file")
                    return
                }
                // 白名单：只认当前文件的编码名，其余一律 404
                if (path != "/" + encodeName(file.name)) {
                    writeSimple(out, "404 Not Found", "not found")
                    return
                }
                val total = file.length()
                if (total <= 0L) {
                    writeSimple(out, "404 Not Found", "empty")
                    return
                }
                val isHead = method == "HEAD"
                val range = parseRange(rangeHeader, total)
                if (served == 0) {
                    AppLog.i(TAG + " serving total=" + total + " range=" + (if (range == null) "full" else range.joinToString("-")))
                    served = 1
                }
                when {
                    range == null -> {
                        head(out, "HTTP/1.1 200 OK", mimeOf(file), total, null)
                        if (!isHead) copyRange(file, 0L, total - 1L, out)
                    }

                    range.isEmpty() -> {
                        // 越界要给 416 并带 Content-Range: bytes */total
                        val sb = StringBuilder()
                        sb.append("HTTP/1.1 416 Range Not Satisfiable\r\n")
                        sb.append("Content-Range: bytes */").append(total).append("\r\n")
                        sb.append("Content-Length: 0\r\n")
                        sb.append("Connection: close\r\n\r\n")
                        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
                        out.flush()
                    }

                    else -> {
                        val from = range[0]
                        val to = range[1]
                        head(
                            out, "HTTP/1.1 206 Partial Content", mimeOf(file), to - from + 1L,
                            "bytes " + from + "-" + to + "/" + total,
                        )
                        if (!isHead) copyRange(file, from, to, out)
                    }
                }
            }
        } catch (th: Throwable) {
            // 电视中途断开（换集、拖进度、关机）都走这里，属正常现象，不刷日志
        }
    }

    private fun head(
        out: OutputStream,
        status: String,
        mime: String,
        length: Long,
        contentRange: String?,
    ) {
        val sb = StringBuilder()
        sb.append(status).append("\r\n")
        sb.append("Content-Type: ").append(mime).append("\r\n")
        sb.append("Content-Length: ").append(length).append("\r\n")
        if (contentRange != null) sb.append("Content-Range: ").append(contentRange).append("\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    private fun writeSimple(out: OutputStream, status: String, msg: String) {
        val body = msg.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        sb.append(status).append("\r\n")
        sb.append("Content-Type: text/plain\r\n")
        sb.append("Content-Length: ").append(body.size).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    /**
     * 解析 `Range: bytes=a-b`。
     *
     * 返回 null = 没有 Range 或格式不认识（按整文件 200 处理）；
     * 返回空数组 = 越界（416）；返回 [from, to] = 要发送的闭区间。
     */
    private fun parseRange(header: String?, total: Long): LongArray? {
        if (header == null) return null
        if (!header.startsWith("bytes=", true)) return null
        val spec = header.substring(6).trim().substringBefore(',')
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val fromRaw = spec.substring(0, dash).trim()
        val toRaw = spec.substring(dash + 1).trim()
        return try {
            if (fromRaw.isEmpty()) {
                // bytes=-N：最后 N 字节
                if (toRaw.isEmpty()) return null
                val suffix = toRaw.toLong()
                if (suffix <= 0L) return LongArray(0)
                val from = (total - suffix).coerceAtLeast(0L)
                longArrayOf(from, total - 1L)
            } else {
                val from = fromRaw.toLong()
                val to = if (toRaw.isEmpty()) total - 1L else toRaw.toLong().coerceAtMost(total - 1L)
                if (from > to || from >= total) LongArray(0) else longArrayOf(from, to)
            }
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun copyRange(file: File, from: Long, to: Long, out: OutputStream) {
        FileInputStream(file).use { fis ->
            fis.channel.position(from)
            val input = BufferedInputStream(fis, BUF)
            val buf = ByteArray(BUF)
            var remain = to - from + 1L
            while (remain > 0L) {
                val want = if (remain < BUF) remain.toInt() else BUF
                val n = input.read(buf, 0, want)
                if (n <= 0) break
                out.write(buf, 0, n)
                remain -= n.toLong()
            }
            out.flush()
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 4096) return sb.toString().trimEnd('\r')
        }
    }

    private fun buildUrl(host: String, port: Int, name: String): String =
        "http://" + host + ":" + port + "/" + encodeName(name)

    /** 文件名进 URL 必须编码，否则中文名会让部分电视直接 4xx */
    private fun encodeName(name: String): String =
        URLEncoder.encode(name, "UTF-8").replace("+", "%20")

    private fun mimeOf(file: File): String = when (file.extension.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "ts", "m2ts" -> "video/mp2t"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "flv" -> "video/x-flv"
        "wmv" -> "video/x-ms-wmv"
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "flac" -> "audio/flac"
        else -> "application/octet-stream"
    }

    /**
     * 取局域网 IPv4：优先 wlan 接口。
     * 移动数据下的 10.x 地址电视访问不到，调用方拿不到 Wi-Fi 地址时应当提示用户。
     */
    private fun localIpv4(): String? {
        val found = ArrayList<Pair<String, String>>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (nf in interfaces) {
                if (!nf.isUp || nf.isLoopback) continue
                for (addr in nf.inetAddresses) {
                    if (addr !is Inet4Address) continue
                    if (addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                    val ip = addr.hostAddress ?: continue
                    found.add(nf.name to ip)
                }
            }
        } catch (th: Throwable) {
            AppLog.e(TAG + " enum interfaces failed: " + th.message)
            return null
        }
        if (found.isEmpty()) return null
        found.firstOrNull { it.first.startsWith("wlan") }?.let { return it.second }
        found.firstOrNull { it.first.startsWith("ap") }?.let { return it.second }
        return found.first().second
    }
}
