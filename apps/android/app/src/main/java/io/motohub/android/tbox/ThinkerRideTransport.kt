package io.motohub.android.tbox

import android.content.Context
import android.net.ConnectivityManager
import io.motohub.android.session.ProjectionEventLog
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ThinkerRide projection transport (KOVE-family dashboards). The roles are the inverse of
 * EasyConn: after [ThinkerRideBleLink] completes the BLE handshake, the *dash* connects to three
 * TCP servers the phone opens on the shared Wi-Fi network — control (17818), keep-alive (15457)
 * and video (15456). See [ThinkerRideProtocol] for the wire format and refs/KoveMirror for the
 * decoded reference implementation.
 *
 * The [TBoxTransport] lifecycle maps as: [discover] = bind the servers, pair over BLE and wait
 * for the dash's control connection (its "I can reach your phone" proof); [start] = tell the
 * dash over BLE to begin mirroring and wait for its video connection. The dash never reports a
 * panel size, so [start] emits the configured profile's geometry as the session's video area —
 * that is what makes other ThinkerRide models with other TFT resolutions a profile entry, not a
 * code change.
 *
 * A re-[discover] while the session is still healthy reuses it instead of tearing it down
 * (KoveMirror 9163284 keeps its TCP listeners alive across retries for the same reason): the
 * dash opens the video connection up to ~25s after mirror-start, and destroying the servers on
 * a retry leaves it mirroring into a socket nobody owns — a black TFT until power-cycle. The
 * video accept path is late-tolerant for the same scenario: a connection that arrives after
 * [start] gave up, or a dash that drops and reopens the channel, is wired straight into the
 * running session.
 */
class ThinkerRideTransport(context: Context) : TBoxTransport {

    private val appContext = context.applicationContext
    private val mutableEvents = MutableSharedFlow<TBoxEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val events: Flow<TBoxEvent> = mutableEvents.asSharedFlow()

    @Volatile
    private var protocolProfile: TBoxModelProfile? = null

    @Volatile
    private var session: Session? = null
    private val sessionLock = Any()

    override fun configureProtocolProfile(profile: TBoxModelProfile) {
        protocolProfile = profile
    }

