package com.rhys.obd2.elm

import android.util.Log
import com.rhys.obd2.transport.ObdConnectionException
import com.rhys.obd2.transport.ObdTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Why a request didn't produce usable data. */
enum class ObdError {
    /** The ECU understood the request but has nothing for that PID. Usually "not supported". */
    NO_DATA,

    /** The adapter couldn't get on the bus. Ignition off, or wrong protocol. */
    UNABLE_TO_CONNECT,

    /** Bus-level fault: wiring, termination, or a protocol mismatch. */
    BUS_ERROR,

    /** The chip aborted the request, typically because another command interrupted it. */
    STOPPED,

    /** The adapter didn't answer in time. */
    TIMEOUT,

    /** The ELM327 didn't recognise the command at all. */
    UNKNOWN_COMMAND,

    /** The transport is down. */
    NOT_CONNECTED,

    /** Response arrived but wasn't the service we asked for. */
    BAD_RESPONSE,
}

/** Raw text captured from the wire, for the terminal and for bug reports. */
data class WireLine(val outgoing: Boolean, val text: String, val timestamp: Long = System.currentTimeMillis())

/**
 * Result of one OBD request. [data] holds the payload bytes with the service byte and
 * echoed PID already stripped, which is what every decoder wants.
 */
data class ObdResult(
    val raw: String,
    val data: IntArray = IntArray(0),
    val error: ObdError? = null,
) {
    val isSuccess: Boolean get() = error == null

    // IntArray in a data class needs these written out.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ObdResult) return false
        return raw == other.raw && data.contentEquals(other.data) && error == other.error
    }

    override fun hashCode(): Int =
        (raw.hashCode() * 31 + data.contentHashCode()) * 31 + (error?.hashCode() ?: 0)
}

/**
 * Drives an ELM327 (or one of the many clones) over an [ObdTransport].
 *
 * The chip is a strictly half-duplex, one-command-at-a-time device: you write a command
 * terminated with CR, then read until it prints a '>' prompt. Sending a second command
 * before the prompt arrives corrupts both. [commandLock] enforces that, which is why
 * every caller in the app can pretend requests are independent.
 */
