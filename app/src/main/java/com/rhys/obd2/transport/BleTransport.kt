package com.rhys.obd2.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Talks to a Bluetooth Low Energy ELM327 clone (Vgate iCar Pro, OBDLink CX, LELink,
 * and the many unbranded ones).
 *
 * There is no standard GATT profile for these things. Every vendor picked their own
 * service and characteristic UUIDs, so we try a table of the known ones first and then
 * fall back to "any characteristic that can notify plus any that can be written". That
 * fallback is what makes unbranded dongles work.
 */
@SuppressLint("MissingPermission")
class BleTransport(
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
    override val incoming: Flow<String> = _incoming.asSharedFlow()

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    /** BLE permits exactly one outstanding GATT operation, so every write is serialised. */
    private val writeMutex = Mutex()
    private var pendingWrite: CompletableDeferred<Boolean>? = null

    private var servicesReady: CompletableDeferred<Boolean>? = null
    private var connectionReady: CompletableDeferred<Boolean>? = null
    private var mtuNegotiated: CompletableDeferred<Int>? = null

    /** Usable payload per write. Starts at the BLE default and grows if MTU negotiation works. */
    private var chunkSize = DEFAULT_MTU - 3

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT connected, requesting MTU")
                    connectionReady?.complete(true)
                    // Bigger MTU means fewer notification fragments to reassemble.
                    if (!g.requestMtu(PREFERRED_MTU)) {
                        mtuNegotiated?.complete(DEFAULT_MTU)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "GATT disconnected (status=$status)")
                    connected = false
                    connectionReady?.complete(false)
                    servicesReady?.complete(false)
                    mtuNegotiated?.complete(DEFAULT_MTU)
                    pendingWrite?.complete(false)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            val effective = if (status == BluetoothGatt.GATT_SUCCESS) mtu else DEFAULT_MTU
            chunkSize = (effective - 3).coerceAtLeast(DEFAULT_MTU - 3)
            Log.i(TAG, "MTU=$effective chunk=$chunkSize")
            mtuNegotiated?.complete(effective)
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                servicesReady?.complete(false)
                return
            }
            servicesReady?.complete(selectCharacteristics(g))
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        // Android 13+
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            emit(value)
        }

        @Deprecated("Required for API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                characteristic.value?.let { emit(it) }
            }
        }
    }

    private fun emit(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        _incoming.tryEmit(String(bytes, Charsets.US_ASCII))
    }

    override suspend fun connect() {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            ?: throw ObdConnectionException("This device has no Bluetooth hardware")
        val adapter: BluetoothAdapter = manager.adapter
            ?: throw ObdConnectionException("Bluetooth is unavailable")
        if (!adapter.isEnabled) throw ObdConnectionException("Bluetooth is turned off")

        val remote = try {
            adapter.getRemoteDevice(device.address)
        } catch (e: IllegalArgumentException) {
            throw ObdConnectionException("Bad adapter address: ${device.address}", e)
        }

        connectionReady = CompletableDeferred()
        servicesReady = CompletableDeferred()
        mtuNegotiated = CompletableDeferred()

        gatt = remote.connectGatt(context, false, callback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
            ?: throw ObdConnectionException("Could not open a GATT connection")

        val linkUp = try {
            withTimeout(CONNECT_TIMEOUT_MS) { connectionReady!!.await() }
        } catch (e: TimeoutCancellationException) {
            close()
            throw ObdConnectionException("${device.name} did not respond. Is it powered and in range?", e)
        }
        if (!linkUp) {
            close()
            throw ObdConnectionException("${device.name} refused the connection")
        }

        val ready = try {
            withTimeout(SERVICE_TIMEOUT_MS) { servicesReady!!.await() }
        } catch (e: TimeoutCancellationException) {
            close()
            throw ObdConnectionException("Timed out reading the adapter's Bluetooth services", e)
        }
        if (!ready) {
            close()
            throw ObdConnectionException(
                "${device.name} doesn't expose a usable serial service. " +
                    "It may not be an ELM327-compatible adapter."
            )
        }
        connected = true
    }

    /**
     * Picks the pair of characteristics we'll use as a serial pipe.
     *
     * Tries the vendor UUIDs we know about, then falls back to shape-matching on the
     * characteristic properties, which covers adapters we've never seen.
     */
    private fun selectCharacteristics(g: BluetoothGatt): Boolean {
        for (profile in KNOWN_PROFILES) {
            val service = g.getService(profile.service) ?: continue
            val notify = service.getCharacteristic(profile.notify)
            val write = service.getCharacteristic(profile.write)
            if (notify != null && write != null) {
                Log.i(TAG, "Matched known profile ${profile.label}")
                return activate(g, notify, write)
            }
        }

        // Unknown adapter: find anything that looks like a serial pipe.
        var notify: BluetoothGattCharacteristic? = null
        var write: BluetoothGattCharacteristic? = null
        for (service in g.services) {
            if (service.uuid == GENERIC_ACCESS || service.uuid == GENERIC_ATTRIBUTE) continue
            for (c in service.characteristics) {
                val props = c.properties
                val canNotify = props and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                val canWrite = props and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                if (canNotify && notify == null) notify = c
                if (canWrite && write == null) write = c
                // Some adapters use one characteristic for both directions.
                if (canNotify && canWrite) {
                    notify = c
                    write = c
                    break
                }
            }
            if (notify != null && write != null) break
        }
        if (notify == null || write == null) return false
        Log.i(TAG, "Falling back to discovered pipe notify=${notify.uuid} write=${write.uuid}")
        return activate(g, notify, write)
    }

    private fun activate(
        g: BluetoothGatt,
        notify: BluetoothGattCharacteristic,
        write: BluetoothGattCharacteristic,
    ): Boolean {
        notifyChar = notify
        writeChar = write
        if (!g.setCharacteristicNotification(notify, true)) return false

        // Subscribing on the phone side isn't enough; the peer needs its CCCD written too.
        val cccd = notify.getDescriptor(CCCD_UUID) ?: return true
        val value = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, value)
        } else {
            @Suppress("DEPRECATION")
            run {
                cccd.value = value
                g.writeDescriptor(cccd)
            }
        }
        return true
    }

    override suspend fun write(data: String) {
        val g = gatt ?: throw ObdConnectionException("Not connected")
        val c = writeChar ?: throw ObdConnectionException("No writable characteristic")
        val bytes = data.toByteArray(Charsets.US_ASCII)

        writeMutex.withLock {
            var offset = 0
            while (offset < bytes.size) {
                val end = minOf(offset + chunkSize, bytes.size)
                val chunk = bytes.copyOfRange(offset, end)
                if (!writeChunk(g, c, chunk)) {
                    throw ObdConnectionException("Write to ${device.name} failed")
                }
                offset = end
            }
        }
    }

    private suspend fun writeChunk(
        g: BluetoothGatt,
        c: BluetoothGattCharacteristic,
        chunk: ByteArray,
    ): Boolean {
        val writeType = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }

        val ack = CompletableDeferred<Boolean>()
        pendingWrite = ack

        val queued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, chunk, writeType) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                c.writeType = writeType
                c.value = chunk
                g.writeCharacteristic(c)
            }
        }
        if (!queued) {
            pendingWrite = null
            return false
        }

        return try {
            withTimeout(WRITE_TIMEOUT_MS) { ack.await() }
        } catch (e: TimeoutCancellationException) {
            false
        } finally {
            pendingWrite = null
        }
    }

    override fun close() {
        connected = false
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing GATT", e)
        }
        gatt = null
        writeChar = null
        notifyChar = null
    }

    private data class GattProfile(
        val label: String,
        val service: UUID,
        val notify: UUID,
        val write: UUID,
    )

    companion object {
        private const val TAG = "BleTransport"
        private const val DEFAULT_MTU = 23
        private const val PREFERRED_MTU = 517
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val SERVICE_TIMEOUT_MS = 15_000L
        private const val WRITE_TIMEOUT_MS = 3_000L

        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val GENERIC_ACCESS: UUID = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
        private val GENERIC_ATTRIBUTE: UUID = UUID.fromString("00001801-0000-1000-8000-00805f9b34fb")

        private fun short(id: String): UUID = UUID.fromString("0000$id-0000-1000-8000-00805f9b34fb")

        /**
         * Service UUIDs worth advertising a scan filter for. Kept public so the scanner
         * can prioritise likely adapters without filtering out unknown ones.
         */
        val SERVICE_HINTS: List<UUID> = listOf(
            short("FFF0"), short("FFE0"), short("18F0"), short("FFE5"), short("FF00"),
            UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"),
        )

        private val KNOWN_PROFILES = listOf(
            // Vgate iCar Pro / vLinker and many generic CC2541 dongles
            GattProfile("18F0 (Vgate/vLinker)", short("18F0"), short("2AF0"), short("2AF1")),
            // Very common HM-10 / CC2541 module: one characteristic both ways
            GattProfile("FFE0 (HM-10)", short("FFE0"), short("FFE1"), short("FFE1")),
            // Common JDY/BT05 style split characteristics
            GattProfile("FFF0 split", short("FFF0"), short("FFF1"), short("FFF2")),
            GattProfile("FFF0 shared", short("FFF0"), short("FFF1"), short("FFF1")),
            // Nordic UART Service, used by OBDLink CX and various ESP32 builds
            GattProfile(
                "Nordic UART",
                UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"),
                UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"),
                UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"),
            ),
            // LELink
            GattProfile(
                "LELink",
                UUID.fromString("E7810A71-73AE-499D-8C15-FAA9AEF0C3F2"),
                UUID.fromString("BEF8D6C9-9C21-4C9E-B632-BD58C1009F9F"),
                UUID.fromString("BEF8D6C9-9C21-4C9E-B632-BD58C1009F9F"),
            ),
        )
    }
}
