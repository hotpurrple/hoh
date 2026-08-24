package com.koreadervoicepager

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * Talks to KOReader's built-in "HTTP inspector" plugin (Tools -> More tools -> HTTP inspector),
 * exactly like the Windows version:
 *   GET /koreader/event/GotoViewRel/1   -> next page
 *   GET /koreader/event/GotoViewRel/-1  -> previous page
 *
 * Same design goal as the original: the socket for the next request should already be open
 * and warm when you say the word, so the request itself is just "write bytes, read bytes" -
 * no TCP handshake, no DNS lookup, in the critical path.
 *
 * OkHttp's ConnectionPool is the direct analog of the Windows version's SocketsHttpHandler
 * with an infinite pooled-connection lifetime: we cap it at exactly one kept-alive connection
 * and ping it every 15s so it never goes stale or gets idle-closed by the reader's network
 * stack before you actually need it.
 *
 * Floored-latency pass: the call itself now runs on a single dedicated, pre-started,
 * max-priority thread (instead of borrowing from a general-purpose pool) so there's no
 * scheduling/dispatch delay when the word is actually said, and every socket this client
 * opens has TCP_NODELAY set explicitly (Nagle's algorithm can add tens of ms of buffering
 * delay to small writes like this one - not something we want on the hot path).
 */
class KOReaderClient(
    private var host: String,
    private var port: Int,
    private val onLog: (String) -> Unit = {}
) {
    // One thread, started immediately (not lazily on first request) and kept alive for the
    // life of the client, so the very first "next"/"back" doesn't pay thread-creation cost.
    private val hotExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "KOReaderClient-hot").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
        }
    }.also { it.submit {} } // force the thread to actually spin up now, not on first real task

    private val hotDispatcher = hotExecutor.asCoroutineDispatcher()
    private var client: OkHttpClient = buildClient()
    private val scope = CoroutineScope(hotDispatcher + Job())
    private var keepAliveJob: Job? = null

    init {
        startKeepAlive()
    }

    /** Plain java.net socket factory that flips TCP_NODELAY on before handing the socket back. */
    private class NoDelaySocketFactory(private val delegate: SocketFactory) : SocketFactory() {
        private fun tune(s: Socket): Socket = s.apply { tcpNoDelay = true }
        override fun createSocket(): Socket = tune(delegate.createSocket())
        override fun createSocket(host: String, port: Int): Socket = tune(delegate.createSocket(host, port))
        override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int): Socket =
            tune(delegate.createSocket(host, port, localHost, localPort))
        override fun createSocket(host: java.net.InetAddress, port: Int): Socket = tune(delegate.createSocket(host, port))
        override fun createSocket(
            address: java.net.InetAddress, port: Int, localAddress: java.net.InetAddress, localPort: Int
        ): Socket = tune(delegate.createSocket(address, port, localAddress, localPort))
    }

    private fun buildClient(): OkHttpClient = OkHttpClient.Builder()
        // Exactly one connection, kept alive indefinitely - removes the handshake from the
        // hot path the same way the Windows version's SocketsHttpHandler tuning did.
        .connectionPool(ConnectionPool(1, 60, TimeUnit.MINUTES))
        // Requests dispatch on our own pre-warmed thread instead of OkHttp's default pool.
        .dispatcher(Dispatcher(hotExecutor).apply { maxRequests = 4; maxRequestsPerHost = 4 })
        .socketFactory(NoDelaySocketFactory(SocketFactory.getDefault()))
        // Tightened as far as is sane on a same-LAN hop: fails fast into the retry below
        // rather than sitting around waiting, since a real command must feel instant anyway.
        .connectTimeout(800, TimeUnit.MILLISECONDS)
        .readTimeout(600, TimeUnit.MILLISECONDS)
        .writeTimeout(400, TimeUnit.MILLISECONDS)
        .callTimeout(900, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun baseUrl() = "http://$host:$port"

    @Synchronized
    fun updateEndpoint(newHost: String, newPort: Int) {
        host = newHost
        port = newPort
        val old = client
        client = buildClient()
        scope.launch { old.connectionPool.evictAll() }
        scope.launch { warmConnection() }
    }

    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (true) {
                warmConnection()
                kotlinx.coroutines.delay(15_000)
            }
        }
    }

    suspend fun warmConnection(): Boolean {
        return try {
            val request = Request.Builder().url(baseUrl() + "/").get().build()
            client.newCall(request).execute().use { resp ->
                onLog("Connection warm.")
                resp.isSuccessful || resp.code in 200..499 // reachable at all is what matters
            }
        } catch (e: Exception) {
            onLog("Warm-up failed: ${e.message}")
            false
        }
    }

    suspend fun nextPage(): Boolean = sendEvent("/koreader/event/GotoViewRel/1")

    suspend fun prevPage(): Boolean = sendEvent("/koreader/event/GotoViewRel/-1")

    private suspend fun sendEvent(path: String): Boolean {
        val started = System.currentTimeMillis()

        if (trySend(path)) {
            onLog("OK $path (${System.currentTimeMillis() - started}ms)")
            return true
        }

        // One immediate retry - covers the pooled connection having just gone stale (reader
        // briefly slept/woke, wifi hiccup). Still fast: no extra timeout wait beforehand.
        if (trySend(path)) {
            onLog("OK on retry $path (${System.currentTimeMillis() - started}ms)")
            return true
        }

        onLog("FAILED $path - check reader IP/port and that HTTP inspector is running.")
        return false
    }

    private suspend fun trySend(path: String): Boolean = try {
        val request = Request.Builder().url(baseUrl() + path).get().build()
        client.newCall(request).execute().use { it.isSuccessful }
    } catch (e: Exception) {
        Log.d("KOReaderClient", "request failed: ${e.message}")
        false
    }

    fun close() {
        keepAliveJob?.cancel()
        client.connectionPool.evictAll()
        hotExecutor.shutdown()
    }
}