    override suspend fun discover(link: TBoxLink, expectedModelId: String?): Result<TBoxHost> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!ThinkerRideGate.hasBlePermissions(appContext)) {
                    error(ThinkerRideGate.missingPermissionMessage("MOTO-HUB"))
                }
                reusableSession(link)?.let { alive ->
                    ProjectionEventLog.record(
                        "THINKERRIDE",
                        "Re-discovery found the dash session still healthy; reusing it instead " +
                            "of rescanning (servers and BLE link stay up)."
                    )
                    return@runCatching TBoxHost(
                        ipAddress = alive.localAddress(),
                        port = ThinkerRideProtocol.VIDEO_PORT,
                        packageName = ThinkerRideProtocol.PACKAGE_TAG
                    )
                }
                teardownSession()
                val created = Session(link)
                synchronized(sessionLock) { session = created }
                try {
                    created.bindServers()
                    created.startAccepting()
                    val deviceName = created.ble.connect(BLE_SCAN_TIMEOUT_MS).getOrThrow()
                    ProjectionEventLog.record(
                        "THINKERRIDE",
                        "Bluetooth handshake running with \"$deviceName\"; waiting for the dash " +
                            "to open the control connection on port ${ThinkerRideProtocol.CONTROL_PORT}."
                    )
                    val controlArrived =
                        withTimeoutOrNull(CONTROL_CONNECT_TIMEOUT_MS) { created.controlConnected.await() }
                    if (controlArrived == null) {
                        error(
                            "The dashboard paired over Bluetooth but never connected to this " +
                                "phone. Make sure the phone is on the dashboard's Wi-Fi network " +
                                "(scan the QR on the dash again if unsure), then retry."
                        )
                    }
                    TBoxHost(
                        ipAddress = created.localAddress(),
                        port = ThinkerRideProtocol.VIDEO_PORT,
                        packageName = ThinkerRideProtocol.PACKAGE_TAG
                    )
                } catch (failure: Throwable) {
                    teardownSession()
                    throw failure
                }
            }
        }

    override suspend fun start(host: TBoxHost): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val active = session ?: error("ThinkerRide session is not established; discover first.")
            val area = protocolProfile?.fallbackTBoxVideoArea
                ?: TBoxEvent.VideoArea(
                    ThinkerRideProtocol.DEFAULT_VIDEO_WIDTH,
                    ThinkerRideProtocol.DEFAULT_VIDEO_HEIGHT
                )
            // The dash only acts on a mirror-start once its own pairing handshake has landed, so
            // never fire one into a half-finished BLE session — that is what the AA retry path
            // used to do, 8 ms after re-opening the link.
            if (!active.ble.awaitPairConfirmation(PAIR_CONFIRM_TIMEOUT_MS)) {
                ProjectionEventLog.record(
                    "THINKERRIDE",
                    "The dashboard has not confirmed Bluetooth pairing after " +
                        "${PAIR_CONFIRM_TIMEOUT_MS}ms; sending mirror-start anyway."
                )
            }
            // Pairing was confirmed while the video pipeline was still coming up, so by now it
            // is seconds old. KoveMirror mirror-starts straight out of the confirmation, and a
            // dash that mirrors under KoveMirror ignored our later one, so re-pair and ride the
            // fresh confirmation. Best effort: if the dash stays quiet we send mirror-start
            // anyway, exactly as before.
            if (active.ble.repairAndAwaitConfirmation(REPAIR_CONFIRM_TIMEOUT_MS)) {
                ProjectionEventLog.record(
                    "THINKERRIDE",
                    "Dashboard re-confirmed pairing; sending mirror-start on the fresh handshake."
                )
            } else {
                ProjectionEventLog.record(
                    "THINKERRIDE",
                    "No fresh pairing confirmation within ${REPAIR_CONFIRM_TIMEOUT_MS}ms; " +
                        "sending mirror-start on the existing session."
                )
            }
            active.mirrorArea = area
            active.ble.sendMirrorStatus(true)
            ProjectionEventLog.record(
                "THINKERRIDE",
                "Mirror-start sent over Bluetooth; waiting for the dash video connection " +
                    "(stream ${area.width}x${area.height})."
            )
            val video = withTimeoutOrNull(VIDEO_CONNECT_TIMEOUT_MS) { active.videoConnected.await() }
                ?: error(
                    "The dashboard acknowledged the session but never opened the video " +
                        "connection. Power-cycle the dash screen and connect again."
                )
            // The dash may have dropped and reopened the channel since the first accept; the
            // newest socket is the live one, the deferred only remembers the first.
            active.beginVideo(active.latestVideoSocket ?: video, area)
            mutableEvents.tryEmit(TBoxEvent.VideoArea(area.width, area.height, isFallback = false))
            mutableEvents.tryEmit(TBoxEvent.VideoStreamStart)
            Unit
        }
    }

    override fun offerAccessUnit(avcc: ByteArray): Boolean {
        val active = session ?: return false
        return active.offerAccessUnit(avcc)
    }

    override suspend fun stop() {
        val active = session ?: return
        // Tell the dash the mirror ended so its UI returns to the stock dashboard, and let the
        // write queue actually drain before the link is torn down under it.
        runCatching { active.ble.sendMirrorStatus(false) }
        runCatching { active.ble.awaitWritesDrained(BLE_DRAIN_TIMEOUT_MS) }
        teardownSession()
        mutableEvents.tryEmit(TBoxEvent.Stopped)
    }

    /**
     * The current session, when it can serve another [discover] as-is: same network, BLE link
     * never lost, and the dash has already proven it can reach the control server. Anything less
     * gets the full teardown-and-rescan.
     */
    private fun reusableSession(link: TBoxLink): Session? {
        val active = synchronized(sessionLock) { session } ?: return null
        val healthy = !active.closed.get() &&
            !active.fatalReported.get() &&
            active.controlConnected.isCompleted &&
            active.link.network == link.network
        return if (healthy) active else null
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
            ProjectionEventLog.error("THINKERRIDE", message)
            mutableEvents.tryEmit(TBoxEvent.FatalError(message))
        }
    }

    /** Everything owned by one dash connection, torn down as a unit. */
    private inner class Session(val link: TBoxLink) {
        val closed = AtomicBoolean(false)
        val fatalReported = AtomicBoolean(false)
        val controlConnected = CompletableDeferred<Socket>()
        val videoConnected = CompletableDeferred<Socket>()

        /** Set by [start] just before mirror-start; a reopened video connection needs it. */
        @Volatile
        var mirrorArea: TBoxEvent.VideoArea? = null

        /** The most recent accepted video socket; [videoConnected] only remembers the first. */
        @Volatile
        var latestVideoSocket: Socket? = null

        val ble = ThinkerRideBleLink(
            appContext,
            log = { message -> ProjectionEventLog.record("THINKERRIDE", message) },
            onLinkLost = { reason -> reportFatal(reason) }
        )

        private var controlServer: ServerSocket? = null
        private var heartbeatServer: ServerSocket? = null
        private var videoServer: ServerSocket? = null
        private val liveSockets = java.util.concurrent.CopyOnWriteArrayList<Socket>()

        private val pulseScheduler = ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "MotoHubThinkerRidePulse").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
        private var heartbeatPulse: ScheduledFuture<*>? = null
        private var videoKeepalive: ScheduledFuture<*>? = null

        // Same shape as RideDaemonTransport's pushFrameExecutor: one frame queued at most, so a
        // stalled dash socket produces rejected offers (which VideoBackpressureGuard understands)
        // instead of an unbounded backlog of stale video.
        private val frameExecutor = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(1)
        ) { runnable -> Thread(runnable, "MotoHubThinkerRideVideo").apply { isDaemon = true } }

        @Volatile
        private var videoOut: OutputStream? = null

        @Volatile
        private var videoSocket: Socket? = null

        fun bindServers() {
            val busy = mutableListOf<Int>()
            controlServer = bindOrNull(ThinkerRideProtocol.CONTROL_PORT) ?: run { busy += ThinkerRideProtocol.CONTROL_PORT; null }
            heartbeatServer = bindOrNull(ThinkerRideProtocol.HEARTBEAT_PORT) ?: run { busy += ThinkerRideProtocol.HEARTBEAT_PORT; null }
            videoServer = bindOrNull(ThinkerRideProtocol.VIDEO_PORT) ?: run { busy += ThinkerRideProtocol.VIDEO_PORT; null }
            if (busy.isNotEmpty()) {
                error(
                    "Ports ${busy.joinToString()} are already in use on this phone, so the " +
                        "dashboard cannot connect. Close the other dashboard/mirroring app and retry."
                )
            }
        }

        private fun bindOrNull(port: Int): ServerSocket? = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port), 1)
            }
        }.getOrNull()

        fun startAccepting() {
            acceptLoop("control", controlServer) { socket -> runControl(socket) }
            acceptLoop("heartbeat", heartbeatServer) { socket -> runHeartbeat(socket) }
            acceptLoop("video", videoServer) { socket ->
                socket.tcpNoDelay = true
                // Only a dash that was told to mirror can be opening this; anything else is
                // something on this phone probing the port. The network diagnostics screen did
                // exactly that (it dials the registry's host:15456, which on this wire is *us*),
                // and without this guard that probe was accepted as the dash and the real
                // connection had nowhere to land.
                if (mirrorArea == null) {
                    ProjectionEventLog.record(
                        "THINKERRIDE",
                        "Ignoring a video connection that arrived before any mirror-start; " +
                            "the dash was never asked to mirror."
                    )
                    runCatching { socket.close() }
                    return@acceptLoop
                }
                latestVideoSocket = socket
                if (!videoConnected.complete(socket)) {
                    // The dash reopened the channel — or connected after start() stopped
                    // waiting. Wire the running session onto the new socket instead of
                    // leaving the dash mirroring into nothing.
                    mirrorArea?.let { area -> beginVideo(socket, area) }
                }
            }
        }

        private fun acceptLoop(label: String, server: ServerSocket?, onAccepted: (Socket) -> Unit) {
            val listening = server ?: return
            Thread({
                while (!closed.get()) {
                    val socket = runCatching { listening.accept() }.getOrNull() ?: break
                    liveSockets += socket
                    ProjectionEventLog.record(
                        "THINKERRIDE",
                        "Dash connected to the $label channel from ${socket.inetAddress?.hostAddress}."
                    )
                    runCatching { onAccepted(socket) }
                }
            }, "MotoHubThinkerRideAccept-$label").apply { isDaemon = true }.start()
        }

        private fun runControl(socket: Socket) {
            if (!controlConnected.isCompleted) controlConnected.complete(socket)
            val out = socket.getOutputStream()
            writeQuietly(out, ThinkerRideProtocol.controlOpeningQuery())
            Thread({
                val buffer = ByteArray(4096)
                var handshakeSent = false
                val input = runCatching { socket.getInputStream() }.getOrNull() ?: return@Thread
                while (!closed.get()) {
                    val read = runCatching { input.read(buffer) }.getOrDefault(-1)
                    if (read <= 0) break
                    logControlPayload(buffer, read)
                    if (ThinkerRideProtocol.isKeepaliveProbe(buffer, read)) {
                        writeQuietly(out, ThinkerRideProtocol.KEEPALIVE_PACKET)
                    }
                    if (!handshakeSent) {
                        handshakeSent = true
                        // The reference implementation waits 100 ms after the dash's first
                        // packet before the binary handshake; firmware drops it otherwise.
                        pulseScheduler.schedule({
                            writeQuietly(out, ThinkerRideProtocol.controlHandshake())
                            ThinkerRideProtocol.controlNaviQueries().forEach { writeQuietly(out, it) }
                        }, 100, TimeUnit.MILLISECONDS)
                    }
                }
            }, "MotoHubThinkerRideControl").apply { isDaemon = true }.start()
        }

        private fun runHeartbeat(socket: Socket) {
            val out = socket.getOutputStream()
            heartbeatPulse?.cancel(false)
            heartbeatPulse = pulseScheduler.scheduleWithFixedDelay(
                { writeQuietly(out, ThinkerRideProtocol.KEEPALIVE_PACKET) },
                0,
                ThinkerRideProtocol.HEARTBEAT_PULSE_INTERVAL_MS,
                TimeUnit.MILLISECONDS
            )
        }

        // Reached from both the accept thread (dash reopened the channel) and start().
        @Synchronized
        fun beginVideo(socket: Socket, area: TBoxEvent.VideoArea) {
            if (videoSocket === socket) return
            videoSocket?.let { previous -> runCatching { previous.close() } }
            val out = socket.getOutputStream()
            out.write(ThinkerRideProtocol.videoSizeHeader(area.width, area.height))
            out.flush()
            videoSocket = socket
            videoOut = out
            videoKeepalive?.cancel(false)
            videoKeepalive = pulseScheduler.scheduleWithFixedDelay(
                { writeQuietly(out, ThinkerRideProtocol.KEEPALIVE_PACKET) },
                ThinkerRideProtocol.VIDEO_KEEPALIVE_INTERVAL_MS,
                ThinkerRideProtocol.VIDEO_KEEPALIVE_INTERVAL_MS,
                TimeUnit.MILLISECONDS
            )
        }

        fun offerAccessUnit(avcc: ByteArray): Boolean {
            val out = videoOut ?: return false
            return try {
                frameExecutor.execute {
                    try {
                        synchronized(out) {
                            out.write(ThinkerRideProtocol.annexBFromAvcc(avcc))
                            out.flush()
                        }
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

        fun localAddress(): String {
            (link.network)?.let { network ->
                val properties = appContext.getSystemService(ConnectivityManager::class.java)
                    ?.getLinkProperties(network)
                properties?.linkAddresses
                    ?.map { it.address }
                    ?.filterIsInstance<Inet4Address>()
                    ?.firstOrNull()
                    ?.hostAddress
                    ?.let { return it }
            }
            return runCatching {
                NetworkInterface.getNetworkInterfaces().toList()
                    .asSequence()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.asSequence() }
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { it.isSiteLocalAddress }
                    ?.hostAddress
            }.getOrNull() ?: "0.0.0.0"
        }

        private fun writeQuietly(out: OutputStream, data: ByteArray) {
            try {
                synchronized(out) {
                    out.write(data)
                    out.flush()
                }
            } catch (_: IOException) {
                // Channel-level drops are surfaced by the video path / watchdogs, not here.
            }
        }

        private fun logControlPayload(buffer: ByteArray, length: Int) {
            // The control channel is where an unknown ThinkerRide model would identify itself,
            // so keep whatever readable content arrives; a future profile is written from these
            // lines the same way EasyConn profiles are written from CLIENT_INFO logs.
            val text = String(buffer, 0, length, StandardCharsets.UTF_8)
            val printable = text.count { it.code in 32..126 }
            if (printable >= length / 2 && text.isNotBlank()) {
                ProjectionEventLog.record("THINKERRIDE", "Dash control payload: ${text.trim()}")
            }
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            heartbeatPulse?.cancel(false)
            videoKeepalive?.cancel(false)
            pulseScheduler.shutdownNow()
            frameExecutor.shutdownNow()
            ble.close()
            videoOut = null
            videoSocket = null
            liveSockets.forEach { runCatching { it.close() } }
            liveSockets.clear()
            runCatching { controlServer?.close() }
            runCatching { heartbeatServer?.close() }
            runCatching { videoServer?.close() }
        }
    }

    private companion object {
        const val BLE_SCAN_TIMEOUT_MS = 20_000L
        const val CONTROL_CONNECT_TIMEOUT_MS = 20_000L

        /**
         * A real KOVE 800X was logged opening the video connection 12.0s and 25.3s after
         * mirror-start (2026-08-13 tester diagnostics), so 15s lost the race half the time.
         */
        const val VIDEO_CONNECT_TIMEOUT_MS = 40_000L

        /** How long [start] waits for `send_pairresult` before going ahead regardless. */
        const val PAIR_CONFIRM_TIMEOUT_MS = 6_000L

        /**
         * How long the re-pair right before mirror-start waits. Short on purpose: the dash
         * answered the first handshake in ~4ms, and every millisecond here is added latency on
         * dashes that never needed the re-pair.
         */
        const val REPAIR_CONFIRM_TIMEOUT_MS = 1_500L

        /** How long [stop] gives the mirror-stop packets to leave the phone. */
        const val BLE_DRAIN_TIMEOUT_MS = 1_500L
    }
}
