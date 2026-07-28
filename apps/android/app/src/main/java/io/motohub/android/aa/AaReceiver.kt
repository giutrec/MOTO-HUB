// MOTO-HUB receiver glue (uses AGPLv3 code ported from headunit-revived). Orchestrates the loopback
// "self-mode" Android Auto Projection receiver:
//   1. Listen on TCP 127.0.0.1:5288 (+ NSD _aawireless._tcp).
//   2. Launch Google Android Auto's WirelessStartupActivity pointed at 127.0.0.1:5288 (no VPN).
//   3. Accept the inbound socket, run the AAP version+SSL handshake, point the H.264 decoder at
//      the supplied encoder Surface, and start the message loop → AA video flows into the encoder.
package io.motohub.android.aa

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.view.Surface
import io.motohub.android.androidauto.AaInputBridge
import io.motohub.android.androidauto.AndroidAutoCapabilityProfile
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import io.motohub.android.androidauto.AndroidAutoNightModeStore

class AaReceiver(
    private val context: Context,
    private val encoderSurface: Surface,
    private val log: (String) -> Unit,
    private val onVideoReady: () -> Unit,
    private val onSessionEnded: (clean: Boolean, userExit: Boolean) -> Unit,
    private val mapTouchToSource: (Int, Int) -> Pair<Int, Int>?,
    private val capabilityProfile: AndroidAutoCapabilityProfile,
) {
    companion object {
        const val PORT = 5288

        /**
         * Android Auto's own "head unit server" (Developer settings ▸ Start head unit server),
         * the port the Desktop Head Unit connects to. Here the roles are reversed from self-mode:
         * Android Auto listens and the head unit dials in, so nothing has to ask Android Auto to
         * start — which is the whole point, since 17.4 removed every way of asking.
         */
        const val HEAD_UNIT_SERVER_PORT = 5277
        private const val HEAD_UNIT_SERVER_POLL_MS = 1_500L
        private const val HEAD_UNIT_SERVER_CONNECT_TIMEOUT_MS = 400

        /**
         * Process-wide "Android Auto has opened the local AAP socket" flag. The self-mode trigger
         * needs it to tell a dispatched intent from one that actually reached Gearhead:
         * sendBroadcast and startService report delivery, never whether anything acted on it.
         */
        @Volatile
        private var androidAutoConnectedSinceStart = false

        fun hasAndroidAutoConnectedSinceStart(): Boolean = androidAutoConnectedSinceStart
    }

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var headUnitServerThread: Thread? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    @Volatile private var transport: AapTransport? = null
    @Volatile private var connection: SocketAccessoryConnection? = null
    @Volatile private var videoReadyFired = false
    /**
     * Whether Google Android Auto ever opened the local AAP socket. False means the self-mode
     * trigger never reached it — a different failure from "connected but sent no video", and the
     * one to report when a newer Android Auto refuses every startup entry point.
     */
    @Volatile private var androidAutoConnected = false
    val hasAndroidAutoConnected: Boolean get() = androidAutoConnected
    @Volatile private var input: AaInput? = null
    private val videoDecoder = VideoDecoder().apply {
        fallbackWidth = capabilityProfile.video.width
        fallbackHeight = capabilityProfile.video.height
        onFirstFrameListener = {
            if (!videoReadyFired) {
                videoReadyFired = true
                log("[AA] first decoded video frame received — signalling ready for bike hand-off")
                try { onVideoReady() } catch (failure: Exception) {
                    log("[AA] bike hand-off callback failed: ${failure.message}")
                }
            }
        }
        onFpsChanged = { fps ->
            log("[AA] decode fps=$fps")
        }
    }

    /** Ensure Conscrypt/AAP logging are wired before anything touches SSL. */
    fun start(): Boolean {
        if (running) { log("[AA] already running"); return true }
        if (!SingleKeyKeyManager.isAvailable(context)) {
            log("[AA] Android Auto identity is not included in this build")
            return false
        }
        running = true
        androidAutoConnected = false
        androidAutoConnectedSinceStart = false
        AaLog.sink = log
        ConscryptInitializer.initialize()

        try {
            serverSocket = ServerSocket(PORT).apply { reuseAddress = true }
            log("[AA] WirelessServer listening on :$PORT")
        } catch (e: Exception) {
            log("[AA] failed to bind :$PORT — ${e.message}")
            running = false
            return false
        }

        registerNsd()

        acceptThread = thread(name = "aa-accept", isDaemon = true) { acceptLoop() }
        headUnitServerThread = thread(name = "aa-hu-server", isDaemon = true) {
            // A fallback poller must never take the process down: anything escaping this thread
            // reaches Android's default handler, which kills the app while the rider is riding.
            try { headUnitServerLoop() } catch (failure: Exception) {
                log("[AA] head unit server poll ended: ${failure.message}")
            }
        }
        // Self-mode (launching Google Android Auto) is triggered by MainActivity from the
        // foreground, via AaSelfMode.trigger(), to satisfy background-activity-launch rules.
        return true
    }

    fun stop() {
        running = false
        AaInputBridge.clear(input)
        input = null
        try { transport?.stop() } catch (_: Exception) {
            try { transport?.quit() } catch (_: Exception) {}
        }
        transport = null
        try { connection?.disconnect() } catch (_: Exception) {}
        connection = null
        try { videoDecoder.stop("AaReceiver.stop") } catch (_: Exception) {}
        unregisterNsd()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptThread?.interrupt(); acceptThread = null
        headUnitServerThread?.interrupt(); headUnitServerThread = null
        AaLog.sink = null
        log("[AA] receiver stopped")
    }

    /**
     * Dials Android Auto's head unit server while nothing has connected yet.
     *
     * Self-mode asks Android Auto to connect to us; 17.4 closed every entry point for that. The
     * head unit server is the mirror image — the rider starts it from Android Auto's own
     * developer menu and Android Auto listens, so a plain outbound socket is all that is needed
     * and no permission or exported component is involved. Everything after the socket is the
     * same AAP session, so this hands over to [handleConnection] unchanged.
     */
    private fun headUnitServerLoop() {
        var announced = false
        while (running) {
            if (transport != null) {
                if (!awaitNextPoll()) return
                continue
            }
            val socket = try {
                Socket().apply {
                    connect(
                        java.net.InetSocketAddress("127.0.0.1", HEAD_UNIT_SERVER_PORT),
                        HEAD_UNIT_SERVER_CONNECT_TIMEOUT_MS
                    )
                }
            } catch (_: Exception) {
                // Not running: this is the normal state until the rider starts it.
                if (!announced) {
                    announced = true
                    log(
                        "[AA] Android Auto's head unit server is not running on " +
                            ":$HEAD_UNIT_SERVER_PORT; polling for it as a fallback."
                    )
                }
                if (!awaitNextPoll()) return
                continue
            }
            if (!running || transport != null) {
                try { socket.close() } catch (_: Exception) {}
                return
            }
            log("[AA] <<< connected to Android Auto's head unit server on :$HEAD_UNIT_SERVER_PORT")
            androidAutoConnected = true
            androidAutoConnectedSinceStart = true
            handleConnection(socket)
            return
        }
    }

    /**
     * Waits one poll interval, reporting whether the wait completed. [stop] interrupts this
     * thread, so an interrupt is the normal way the loop ends — and an InterruptedException left
     * to escape it would reach Android's default handler and kill the process.
     */
    private fun awaitNextPoll(): Boolean = try {
        Thread.sleep(HEAD_UNIT_SERVER_POLL_MS)
        true
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    private fun acceptLoop() {
        val ss = serverSocket ?: return
        while (running) {
            val client = try {
                ss.accept()
            } catch (e: Exception) {
                if (running) log("[AA] accept ended: ${e.message}")
                break
            }
            androidAutoConnected = true
            androidAutoConnectedSinceStart = true
            log("[AA] <<< Android Auto connected from ${client.inetAddress?.hostAddress}")
            if (transport != null) {
                log("[AA] already have a session — dropping extra connection")
                try { client.close() } catch (_: Exception) {}
                continue
            }
            thread(name = "aa-session", isDaemon = true) { handleConnection(client) }
        }
    }

    private fun handleConnection(client: Socket) {
        val conn = SocketAccessoryConnection(client)
        connection = conn
        val t = AapTransport(
            videoDecoder = videoDecoder,
            context = context,
            androidAutoCapabilityProfile = capabilityProfile
        )
        t.nightMode = AndroidAutoNightModeStore(context).load()
        t.onQuit = { clean ->
            val userExit = t.wasUserExit
            log("[AA] transport quit (clean=$clean, userExit=$userExit)")
            AaInputBridge.clear(input)
            input = null
            transport = null
            try { conn.disconnect() } catch (_: Exception) {}
            connection = null
            onSessionEnded(clean, userExit)
        }
       transport = t
        t.microphone = AaMicrophone(context, t, log)

        // Bike touchscreen → Android Auto: EasyConnProber decodes dash touches (PXC cmdType 32) and
        // calls this sink with raw bike-canvas coords + a normalised action. Letterbox-map into AA
        // video space and forward over the AAP INPUT channel. Dropped if the point is in a black bar.
        input = AaInput(t, log)

        log("[AA] starting AAP handshake (version + SSL)…")
        if (!t.startHandshake(conn)) {
            log("[AA] handshake FAILED")
            AaInputBridge.clear(input)
            input = null
            transport = null
            try { conn.disconnect() } catch (_: Exception) {}
            connection = null
            return
        }
        AaInputBridge.install(checkNotNull(input))
        log("[AA] handshake OK — pointing decoder at encoder surface and starting read loop")
        videoDecoder.setSurface(encoderSurface)
        t.startReading()
        log("[AA] read loop started — expecting ServiceDiscovery then video")
    }

    fun sendTouch(action: Int, pointerId: Int, canvasX: Int, canvasY: Int) {
        val activeInput = input ?: return
        val mapped = mapTouchToSource(canvasX, canvasY) ?: return
        if (action != AaInput.ACTION_MOVE) {
            log("[AA] touch action=$action p$pointerId canvas=($canvasX,$canvasY) → AA=(${mapped.first},${mapped.second})")
        }
        activeInput.sendTouch(action, pointerId, mapped.first, mapped.second)
    }

    fun sendTouch(action: Int, canvasX: Int, canvasY: Int) = sendTouch(action, 0, canvasX, canvasY)

    fun sendSourceTouch(action: Int, pointerId: Int, sourceX: Int, sourceY: Int) {
        val activeInput = input ?: return
        activeInput.sendTouch(action, pointerId, sourceX, sourceY)
    }

    fun sendSourceTouch(action: Int, sourceX: Int, sourceY: Int) =
        sendSourceTouch(action, 0, sourceX, sourceY)

    fun setNightMode(isNight: Boolean): Boolean {
        val activeTransport = transport ?: return false
        activeTransport.sendNightMode(isNight)
        return true
    }

    private fun registerNsd() {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            if (nsdManager == null) { log("[AA] NSD unavailable"); return }
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "AAWireless"
                serviceType = "_aawireless._tcp"
                port = PORT
            }
            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) = log("[AA] NSD registered: ${info.serviceName}")
                override fun onRegistrationFailed(info: NsdServiceInfo, err: Int) = log("[AA] NSD reg fail: $err")
                override fun onServiceUnregistered(info: NsdServiceInfo) = log("[AA] NSD unregistered")
                override fun onUnregistrationFailed(info: NsdServiceInfo, err: Int) = log("[AA] NSD unreg fail: $err")
            }
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            log("[AA] NSD register error: ${e.message}")
        }
    }

    private fun unregisterNsd() {
        try { registrationListener?.let { nsdManager?.unregisterService(it) } } catch (_: Exception) {}
        registrationListener = null
    }
}