class Elm327(
    private val transport: ObdTransport,
    private val scope: CoroutineScope,
) {

    private val commandLock = Mutex()
    private val buffer = StringBuilder()
    private var pending: CompletableDeferred<String>? = null
    private var collectorJob: Job? = null

    private val _wire = MutableSharedFlow<WireLine>(
        replay = 200,
        extraBufferCapacity = 200,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val wire = _wire.asSharedFlow()

    /** Protocol the adapter settled on, as reported by ATDP. Null until [initialise] runs. */
    var protocolDescription: String? = null
        private set

    /** Adapter firmware banner from ATZ/ATI, e.g. "ELM327 v1.5". */
    var adapterIdentity: String? = null
        private set

    /** True once headers are enabled, so parsers know to expect a leading CAN ID. */
    private var headersOn = false

    /**
     * Begins consuming the transport, and does not return until the subscription is
     * actually registered.
     *
     * The wait matters. [ObdTransport.incoming] is hot with no replay, so anything the
     * adapter says between the write and the collector attaching is dropped on the floor.
     * Launching the collector and immediately writing a command is a race that a slow
     * Bluetooth link hides and a fast Wi-Fi or loopback link loses: the response arrives
     * before anyone is listening, and the command times out for no visible reason.
     */
    suspend fun start() {
        collectorJob?.cancel()
        val subscribed = CompletableDeferred<Unit>()
        collectorJob = scope.launch {
            transport.incoming
                .onSubscription { subscribed.complete(Unit) }
                .collect { chunk -> onChunk(chunk) }
        }
        subscribed.await()
    }

    fun stop() {
        collectorJob?.cancel()
        collectorJob = null
        pending?.complete("")
        pending = null
        synchronized(buffer) { buffer.setLength(0) }
    }

    /**
     * Accumulates incoming characters. The '>' prompt is the only reliable end-of-response
     * marker — responses can span arbitrarily many transport chunks, especially over BLE
     * where 20 bytes per notification is common.
     */
    private fun onChunk(chunk: String) {
        // Assigned inside the lock, consumed outside it: completing the deferred while
        // holding the buffer lock would let the resumed coroutine re-enter onChunk.
        var complete: String? = null
        synchronized(buffer) {
            buffer.append(chunk)
            val promptIndex = buffer.indexOf(">")
            if (promptIndex >= 0) {
                complete = buffer.substring(0, promptIndex)
                buffer.delete(0, promptIndex + 1)
            }
        }
        val text = complete ?: return
        _wire.tryEmit(WireLine(outgoing = false, text = text.trim()))
        pending?.complete(text)
    }

    /**
     * Sends a command and returns everything the adapter said before the next prompt.
     * Prefer [obd] for actual OBD requests; this is for AT commands and the terminal.
     */
    suspend fun command(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String {
        if (!transport.isConnected) throw ObdConnectionException("Adapter is not connected")

        return commandLock.withLock {
            // Anything still buffered belongs to a previous, abandoned command.
            synchronized(buffer) { buffer.setLength(0) }

            val ack = CompletableDeferred<String>()
            pending = ack
            _wire.tryEmit(WireLine(outgoing = true, text = command))
            transport.write(command + "\r")

            try {
                withTimeout(timeoutMs) { ack.await() }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "Timeout waiting for response to '$command'")
                throw ObdTimeoutException(command)
            } finally {
                pending = null
            }
        }
    }

    /**
     * Brings the adapter up and gets it onto the vehicle bus.
     *
     * Order matters here. Echo has to go first or every subsequent response is polluted
     * with its own command. Spaces go off because it roughly halves the bytes on a slow
     * BLE link. Protocol detection is last and is the slow step — the chip may try each
     * of the nine OBD protocols in turn.
     */
    suspend fun initialise(onProgress: (String) -> Unit = {}): InitResult {
        onProgress("Resetting adapter")
        val reset = runCatching { command("ATZ", RESET_TIMEOUT_MS) }.getOrElse { "" }
        adapterIdentity = reset.lines()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() && !it.equals("ATZ", ignoreCase = true) }
        // The chip is genuinely unresponsive for a moment after a reset.
        delay(300)

        onProgress("Configuring")
        // Failures here are not fatal: some clones reject commands they don't implement.
        runCatching { command("ATE0") }
        runCatching { command("ATL0") }
        runCatching { command("ATS0") }
        runCatching { command("ATH0") }
        headersOn = false
        runCatching { command("ATAT1") }

        if (adapterIdentity.isNullOrBlank()) {
            adapterIdentity = runCatching { command("ATI").trim().lines().lastOrNull()?.trim() }.getOrNull()
        }

        onProgress("Detecting protocol")
        runCatching { command("ATSP0") }

        // 0100 is the standard "is anyone there" request. Under ATSP0 this is what
        // actually triggers the protocol search, so it gets a long timeout.
        val probe = runCatching { command("0100", PROTOCOL_TIMEOUT_MS) }.getOrElse { "" }
        val probeError = detectError(probe)

        protocolDescription = runCatching {
            command("ATDP").lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }
        }.getOrNull()

        val voltage = runCatching {
            command("ATRV").lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }
        }.getOrNull()

        return if (probeError == null && probe.replace(Regex("[^0-9A-Fa-f]"), "").contains("4100", ignoreCase = true)) {
            InitResult(
                success = true,
                adapter = adapterIdentity,
                protocol = protocolDescription,
                batteryVoltage = voltage,
            )
        } else {
            InitResult(
                success = false,
                adapter = adapterIdentity,
                protocol = protocolDescription,
                batteryVoltage = voltage,
                message = when (probeError) {
                    ObdError.UNABLE_TO_CONNECT ->
                        "The adapter is working but can't reach the car's computer. " +
                            "Turn the ignition to position II (dashboard lights on) and try again."
                    ObdError.NO_DATA ->
                        "The car didn't answer. Turn the ignition on, or the engine may need to be running."
                    ObdError.BUS_ERROR ->
                        "Bus error while connecting. Unplug the adapter, wait ten seconds, and plug it back in."
                    else ->
                        "Couldn't establish an OBD-II session. Check the adapter is fully seated in the port."
                },
            )
        }
    }

    /** Turns response headers on or off, keeping the parser in sync. */
    suspend fun setHeaders(enabled: Boolean) {
        runCatching { command(if (enabled) "ATH1" else "ATH0") }
        headersOn = enabled
    }

    /**
     * Issues an OBD request and returns the payload with the service byte and echoed
     * PID stripped.
     *
     * [expectedResponses] is the ELM327's "number of replies to wait for" hint, sent as a
     * single hex digit after the request. It matters more than it looks: without it the
     * chip sits out its entire timeout after the last frame in case a second ECU answers,
     * which on a slow BLE link roughly halves the achievable sample rate. Pass 1 for
     * routine live-data polling, and leave it null whenever every ECU's answer is wanted.
     *
     * [echoesPid] controls whether the requested PID is stripped from the response.
     * Normally it should be — the ECU echoes it and no decoder wants it. Service 06 is
     * the exception: it repeats the monitor ID at the head of every test record, so
     * stripping it would eat the first record's first byte and shift the whole response.
     */
    suspend fun obd(
        mode: Int,
        pid: Int? = null,
        expectedResponses: Int? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        echoesPid: Boolean = true,
    ): ObdResult {
        if (!transport.isConnected) {
            return ObdResult(raw = "", error = ObdError.NOT_CONNECTED)
        }

        val request = buildString {
            append("%02X".format(mode))
            if (pid != null) append("%02X".format(pid))
            // One hex digit only — anything wider would be parsed by the chip as part of
            // the request itself.
            expectedResponses?.let { append("%X".format(it.coerceIn(1, 15))) }
        }

        val raw = try {
            command(request, timeoutMs)
        } catch (e: ObdTimeoutException) {
            return ObdResult(raw = "", error = ObdError.TIMEOUT)
        } catch (e: ObdConnectionException) {
            return ObdResult(raw = "", error = ObdError.NOT_CONNECTED)
        }

        detectError(raw)?.let { return ObdResult(raw = raw, error = it) }

        val bytes = ObdParser.parse(raw, mode, if (echoesPid) pid else null, headersOn)
            ?: return ObdResult(raw = raw, error = ObdError.BAD_RESPONSE)

        return ObdResult(raw = raw, data = bytes)
    }

    /** Recognises the ELM327's plain-English failure strings. */
    private fun detectError(raw: String): ObdError? {
        val upper = raw.uppercase()
        return when {
            upper.contains("UNABLE TO CONNECT") -> ObdError.UNABLE_TO_CONNECT
            upper.contains("NO DATA") -> ObdError.NO_DATA
            upper.contains("CAN ERROR") -> ObdError.BUS_ERROR
            upper.contains("BUS ERROR") -> ObdError.BUS_ERROR
            upper.contains("BUS INIT: ERROR") -> ObdError.BUS_ERROR
            upper.contains("BUS BUSY") -> ObdError.BUS_ERROR
            upper.contains("DATA ERROR") -> ObdError.BUS_ERROR
            upper.contains("FB ERROR") -> ObdError.BUS_ERROR
            upper.contains("BUFFER FULL") -> ObdError.BUS_ERROR
            upper.contains("STOPPED") -> ObdError.STOPPED
            upper.contains("UNABLE") -> ObdError.UNABLE_TO_CONNECT
            upper.trim() == "?" -> ObdError.UNKNOWN_COMMAND
            upper.contains("ERROR") -> ObdError.BUS_ERROR
            else -> null
        }
    }

    data class InitResult(
        val success: Boolean,
        val adapter: String?,
        val protocol: String?,
        val batteryVoltage: String?,
        val message: String? = null,
    )

    companion object {
        private const val TAG = "Elm327"
        const val DEFAULT_TIMEOUT_MS = 5_000L
        const val RESET_TIMEOUT_MS = 10_000L
        const val PROTOCOL_TIMEOUT_MS = 20_000L
        const val SLOW_TIMEOUT_MS = 12_000L
    }
}

class ObdTimeoutException(command: String) :
    Exception("The adapter didn't respond to '$command' in time")
