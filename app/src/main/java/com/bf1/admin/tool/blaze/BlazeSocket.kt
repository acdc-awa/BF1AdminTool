package com.bf1.admin.tool.blaze

import java.io.Closeable
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CompletableFuture
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * TCP 分帧：把收到的字节流切成完整 Blaze 包（16 字节 Header + payload）。
 *
 * 与 CardTool.js BlazeSocket 的 #concat 等价，但正确处理"一个 TCP 段含多个包"
 * 与"包被拆到多个段"两种情况（JS 版只处理单包/拆包，多包会丢失）。
 */
class PacketFramer {
    private var buffer = ByteArray(0)

    /** 输入一段字节，返回其中完整包的字节数组；剩余不完整部分缓存在内部。 */
    fun feed(chunk: ByteArray): List<ByteArray> {
        buffer = buffer + chunk
        val out = ArrayList<ByteArray>()
        while (true) {
            if (buffer.size < BlazeCodec.HEADER_SIZE) break
            val payloadLength = BlazeCodec.payloadLength(buffer)
            if (payloadLength < 0 || payloadLength > MAX_PACKET_LENGTH) {
                // 损坏数据：丢弃整个缓冲，避免无限等待
                buffer = ByteArray(0)
                break
            }
            val total = BlazeCodec.HEADER_SIZE + payloadLength
            if (buffer.size < total) break
            val packet = buffer.copyOfRange(0, total)
            buffer = buffer.copyOfRange(total, buffer.size)
            out.add(packet)
        }
        return out
    }

    companion object {
        const val MAX_PACKET_LENGTH = 1 shl 20 // 1 MiB，正常包远小于此
    }
}

/**
 * Blaze TLS Socket。
 *
 * 对应 CardTool.js 的 BlazeSocket：连接 EA Blaze 服务器、每 10 秒心跳、
 * 请求 id 递增、按 id 配对响应、分片包自动拼接。
 *
 * 证书校验关闭（rejectUnauthorized=false 的等价物，EA 旧端点需要）。
 */
class BlazeSocket(
    private val host: String,
    private val port: Int
) : Closeable {

    private val lock = Any()
    private val pending = HashMap<Int, CompletableFuture<BlazePacket>>()
    private var socket: SSLSocket? = null
    private var id = 1
    private var closed = false
    private var keepAliveThread: Thread? = null
    private var readThread: Thread? = null

    val isClosed: Boolean get() = synchronized(lock) { closed }

    /** 建立 TLS 连接并启动读取/心跳线程。 */
    fun connect(timeoutMs: Int = 15_000) {
        val ssl = createTrustAllSocket()
        ssl.connect(InetSocketAddress(host, port), timeoutMs)
        ssl.startHandshake()
        synchronized(lock) {
            socket = ssl
            closed = false
        }
        readThread = Thread({ readLoop() }, "blaze-read").apply { isDaemon = true; start() }
        keepAliveThread = Thread({ keepAliveLoop() }, "blaze-keepalive").apply { isDaemon = true; start() }
    }

    /**
     * 发送请求并返回对应响应的 future。
     * 响应包（含错误包）通过 future 交付，调用方检查 [BlazePacket.error]。
     */
    fun send(request: BlazeRequest): CompletableFuture<BlazePacket> {
        val future = CompletableFuture<BlazePacket>()
        val packetId: Int
        val out: java.io.OutputStream
        synchronized(lock) {
            if (closed) throw BlazeConnectionClosedException("Connection Closed")
            packetId = id
            id = if (id >= 65535) 1 else id + 1
            pending[packetId] = future
            out = requireNotNull(socket).getOutputStream()
        }
        try {
            out.write(BlazeCodec.encode(request.method, request.type, packetId, request.data))
            out.flush()
        } catch (e: Exception) {
            synchronized(lock) { pending.remove(packetId) }
            future.completeExceptionally(e)
        }
        return future
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        try { socket?.close() } catch (_: Exception) {}
        readThread?.interrupt()
        keepAliveThread?.interrupt()
        val err = BlazeConnectionClosedException("Connection Closed")
        synchronized(lock) {
            val it = pending.iterator()
            while (it.hasNext()) {
                val (_, f) = it.next()
                f.completeExceptionally(err)
                it.remove()
            }
        }
    }

    private fun readLoop() {
        val framer = PacketFramer()
        try {
            val input = requireNotNull(socket).getInputStream()
            val chunk = ByteArray(8192)
            while (!isClosed) {
                val n = input.read(chunk)
                if (n < 0) break
                if (n == 0) continue
                for (packetBytes in framer.feed(chunk.copyOfRange(0, n))) {
                    val packet = BlazeCodec.decode(packetBytes)
                    if (packet.method == "KeepAlive") continue
                    synchronized(lock) {
                        pending.remove(packet.id)?.complete(packet)
                    }
                }
            }
        } catch (_: Exception) {
            // 连接被关闭/重置
        } finally {
            val err = BlazeConnectionClosedException("Connection Closed")
            synchronized(lock) {
                val it = pending.iterator()
                while (it.hasNext()) {
                    val (_, f) = it.next()
                    f.completeExceptionally(err)
                    it.remove()
                }
            }
        }
    }

    private fun keepAliveLoop() {
        try {
            val out = requireNotNull(socket).getOutputStream()
            while (!isClosed) {
                Thread.sleep(KEEPALIVE_INTERVAL_MS)
                if (isClosed) break
                out.write(KEEPALIVE_BYTES)
                out.flush()
            }
        } catch (_: Exception) {
            // 连接已关闭
        }
    }

    private fun createTrustAllSocket(): SSLSocket {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val context = SSLContext.getInstance("TLS")
        context.init(null, trustAll, SecureRandom())
        return context.socketFactory.createSocket() as SSLSocket
    }

    companion object {
        private const val KEEPALIVE_INTERVAL_MS = 10_000L
        // 与 CardTool.js keepalive 一致：16 字节 Header，type = 0x80 (SendKeepAlive)
        val KEEPALIVE_BYTES: ByteArray = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x80.toByte(), 0x00, 0x00
        )
    }
}
