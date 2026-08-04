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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
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

    /** Reports which attempt is in progress, so a slow connect doesn't look like a hang. */
    var onProgress: (String) -> Unit = {}

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
     *
     * Every attempt is bounded. `BluetoothSocket.connect()` is a blocking call with no
     * timeout of its own, and against a device that is paired but not actually there — the
     * dongle unplugged, or the ignition off, which is the common case — it can block until
     * the stack gives up, if it ever does. Three of those in a row is why the app could sit
     * on "Opening" indefinitely with nothing to show for it.
     *
     * The timeout has to be enforced by closing the socket from another thread. `connect()`
     * ignores interruption and ignores coroutine cancellation; closing the socket underneath
     * it is the only thing that makes it return.
     */
    private suspend fun openSocket(remote: BluetoothDevice): BluetoothSocket {
        val attempts = listOf<Triple<String, Long, () -> BluetoothSocket>>(
            Triple("secure SPP", 10_000L) { remote.createRfcommSocketToServiceRecord(SPP_UUID) },
            Triple("insecure SPP", 8_000L) { remote.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            Triple("channel 1 fallback", 6_000L) {
                val method = remote.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                method.invoke(remote, 1) as BluetoothSocket
            },
        )

        var lastError: Exception? = null
        var timedOut = false

        // Deliberately not a `finally` for the cleanup. A successful attempt returns out of
        // this loop, and a `finally` would run on the way out and close the socket that was
        // just opened — so the failed sockets are closed in the catch blocks instead.
        attempts.forEachIndexed { index, (label, timeoutMs, open) ->
            onProgress("Connecting to ${device.name} (${index + 1} of ${attempts.size})")
            var sock: BluetoothSocket? = null
            try {
                sock = open()
                connectWithin(sock, timeoutMs)
                Log.i(TAG, "Connected to ${device.name} via $label")
                // Cheap clones are not ready the instant the socket opens; commands sent
                // immediately are answered with garbage or not at all. A short settle is
                // far cheaper than the ten-second ATZ timeout it otherwise costs.
                delay(SETTLE_MS)
                return sock
            } catch (e: SocketTimeout) {
                Log.w(TAG, "$label timed out after ${timeoutMs}ms")
                lastError = e
                timedOut = true
                // A half-open RFCOMM socket left behind makes the next connect fail too on
                // most Android stacks, which would defeat the very fallbacks below it.
                runCatching { sock?.close() }
            } catch (e: Exception) {
                Log.w(TAG, "$label failed: ${e.message}")
                lastError = e
                runCatching { sock?.close() }
            }
        }

        throw ObdConnectionException(diagnose(timedOut), lastError)
    }

    /**
     * Runs the blocking connect with a hard deadline, closing the socket to break it out.
     */
    private suspend fun connectWithin(sock: BluetoothSocket, timeoutMs: Long) {
        coroutineScope {
            val watchdog = launch(Dispatchers.IO) {
                delay(timeoutMs)
                // The only lever that works. connect() is not interruptible.
                runCatching { sock.close() }
            }
            try {
                runInterruptible(Dispatchers.IO) { sock.connect() }
            } catch (e: IOException) {
                // A close from the watchdog surfaces here as a generic IO failure, so the
                // watchdog's own state is what distinguishes a timeout from a refusal.
                if (!watchdog.isActive) throw SocketTimeout(timeoutMs) else throw e
            } finally {
                watchdog.cancel()
            }
        }
    }

    private class SocketTimeout(val afterMs: Long) : IOException("Connect timed out after ${afterMs}ms")

    /**
     * Says what to actually do about it.
     *
     * A timeout and a refusal mean different things and have different fixes, and "could
     * not connect" covers both uselessly. Timing out on a paired device almost always means
     * the dongle has no power — which on most cars means the ignition is not on, since the
     * OBD socket is dead otherwise.
     */
    private fun diagnose(timedOut: Boolean): String = if (timedOut) {
        "${device.name} is paired but didn't answer.\n\n" +
            "The usual cause is that the adapter has no power: the OBD socket is dead " +
            "until the ignition is on. Turn the key to position II so the dashboard " +
            "lights come on, check the adapter's own light is lit, and try again.\n\n" +
            "If it is lit, switch Bluetooth off and on — a stuck pairing on the phone " +
            "side clears that way."
    } else {
        "${device.name} refused the connection.\n\n" +
            "This usually means it is already connected to something else — another " +
            "phone, or a scanner app left running in the background. Close any other " +
            "OBD app, or unpair and re-pair the adapter in Android's Bluetooth settings " +
            "(the PIN is almost always 1234 or 0000)."
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

        /** Settle time after the socket opens, before the first command. */
        private const val SETTLE_MS = 400L
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
