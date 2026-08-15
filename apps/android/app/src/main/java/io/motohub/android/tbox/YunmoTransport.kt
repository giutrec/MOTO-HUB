package io.motohub.android.tbox

import android.content.Context
import io.motohub.android.session.ProjectionEventLog
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.withContext

/**
 * Yunmo :8200 SoftAP projection transport (Moto Morini X-Cape 1200 and any dash sharing that
 * wire). Unlike EasyConn there is nothing to discover — the SoftAP is already joined and the dash
 * always answers on `192.168.4.1:8200` — so [discover] just names the host and all the real work
 * happens in [start]: open the socket, ask the dash for its canvas size, tell it to start, and
 * emit the negotiated [TBoxEvent.VideoArea] the session encoder is then configured from.
 *
 * The dash reports its real canvas and the emitted area is that size (see
 * [YunmoProtocol.encodeCanvas], which documents the doubling this used to do and why it was
 * wrong); when the dash never answers the size query, the profile's
 * [TBoxModelProfile.fallbackTBoxVideoArea] backstops it. Frame metadata is deliberately kept off
 * the wire ([YunmoProtocol.encodeH264Ex] `omitMeta`), matching the only build known to render.
 *
 * [answersOnThisLink] lets the EasyConn path hand a session over when a dash turns out to speak
 * Yunmo, so reaching this transport does not depend on the rider having found a profile override.
 *
 * See [YunmoProtocol] for the byte format and the memory note `reference-yunmo-8200-protocol`.
 */
class YunmoTransport(context: Context) : TBoxTransport {

    private val appContext = context.applicationContext
    private val mutableEvents = MutableSharedFlow<TBoxEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val events: Flow<TBoxEvent> = mutableEvents.asSharedFlow()

    @Volatile
    private var protocolProfile: TBoxModelProfile? = null

    @Volatile
    private var link: TBoxLink? = null

    @Volatile
    private var session: Session? = null
    private val sessionLock = Any()

    override fun configureProtocolProfile(profile: TBoxModelProfile) {
        protocolProfile = profile
    }

    override suspend fun discover(link: TBoxLink, expectedModelId: String?): Result<TBoxHost> =
        withContext(Dispatchers.IO) {
            runCatching {
                teardownSession()
                this@YunmoTransport.link = link
                // No probing: the SoftAP is already joined and the dash listens on a fixed
                // address. A peer hint (Wi-Fi Direct) wins if one is ever present.
                val host = link.peerHint?.hostAddress ?: YunmoProtocol.DEFAULT_HOST
                ProjectionEventLog.record(
                    "YUNMO",
                    "SoftAP link ready (${link.label}); dash expected at $host:${YunmoProtocol.DEFAULT_PORT}."
                )
                TBoxHost(host, YunmoProtocol.DEFAULT_PORT, YUNMO_PACKAGE_TAG)
            }
        }

    /**
     * Asks whether a dash on [link] speaks Yunmo, for the EasyConn path to call once its own
     * discovery has come up empty. Returns the host to hand to [start], or null.
     *
     * This exists because the profile used to be the only way in: a rider whose dash speaks Yunmo
     * had to find and pin a profile override by hand, and one who did not simply got "EasyConn
     * offline" forever. Which protocol a dash speaks is something the dash can be asked, so ask it.
     *
     * A bare TCP connect is not enough to answer — plenty of things listen on a port. The dash has
     * to reply to the canvas query with a well-formed frame, which also means a positive answer
     * has already proved the socket path works end to end.
     */
    suspend fun answersOnThisLink(link: TBoxLink): String? = withContext(Dispatchers.IO) {
        val host = link.peerHint?.hostAddress ?: YunmoProtocol.DEFAULT_HOST
        try {
            link.createSocket().use { socket ->
                socket.connect(
                    InetSocketAddress(host, YunmoProtocol.DEFAULT_PORT),
                    PROBE_CONNECT_TIMEOUT_MS
                )
                socket.soTimeout = PROBE_READ_TIMEOUT_MS
                socket.tcpNoDelay = true
                val output = BufferedOutputStream(socket.getOutputStream())
                output.write(YunmoProtocol.dimQueryFrame())
                output.flush()
                val reply = YunmoProtocol.readSimpleFrame(BufferedInputStream(socket.getInputStream()))
                    ?: return@withContext null
                val known = reply.command == YunmoProtocol.CMD_OK_A ||
                    reply.command == YunmoProtocol.CMD_OK_B ||
                    reply.command == YunmoProtocol.CMD_DISPLAY
                if (!known) return@withContext null
                ProjectionEventLog.record(
                    "YUNMO",
                    "A dash at $host:${YunmoProtocol.DEFAULT_PORT} answered the canvas query " +
                        "(cmd 0x${reply.command.toString(16)}), so this motorcycle speaks Yunmo."
                )
                host
            }
        } catch (failure: Throwable) {
            ProjectionEventLog.debug(
                "YUNMO",
                "No Yunmo dash at $host:${YunmoProtocol.DEFAULT_PORT}: ${failure.message}."
            )
            null
        }
    }

