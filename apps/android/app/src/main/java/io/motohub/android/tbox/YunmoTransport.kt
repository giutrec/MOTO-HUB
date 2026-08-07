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
 * The dash reports HALF its true canvas, so the emitted area is the report doubled (see
 * [YunmoProtocol.encodeCanvas]); when the dash never answers the size query, the profile's
 * [TBoxModelProfile.fallbackTBoxVideoArea] backstops it. Frame metadata is deliberately kept off
 * the wire ([YunmoProtocol.encodeH264Ex] `omitMeta`), matching the only build known to render.
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

    override suspend fun start(host: TBoxHost): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val activeLink = link ?: error("Yunmo link is not established; discover first.")
            val profile = protocolProfile
            val fallback = profile?.fallbackTBoxVideoArea ?: TBoxEvent.VideoArea(800, 480)
            val mapNav = profile?.yunmoMapNavExperiment == true

            teardownSession()
            val created = Session(mapNav)
            synchronized(sessionLock) { session = created }
            try {
                created.connect(activeLink, host)
                created.startReceiving()

                val report = created.handshakeDimensions()
                val (canvasW, canvasH) = YunmoProtocol.encodeCanvas(report, fallback.width, fallback.height)
                created.canvasWidth = canvasW
                created.canvasHeight = canvasH
                ProjectionEventLog.record(
                    "YUNMO",
                    if (report != null) {
                        "Dash canvas reported ${report.reportedWidth}x${report.reportedHeight}; " +
                            "encoding ${canvasW}x$canvasH (maps x2)."
                    } else {
                        "No dim response — encoding fallback ${canvasW}x$canvasH."
                    }
                )

                created.beginMode()
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
        fun handshakeDimensions(): YunmoProtocol.DimensionReport? {
            write(YunmoProtocol.dimQueryFrame())
            phase("dim-query sent (B0 payload 01 00 01)")
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
         * Enters the negotiated display mode. In map-nav the dash's state-6 echo is a hard gate,
         * not a hint: an owner's capture of the OEM app (2026-08-07) showed the TFT only paints a
         * full-screen map when the rider selected that presentation in the dash menu *before*
         * navigation started, and that neither a live socket, an ack, nor a created display
         * proves the dash is in that state. Streaming without the echo is how a session ends up
         * pushing frames into a dash that will never paint them, which is exactly the black-TFT
         * result earlier implementations reported.
         */
        fun beginMode() {
            if (mapNavMode) {
                write(YunmoProtocol.mapNaviFrame())
                phase("map-nav requested (A0 cmd=6)")
                awaitMapNav()
                if (!mapNavConfirmed) {
                    error(
                        "The dashboard did not confirm full-screen map mode. On the motorcycle " +
                            "TFT menu select the full-screen map navigation view (not the compact " +
                            "arrow guidance), then connect again. The dash only accepts the map " +
                            "canvas when that view is selected before the session starts."
                    )
                }
                frameId.set(0)
                unackedSinceAck = 0
                phase("map-nav confirmed by dash")
            } else {
                YunmoProtocol.startMirrorFrames().forEach { write(it) }
                phase("mirror requested (B0/A0 cmd=7)")
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
            if (!mapNavConfirmed) {
                phase("map-nav NOT echoed within ${MAP_NAV_TIMEOUT_MS}ms")
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
            val annexB = YunmoProtocol.annexBFromAvcc(avcc)
            val nals = YunmoProtocol.splitAnnexB(annexB)
            val hasIdr = nals.any { it.type == YunmoProtocol.NAL_IDR }
            if (nals.any { it.type == YunmoProtocol.NAL_SPS || it.type == YunmoProtocol.NAL_PPS }) {
                phaseOnce("codec-config") {
                    "first codec config out (" +
                        nals.joinToString("+") { YunmoProtocol.nalName(it.type) } + ")"
                }
            }
            if (hasIdr) phaseOnce("idr") { "first IDR out (${annexB.size}b)" }

            // Map-nav's OEM path wants the parameter sets split out ahead of each keyframe:
            // SPS alone, PPS alone, then the bare coded picture — three separate frames.
            if (mapNavMode && hasIdr) {
                val sps = nals.firstOrNull { it.type == YunmoProtocol.NAL_SPS }
                val pps = nals.firstOrNull { it.type == YunmoProtocol.NAL_PPS }
                if (sps != null && pps != null) {
                    sendFrame(YunmoProtocol.toAnnexBFrame(sps))
                    sendFrame(YunmoProtocol.toAnnexBFrame(pps))
                    sendFrame(YunmoProtocol.stripLeadingSpsPps(annexB))
                    return
                }
            }
            sendFrame(annexB)
        }

        private fun sendFrame(accessUnit: ByteArray) {
            if (!waitForSendWindow() || !running.get()) return
            val id = frameId.getAndIncrement()
            val frame = YunmoProtocol.encodeH264Ex(accessUnit, canvasWidth, canvasHeight, id)
            write(frame)
            if (mapNavMode) unackedSinceAck++
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
                            phase(
                                "dash reported ${dimension.reportedWidth}x${dimension.reportedHeight} " +
                                    "(maps ${dimension.mapsWidth}x${dimension.mapsHeight})"
                            )
                            synchronized(dimLock) { dimLock.notifyAll() }
                        }
                    }
                    YunmoProtocol.CMD_DISPLAY -> {
                        if (YunmoProtocol.isMapNaviConfirm(frame.payload)) {
                            mapNavConfirmed = true
                            frameId.set(0)
                            unackedSinceAck = 0
                            synchronized(ackLock) { ackLock.notifyAll() }
                        } else {
                            YunmoProtocol.parseAck(frame.payload)?.let { acked ->
                                lastAckedId = acked
                                unackedSinceAck = 0
                                phaseOnce("ack") { "first media ack from dash (frameId=$acked)" }
                                synchronized(ackLock) { ackLock.notifyAll() }
                            }
                        }
                    }
                }
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
        const val DIM_TIMEOUT_MS = 5_000L
        const val MAP_NAV_TIMEOUT_MS = 2_500L
        const val SEND_WINDOW_TIMEOUT_MS = 2_000L
    }
}
