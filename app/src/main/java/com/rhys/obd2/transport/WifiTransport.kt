package com.rhys.obd2.transport

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Talks to a Wi-Fi ELM327, which hosts its own access point and exposes the chip as a
 * raw TCP socket — almost always 192.168.0.10:35000.
 *
 * The phone must be joined to the dongle's Wi-Fi network for this to work, which means
 * no mobile data routing weirdness: Android will happily keep cellular as the default
 * network, so the socket is bound through whatever route reaches the literal IP.
 */
class WifiTransport(
    private val host: String,
    private val port: Int,
) : ObdTransport {

    override val name: String get() = "$host:$port"

    @Volatile
    private var connected = false
    override val isConnected: Boolean get() = connected

    private val _incoming = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val incoming: SharedFlow<String> = _incoming.asSharedFlow()

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var readerScope: CoroutineScope? = null
    private var readerJob: Job? = null

    override suspend fun connect() = withContext(Dispatchers.IO) {
        try {
            val sock = Socket()
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket = sock
            input = sock.getInputStream()
            output = sock.getOutputStream()
            connected = true
        } catch (e: IOException) {
            throw ObdConnectionException(
                "Could not reach $host:$port. Check the phone is joined to the adapter's Wi-Fi network.",
                e,
            )
        }

        val scope = CoroutineScope(Dispatchers.IO)
        readerScope = scope
        readerJob = scope.launch { pump() }
    }

    private suspend fun pump() {
        val stream = input ?: return
        val buffer = ByteArray(1024)
        val scope = readerScope
        try {
            while (scope?.isActive == true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (read > 0) _incoming.emit(String(buffer, 0, read, Charsets.US_ASCII))
            }
        } catch (e: IOException) {
            Log.i(TAG, "Reader stopped: ${e.message}")
        } finally {
            connected = false
        }
    }

    override suspend fun write(data: String) = withContext(Dispatchers.IO) {
        val out = output ?: throw ObdConnectionException("Not connected")
        try {
            out.write(data.toByteArray(Charsets.US_ASCII))
            out.flush()
        } catch (e: IOException) {
            connected = false
            throw ObdConnectionException("Write to $name failed", e)
        }
    }

    override fun close() {
        connected = false
        readerJob?.cancel()
        readerScope?.cancel()
        readerScope = null
        listOf<() -> Unit>({ input?.close() }, { output?.close() }, { socket?.close() }).forEach {
            runCatching { it() }
        }
        input = null
        output = null
        socket = null
    }

    companion object {
        private const val TAG = "WifiTransport"
        private const val CONNECT_TIMEOUT_MS = 8_000
        const val DEFAULT_HOST = "192.168.0.10"
        const val DEFAULT_PORT = 35000
    }
}