    override suspend fun start(host: TBoxHost): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val activeLink = link ?: error("Yunmo link is not established; discover first.")
            val profile = protocolProfile
            val fallback = profile?.fallbackTBoxVideoArea ?: TBoxEvent.VideoArea(800, 480)
            val mapNav = profile?.yunmoMapNavExperiment == true

            teardownSession()
            val created = Session(mapNavMode = mapNav)
            synchronized(sessionLock) { session = created }
            try {
                created.connect(activeLink, host)
                created.startReceiving()

                // Query, request the mode, THEN wait for the canvas — the order the OEM app uses.
                // A capture of Ride MO shows the dash answering the size query only after it has
                // been told which display mode to enter (the reply lands between the mode request
                // and the follow-up burst), which makes sense if the canvas differs per mode.
                // Waiting for the size first, as this used to, would spend the whole timeout on a
                // reply the dash is not going to send yet, then fall back to the profile geometry
                // and encode a 1024x464 panel at 800x480. A dash that does answer immediately is
                // unaffected: the reply is collected by the receive loop either way.
                created.sendDimQuery()
                created.beginMode()
                val report = created.awaitDimensions()

                val (canvasW, canvasH) = YunmoProtocol.encodeCanvas(report, fallback.width, fallback.height)
                created.canvasWidth = canvasW
                created.canvasHeight = canvasH
                ProjectionEventLog.record(
                    "YUNMO",
                    if (report != null) {
                        "Dash canvas reported ${report.reportedWidth}x${report.reportedHeight}; " +
                            "encoding ${canvasW}x$canvasH."
                    } else {
                        "No dim response — encoding fallback ${canvasW}x$canvasH."
                    }
                )
                mutableEvents.tryEmit(
                    TBoxEvent.VideoArea(canvasW, canvasH, isFallback = report == null)
                )
                mutableEvents.tryEmit(TBoxEvent.VideoStreamStart)
                Unit
            } catch (failure: Throwable) {
                teardownSession()
                throw failure
            }
        }
    }

    override fun offerAccessUnit(avcc: ByteArray): Boolean {
        val active = session ?: return false
        return active.offerAccessUnit(avcc)
    }

    override suspend fun stop() {
        val active = synchronized(sessionLock) { session } ?: return
        // Tell the dash the mirror ended so its UI returns to the stock dashboard before the
        // socket is closed under it.
        active.sendStop()
        teardownSession()
        mutableEvents.tryEmit(TBoxEvent.Stopped)
    }

    private fun teardownSession() {
        val previous = synchronized(sessionLock) {
            val current = session
            session = null
            current
        }
        previous?.close()
    }

    private fun reportFatal(message: String) {
        val active = session ?: return
        if (active.fatalReported.compareAndSet(false, true)) {
            ProjectionEventLog.error("YUNMO", message)
            mutableEvents.tryEmit(TBoxEvent.FatalError(message))
        }
    }

    /** Everything owned by one dash connection, torn down as a unit. */
    private inner class Session(private val mapNavMode: Boolean) {
        val fatalReported = AtomicBoolean(false)
        private val running = AtomicBoolean(false)

        @Volatile
        private var socket: Socket? = null

        @Volatile
        private var output: OutputStream? = null

        private val frameId = AtomicInteger(0)

        @Volatile
        private var lastAckedId = -1

        @Volatile
        private var unackedSinceAck = 0

        @Volatile
        private var pendingDim: YunmoProtocol.DimensionReport? = null

        @Volatile
        private var mapNavConfirmed = false

        /** Media acks received this session; only every [ACK_LOG_INTERVAL]th is logged. */
        @Volatile
        private var acksSeen = 0

        /**
         * The dash is showing its own compact arrow guidance, which it renders itself. Frames
         * pushed in this state are discarded by the dash, so the sender skips them until it
         * announces map-nav again.
         */
        @Volatile
        private var simpleNaviActive = false

        /**
         * Cleared whenever the dash restarts its decoder (a SimpleNavi round trip), so the next
         * access unit carries SPS/PPS again instead of arriving as a picture the dash cannot decode.
         */
        @Volatile
        private var sentParameterSets = false

        @Volatile
        var canvasWidth = 0

        @Volatile
        var canvasHeight = 0

        private val ackLock = Object()
        private val dimLock = Object()

        private var receiveThread: Thread? = null

        // Phase timeline. A Yunmo session fails in stages that look identical from the outside
        // (socket up but no mode, mode but no video, video but no paint), so every milestone is
        // stamped relative to the connect attempt and the one-shot ones are logged exactly once.
        private val startedAtMillis = System.currentTimeMillis()
        private val loggedFirsts = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        fun phase(event: String) {
            ProjectionEventLog.record(
                "YUNMO",
                "phase +${System.currentTimeMillis() - startedAtMillis}ms: $event"
            )
        }

        private fun phaseOnce(key: String, event: () -> String) {
            if (loggedFirsts.add(key)) phase(event())
        }

        // One frame queued at most, like RideDaemon/ThinkerRide: a stalled dash socket produces
        // rejected offers (which VideoBackpressureGuard understands) rather than an unbounded
        // backlog of stale video.
        private val frameExecutor = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(1)
        ) { runnable -> Thread(runnable, "MotoHubYunmoVideo").apply { isDaemon = true } }

        fun connect(link: TBoxLink, host: TBoxHost) {
            val created = link.createSocket()
            created.connect(InetSocketAddress(host.ipAddress, host.port), CONNECT_TIMEOUT_MS)
            created.tcpNoDelay = true
            created.soTimeout = 0
            socket = created
            output = BufferedOutputStream(created.getOutputStream())
            running.set(true)
            phase("TCP connected to ${host.ipAddress}:${host.port}")
        }

        fun startReceiving() {
            val input = BufferedInputStream(socket!!.getInputStream())
            receiveThread = Thread({ receiveLoop(input) }, "MotoHubYunmoRecv").apply {
                isDaemon = true
            }.also { it.start() }
        }

        /** Sends the size query and waits up to [DIM_TIMEOUT_MS] for the dash's reply. */
        fun sendDimQuery() {
            write(YunmoProtocol.dimQueryFrame())
            phase("dim-query sent (B0 payload 01 00 01)")
        }

        /**
         * Waits for the dash's canvas reply, which is sent AFTER the mode request rather than in
         * answer to the query alone — see the ordering note on [start].
         */
        fun awaitDimensions(): YunmoProtocol.DimensionReport? {
            val deadline = System.currentTimeMillis() + DIM_TIMEOUT_MS
            synchronized(dimLock) {
                while (running.get() && pendingDim == null) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    try {
                        dimLock.wait(minOf(remaining, 100))
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
            return pendingDim
        }

        /**
         * Enters the negotiated display mode, re-sending the request until the dash confirms.
         *
         * The OEM app sends this command through the *synchronous* form of its own send helper
         * (`Trans_Ins(160, {6}, 1)` — the three-argument overload sets its `z` flag), which waits
         * for the dash's reply and re-sends up to three times if none arrives. The dash answers
         * ours around two seconds later, which is the same order as that timeout, so the OEM very
         * likely sends this command more than once in practice. Sending it once and moving on was
         * a difference from the OEM we could see in its source, so it is worth removing.
         */
        fun beginMode() {
            if (!mapNavMode) {
                YunmoProtocol.startMirrorFrames().forEach { write(it) }
                phase("mirror requested (B0/A0 cmd=7)")
                return
            }
            repeat(MODE_REQUEST_ATTEMPTS) { attempt ->
                if (mapNavConfirmed || !running.get()) return@repeat
                write(YunmoProtocol.mapNaviFrame())
                phase("map-nav requested (A0 cmd=6), attempt ${attempt + 1}/$MODE_REQUEST_ATTEMPTS")
                awaitMapNav()
            }
            if (!mapNavConfirmed) {
                // The counter is normally reset by the confirmation itself; do it here only
                // when none arrived, so a silent dash still starts from a known state.
                frameId.set(0)
                unackedSinceAck = 0
                phase(
                    "map-nav not confirmed after $MODE_REQUEST_ATTEMPTS attempts - streaming " +
                        "anyway. If the TFT stays black, try the mirror profile variant instead."
                )
            }
        }

        private fun awaitMapNav() {
            val deadline = System.currentTimeMillis() + MAP_NAV_TIMEOUT_MS
            synchronized(ackLock) {
                while (running.get() && !mapNavConfirmed) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    try {
                        ackLock.wait(minOf(remaining, 100))
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }

        fun offerAccessUnit(avcc: ByteArray): Boolean {
            if (!running.get()) return false
            return try {
                frameExecutor.execute {
                    try {
                        sendAccessUnit(avcc)
                    } catch (failure: IOException) {
                        reportFatal("The dashboard video connection dropped: ${failure.message}")
                    }
                }
                true
            } catch (_: RejectedExecutionException) {
                // A frame is already queued behind a slow socket; drop this one instead of
                // building latency. VideoBackpressureGuard turns a persistent streak into a stop.
                false
            }
        }

        private fun sendAccessUnit(avcc: ByteArray) {
            // The dash paints its own arrows in this state and drops whatever we push. Skipping
            // here rather than blocking keeps the encoder draining, so the resume is immediate.
            if (simpleNaviActive) return

            val annexB = YunmoProtocol.annexBFromAvcc(avcc)
            val nals = YunmoProtocol.splitAnnexB(annexB)
            val hasIdr = nals.any { it.type == YunmoProtocol.NAL_IDR }
            val sps = nals.firstOrNull { it.type == YunmoProtocol.NAL_SPS }
            val pps = nals.firstOrNull { it.type == YunmoProtocol.NAL_PPS }
            if (sps != null || pps != null) {
                phaseOnce("codec-config") {
                    "first codec config out (" +
                        nals.joinToString("+") { YunmoProtocol.nalName(it.type) } + ")"
                }
            }
            if (hasIdr) phaseOnce("idr") { "first IDR out (${annexB.size}b)" }

            // Map-nav's OEM path wants the parameter sets split out ahead of each keyframe:
            // SPS alone, PPS alone, then the bare coded picture — three separate frames.
            if (mapNavMode && hasIdr) {
                if (sps != null && pps != null) {
                    // One picture, one window slot. Reserving per wire frame meant a single
                    // keyframe filled the three-frame window by itself, so every access unit after
                    // the first blocked for the full send-window timeout while the encoder kept
                    // producing - the "T-Box rejected an Android Auto frame" flood in the field log
                    // of 2026-08-10, 67 rejections in ten seconds with the picture never arriving.
                    if (!reserveSendSlot() || !running.get()) return
                    writeFrame(YunmoProtocol.toAnnexBFrame(sps))
                    writeFrame(YunmoProtocol.toAnnexBFrame(pps))
                    writeFrame(YunmoProtocol.stripLeadingSpsPps(annexB))
                    sentParameterSets = true
                    return
                }
                // A keyframe with no parameter sets in front of it is undecodable to a dash that
                // just restarted. Nothing to prepend, so ask the encoder for a fresh configured
                // keyframe and drop this one rather than sending a picture that cannot be shown.
                if (!sentParameterSets) return
            }
            sendFrame(annexB)
        }

        /** Reserves a window slot, then writes one wire frame. */
        private fun sendFrame(accessUnit: ByteArray) {
            if (!reserveSendSlot() || !running.get()) return
            writeFrame(accessUnit)
        }

        /**
         * Waits for room in the send window and takes one slot, so the window counts *pictures* in
         * flight rather than wire frames. The split path writes three frames for one picture and
         * would otherwise fill a three-slot window on its own.
         */
        private fun reserveSendSlot(): Boolean {
            if (!waitForSendWindow()) return false
            if (mapNavMode) unackedSinceAck++
            return true
        }

        /**
         * Writes one wire frame with no flow control of its own. Callers that emit several frames
         * for a single picture (the OEM split path) reserve the window once around the set.
         */
        private fun writeFrame(accessUnit: ByteArray) {
            val id = frameId.getAndIncrement()
            // Fixed media type 2, metadata left zero — the exact header the OEM app writes.
            val frame = YunmoProtocol.encodeH264Ex(accessUnit, canvasWidth, canvasHeight, id)
            write(frame)
        }

        /** Blocks until fewer than [YunmoProtocol.SEND_WINDOW] frames are unacked, or 2s passes. */
        private fun waitForSendWindow(): Boolean {
            val deadline = System.currentTimeMillis() + SEND_WINDOW_TIMEOUT_MS
            synchronized(ackLock) {
                while (running.get()) {
                    val inFlight = if (mapNavMode) {
                        unackedSinceAck
                    } else {
                        frameId.get() - maxOf(lastAckedId, 0)
                    }
                    if (inFlight < YunmoProtocol.SEND_WINDOW) return true
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) return true // send anyway rather than stall the encoder
                    try {
                        ackLock.wait(minOf(remaining, 200))
                    } catch (_: InterruptedException) {
                        return running.get()
                    }
                }
            }
            return false
        }

        private fun receiveLoop(input: BufferedInputStream) {
            while (running.get()) {
                val frame = try {
                    YunmoProtocol.readSimpleFrame(input)
                } catch (_: Exception) {
                    null
                }
                if (frame == null) {
                    if (running.get()) reportFatal("The dashboard closed the Yunmo connection.")
                    return
                }
                when (frame.command) {
                    YunmoProtocol.CMD_OK_A, YunmoProtocol.CMD_OK_B -> {
                        val dimension = YunmoProtocol.parseOkDimension(frame.payload)
                        if (dimension != null && pendingDim == null) {
                            pendingDim = dimension
                            phase("dash reported ${dimension.reportedWidth}x${dimension.reportedHeight}")
                            synchronized(dimLock) { dimLock.notifyAll() }
                        } else if (frame.command == YunmoProtocol.CMD_OK_B && mapNavMode) {
                            // THIS is the map-nav confirmation, and we spent days looking for it in
                            // the wrong shape. The OEM app's listener keys on the command byte alone
                            // (`i == 51`, GoogleMediaCodecH264LiveThread) and answers it by entering
                            // map-nav and calling ResetFrameID; it never waits for an inbound A0{6},
                            // which is what this transport used to look for and never saw. The dash
                            // had been answering all along, ~2s after the request and well inside
                            // our own timeout, under a command we were logging and discarding.
                            //
                            // The frame counter resets HERE rather than when the request was sent:
                            // the OEM resets on the dash's confirmation, so the first frame the dash
                            // sees numbered zero is the first one it was actually ready for.
                            if (!mapNavConfirmed) {
                                mapNavConfirmed = true
                                frameId.set(0)
                                unackedSinceAck = 0
                                phase("dash confirmed map-nav (0x33) - frame counter reset")
                            }
                            synchronized(ackLock) { ackLock.notifyAll() }
                        } else {
                            logUnrecognised(frame, "OK frame that is not a canvas report")
                        }
                    }
                    YunmoProtocol.CMD_DISPLAY -> {
                        if (YunmoProtocol.isMapNaviConfirm(frame.payload)) {
                            // Also the way back from SimpleNavi, so re-prime: the dash restarts its
                            // decoder on the switch and will not paint until it sees a keyframe with
                            // parameter sets in front of it.
                            val resuming = simpleNaviActive
                            simpleNaviActive = false
                            mapNavConfirmed = true
                            frameId.set(0)
                            unackedSinceAck = 0
                            if (resuming) {
                                sentParameterSets = false
                                phase("dash returned to full-screen map - resuming video")
                            }
                            synchronized(ackLock) { ackLock.notifyAll() }
                        } else if (YunmoProtocol.isSimpleNaviSwitch(frame.payload)) {
                            // The dash draws its own turn arrows in this mode and paints nothing we
                            // push, so hold the encoder off rather than burning the link on frames
                            // that are discarded. It sends cmd=6 when the rider switches back.
                            if (!simpleNaviActive) {
                                simpleNaviActive = true
                                phase("dash switched to its own arrow guidance - video paused")
                            }
                            synchronized(ackLock) { ackLock.notifyAll() }
                        } else {
                            val acked = YunmoProtocol.parseAck(frame.payload)
                            if (acked != null) {
                                lastAckedId = acked
                                unackedSinceAck = 0
                                phaseOnce("ack") { "first media ack from dash (frameId=$acked)" }
                                // Whether acks keep coming is the difference between "the dash is
                                // consuming video" and "the dash acked once and went quiet", and
                                // the first-ack line alone cannot tell them apart.
                                if (++acksSeen % ACK_LOG_INTERVAL == 0) {
                                    phase("media acks: $acksSeen so far (latest frameId=$acked)")
                                }
                                synchronized(ackLock) { ackLock.notifyAll() }
                            } else {
                                logUnrecognised(frame, "display frame that is neither a mode nor an ack")
                            }
                        }
                    }
                    else -> logUnrecognised(frame, "unknown command")
                }
            }
        }

        /**
         * Records a control frame this implementation does not act on, once per (command, first
         * byte) shape so a dash that repeats one cannot flood the log.
         *
         * This exists because silence here is indistinguishable from the dash saying nothing at
         * all. A capture of the OEM app shows it receiving `A0` frames whose first payload byte is
         * `8` — a shape neither this transport nor the reference implementation recognises, both
         * of which drop it without a trace, and both of which fail to paint. Whatever those frames
         * are, the next field log should be able to say so rather than leave it to be inferred.
         */
        private fun logUnrecognised(frame: YunmoProtocol.SimpleFrame, why: String) {
            val shape = "unhandled-${frame.command}-${frame.payload.firstOrNull() ?: -1}"
            phaseOnce(shape) {
                "dash sent an unhandled frame ($why): cmd=0x${frame.command.toString(16)} " +
                    "len=${frame.payload.size} payload=[${YunmoProtocol.hex(frame.payload)}]"
            }
        }

        fun sendStop() {
            if (!running.get()) return
            YunmoProtocol.stopFrames().forEach { runCatching { write(it) } }
        }

        private fun write(data: ByteArray) {
            val out = output ?: return
            synchronized(out) {
                out.write(data)
                out.flush()
            }
        }

        fun close() {
            running.set(false)
            synchronized(ackLock) { ackLock.notifyAll() }
            synchronized(dimLock) { dimLock.notifyAll() }
            receiveThread?.interrupt()
            frameExecutor.shutdownNow()
            runCatching { socket?.close() }
            socket = null
            output = null
        }
    }

    private companion object {
        const val YUNMO_PACKAGE_TAG = "yunmo"
        const val CONNECT_TIMEOUT_MS = 5_000

        /** Short on purpose: this runs after EasyConn discovery has already spent its budget. */
        const val PROBE_CONNECT_TIMEOUT_MS = 1_500
        const val PROBE_READ_TIMEOUT_MS = 1_500

        const val DIM_TIMEOUT_MS = 5_000L
        const val MAP_NAV_TIMEOUT_MS = 2_500L

        /** Matches the OEM's own retry count for the synchronous form of this command. */
        const val MODE_REQUEST_ATTEMPTS = 3
        const val SEND_WINDOW_TIMEOUT_MS = 2_000L

        /** Roughly every few seconds at this dash's frame rate; enough to show acks still flow. */
        const val ACK_LOG_INTERVAL = 60
    }
}
