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
     * Whether the negotiated protocol is CAN, which decides whether the "how many replies"
     * optimisation is safe to use. See [obd].
     */
    var isCan: Boolean = true
        private set

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

        // 0100 is the standard "is anyone there" request. Under ATSP0 this is what actually
        // triggers the protocol search, so it gets a long timeout.
        //
        // Retried rather than trusted once. The ELM327 documentation is explicit that the
        // first request after a reset or a protocol change may fail while the search is
        // still settling, and that repeating it is the correct response. A single attempt
        // reported a perfectly good adapter as unable to reach the car.
        var probe = ""
        var probeError: ObdError? = null
        var attempt = 0
        while (attempt < AUTO_DETECT_ATTEMPTS) {
            if (attempt > 0) onProgress("Detecting protocol (attempt ${attempt + 1})")
            probe = runCatching { command("0100", PROTOCOL_TIMEOUT_MS) }.getOrElse { "" }
            probeError = detectError(probe)
            // A plain loop rather than `repeat`, because `return@repeat` continues to the
            // next iteration instead of leaving — so a successful first probe would have
            // been thrown away and replaced by a second, failing one.
            if (respondedToProbe(probe, probeError)) break
            attempt++
        }

        // Auto-detection failed. Walk the protocols by hand.
        //
        // ATSP0 is not the reliable mechanism it looks like. It searches well for CAN, which
        // is why it works on anything built since roughly 2008, but the older protocols need
        // a 5-baud initialisation sequence taking seconds per attempt and many clone chips
        // abandon the search before reaching them — or claim to have searched and quietly
        // didn't. On a pre-CAN car the result is an adapter that is plainly alive reporting
        // that it cannot reach a car it is perfectly capable of reaching.
        if (!respondedToProbe(probe, probeError)) {
            for (candidate in PROTOCOL_WALK) {
                onProgress("Trying ${candidate.label}")
                // ATTP rather than ATSP: try it, and leave it selected only if it answers.
                runCatching { command("ATTP${candidate.code}") }
                probe = runCatching { command("0100", candidate.timeoutMs) }.getOrElse { "" }
                probeError = detectError(probe)
                if (respondedToProbe(probe, probeError)) {
                    // Make the working protocol stick for the rest of the session, so no
                    // later request re-runs the search.
                    runCatching { command("ATSP${candidate.code}") }
                    Log.i(TAG, "Protocol found by walking: ${candidate.label}")
                    break
                }
            }
            // Leave the chip on automatic if nothing answered, so a retry starts clean
            // rather than pinned to whichever protocol happened to be tried last.
            if (!respondedToProbe(probe, probeError)) runCatching { command("ATSP0") }
        }

        protocolDescription = runCatching {
            command("ATDP").lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }
        }.getOrNull()
        isCan = protocolDescription?.contains("CAN", ignoreCase = true) ?: true
        Log.i(TAG, "Protocol: $protocolDescription (CAN: $isCan)")

        val voltage = runCatching {
            command("ATRV").lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }
        }.getOrNull()

        return if (respondedToProbe(probe, probeError)) {
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
                        "The adapter is working, but no protocol reached the car's computer — " +
                            "all nine were tried.\n\n" +
                            "The usual cause is the ignition: turn the key to position II so the " +
                            "dashboard lights come on. The engine doesn't need to be running, but " +
                            "the OBD socket is dead with the key out.\n\n" +
                            "If the ignition is on, check the adapter is pushed fully home — the " +
                            "socket is often loose, and a partly seated plug powers the adapter " +
                            "without connecting the data pins."
                    ObdError.NO_DATA ->
                        "The adapter reached the car but the engine computer didn't answer.\n\n" +
                            "On a diesel built before about 2004, this can mean the car predates " +
                            "mandatory EOBD and genuinely has nothing to talk to. On a petrol car " +
                            "it usually means the ignition is not fully on."
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
            //
            // Only ever sent on CAN. On the older buses several control units answer the
            // same standard request, and telling the chip to stop after the first leaves
            // the rest of them still transmitting. Those bytes arrive moments later, sit
            // in the buffer, and are read as the answer to whatever is asked next — so
            // readings appear on the wrong gauge. The optimisation is worth roughly a
            // halving of the sample rate on CAN, and it is worth nothing at all if the
            // numbers are wrong.
            if (isCan) expectedResponses?.let { append("%X".format(it.coerceIn(1, 15))) }
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

        /**
         * How many times to ask under automatic detection before walking by hand.
         *
         * Two, because the ELM327 documentation says the first request after a reset or a
         * protocol change can fail while the search settles, and repeating it is the
         * documented fix — but a third attempt has never been observed to succeed where
         * the second didn't, and each one costs twenty seconds.
         */
        const val AUTO_DETECT_ATTEMPTS = 2

        /** One protocol the chip can be told to try, with a budget suited to its init. */
        data class ProtocolCandidate(val code: String, val label: String, val timeoutMs: Long)

        /**
         * The manual search order, used only once automatic detection has given up.
         *
         * Ordered by what is actually likely at that point rather than by protocol number.
         * ATSP0 finds CAN reliably — that is the case it is good at — so by the time this
         * list is reached, CAN has effectively been ruled out and the older protocols are
         * the probable answer. Within those, fast initialisation comes before 5-baud
         * initialisation because it costs two seconds instead of twelve, and a wrong guess
         * that fails quickly is cheaper than a right guess reached slowly.
         *
         * CAN is still tried afterwards. A clone chip that mishandled the automatic search
         * may equally have mishandled CAN, and by then there is nothing left to lose.
         */
        val PROTOCOL_WALK = listOf(
            ProtocolCandidate("5", "ISO 14230-4 KWP, fast init", 8_000L),
            ProtocolCandidate("3", "ISO 9141-2", 12_000L),
            ProtocolCandidate("4", "ISO 14230-4 KWP, 5-baud init", 12_000L),
            ProtocolCandidate("6", "ISO 15765-4 CAN, 11-bit 500k", 6_000L),
            ProtocolCandidate("7", "ISO 15765-4 CAN, 29-bit 500k", 6_000L),
            ProtocolCandidate("1", "SAE J1850 PWM", 6_000L),
            ProtocolCandidate("2", "SAE J1850 VPW", 6_000L),
            ProtocolCandidate("8", "ISO 15765-4 CAN, 11-bit 250k", 6_000L),
            ProtocolCandidate("9", "ISO 15765-4 CAN, 29-bit 250k", 6_000L),
        )

        /**
         * Did the car answer the 0100 probe?
         *
         * A positive response to service 01 is 0x41, so the reply to `0100` begins `4100`.
         * Checked on the stripped hex rather than the raw text because adapters differ on
         * spacing, echo and line endings, and because a "SEARCHING..." notice can share the
         * buffer with the real answer.
         */
        fun respondedToProbe(raw: String, error: ObdError?): Boolean =
            error == null && raw.replace(Regex("[^0-9A-Fa-f]"), "").contains("4100", ignoreCase = true)
    }
}

class ObdTimeoutException(command: String) :
    Exception("The adapter didn't respond to '$command' in time")
