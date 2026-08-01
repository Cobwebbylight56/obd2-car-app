package com.rhys.obd2.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
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
 * Talks to a classic (pre-BLE) Bluetooth ELM327 over RFCOMM — the cheap blue dongles
 * that pair with a PIN of 1234 or 0000.
 *
 * These are simpler than BLE: once the socket is open it's a plain byte stream, so a
 * reader thread pumping [incoming] is all that's needed.
 */
@SuppressLint("MissingPermission")
class ClassicBtTransport(
    private val context: Context,
    private val device: AdapterDevice,
) : ObdTransport {

    override val name: String get() = device.name

    @Volatile
    private var connected = false
    override val isConnected: Boolean get() = connected

    private val _incoming = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val incoming: SharedFlow<String> = _incoming.asSharedFlow()

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var readerScope: CoroutineScope? = null
    private var readerJob: Job? = null

    override suspend fun connect() = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: throw ObdConnectionException("This device has no Bluetooth hardware")
        val adapter: BluetoothAdapter = manager.adapter
            ?: throw ObdConnectionException("Bluetooth is unavailable")
        if (!adapter.isEnabled) throw ObdConnectionException("Bluetooth is turned off")

        // Discovery hammers the radio and reliably breaks socket connects.
        if (adapter.isDiscovering) adapter.cancelDiscovery()

        val remote: BluetoothDevice = try {
            adapter.getRemoteDevice(device.address)
        } catch (e: IllegalArgumentException) {
            throw ObdConnectionException("Bad adapter address: ${device.address}", e)
        }

        val sock = openSocket(remote)
        socket = sock
        input = sock.inputStream
        output = sock.outputStream
        connected = true

        val scope = CoroutineScope(Dispatchers.IO)
        readerScope = scope
        readerJob = scope.launch { pump() }
    }

    /**
     * The well-known SPP connect first, then the reflection-based fallback on channel 1.
     * Plenty of cheap clones advertise SPP but only accept the insecure/reflective path.
     */
    private fun openSocket(remote: BluetoothDevice): BluetoothSocket {
        val attempts = listOf<Pair<String, () -> BluetoothSocket>>(
            "secure SPP" to { remote.createRfcommSocketToServiceRecord(SPP_UUID) },
            "insecure SPP" to { remote.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            "channel 1 fallback" to {
                val method = remote.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                method.invoke(remote, 1) as BluetoothSocket
            },
        )

        var lastError: Exception? = null
        for ((label, open) in attempts) {
            try {
                val sock = open()
                sock.connect()
                Log.i(TAG, "Connected to ${device.name} via $label")
                return sock
            } catch (e: Exception) {
                Log.w(TAG, "$label failed: ${e.message}")
                lastError = e
            }
        }
        throw ObdConnectionException(
            "Could not open a Bluetooth connection to ${device.name}. " +
                "Make sure it is paired in Android's Bluetooth settings first.",
            lastError,
        )
    }

    private suspend fun pump() {
        val stream = input ?: return
        val buffer = ByteArray(1024)
        val scope = readerScope
        try {
            while (scope?.isActive == true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (read > 0) {
                    _incoming.emit(String(buffer, 0, read, Charsets.US_ASCII))
                }
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
            throw ObdConnectionException("Write to ${device.name} failed", e)
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
        private const val TAG = "ClassicBtTransport"
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
