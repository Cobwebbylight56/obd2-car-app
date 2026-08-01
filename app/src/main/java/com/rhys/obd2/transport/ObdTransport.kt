package com.rhys.obd2.transport

import kotlinx.coroutines.flow.Flow

/**
 * How the app is physically wired to the ELM327 chip in the dongle.
 *
 * Every adapter type ultimately gives us the same thing: a bidirectional stream of
 * ASCII where we send a command terminated by CR and read back characters until the
 * chip emits its '>' prompt. The differences (GATT characteristics vs an RFCOMM
 * socket vs a TCP socket) are hidden behind this interface so that [com.rhys.obd2.elm.Elm327]
 * never has to care.
 */
interface ObdTransport {

    /** Human-readable name of the thing we're connected to, for the UI. */
    val name: String

    /** True between a successful [connect] and a [close] or link failure. */
    val isConnected: Boolean

    /**
     * Incoming bytes as they arrive, already decoded to ASCII. Chunk boundaries are
     * arbitrary and meaningless — BLE in particular will split a response across many
     * 20-byte notifications. The framing into commands/responses happens upstream.
     */
    val incoming: Flow<String>

    /** Opens the link. Throws [ObdConnectionException] if it can't. */
    suspend fun connect()

    /** Sends raw ASCII. The caller is responsible for the trailing carriage return. */
    suspend fun write(data: String)

    /** Tears the link down. Safe to call more than once. */
    fun close()
}

class ObdConnectionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** What kind of dongle we're talking to. Drives which transport gets built. */
enum class AdapterKind {
    BLE,
    CLASSIC_BLUETOOTH,
    WIFI,
    DEMO;

    val label: String
        get() = when (this) {
            BLE -> "Bluetooth LE"
            CLASSIC_BLUETOOTH -> "Bluetooth (classic)"
            WIFI -> "Wi-Fi"
            DEMO -> "Demo (no hardware)"
        }
}

/**
 * A dongle we could connect to. [address] is a MAC for Bluetooth and a "host:port"
 * string for Wi-Fi adapters.
 */
data class AdapterDevice(
    val name: String,
    val address: String,
    val kind: AdapterKind,
    val rssi: Int? = null,
    val bonded: Boolean = false,
)
