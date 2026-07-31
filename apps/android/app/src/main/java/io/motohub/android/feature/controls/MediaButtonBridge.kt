package io.motohub.android.feature.controls

import io.motohub.android.i18n.motoHubText

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import io.motohub.android.R
import io.motohub.android.androidauto.AndroidAutoInputCodes
import io.motohub.android.androidauto.AndroidAutoPreviewRuntime
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

private const val DOUBLE_PRESS_VOLUME_STEPS = 3

/** Converts the motorcycle's Bluetooth AVRCP gestures into active-mode input events. */
class MediaButtonBridge(
    private val context: Context,
    private val log: (String) -> Unit,
    private val targetName: String = TARGET_ANDROID_AUTO,
    private val gestureHandler: ((HandlebarGesture) -> Boolean)? = null
) {
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager by lazy { context.getSystemService(AudioManager::class.java) }
    /** AVRCP "now playing" appearance only — the motorcycle must see a normal media player. */
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    /**
     * What the FOCUS request and the silent track actually use. Navigation-guidance usage ducks
     * other players instead of competing media-vs-media: a media-usage MAY_DUCK request is the
     * weakest possible claim and loses to any real player, which is how the buttons used to die
     * the moment Spotify started. Ported from open-cfmoto's navAttrs.
     */
    private val navAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var session: MediaSession? = null
    private var volumeObserver: ContentObserver? = null
    private var focusRequest: AudioFocusRequest? = null
    private var silentTrack: AudioTrack? = null
    private var pinnedVolume = -1
    private var previousVolume = -1
    private var pendingCapture = false
    @Volatile private var ignoreVolumeChanges = false
    private val volumePoll = object : Runnable {
        override fun run() {
            if (!captureActive) return
            consumeVolumeChange()
            handler.postDelayed(this, VOLUME_POLL_INTERVAL_MILLIS)
        }
    }

    @Volatile var captureActive: Boolean = false
        private set

    fun start() {
        handler.post {
            if (session != null) return@post
            try {
                session = MediaSession(context, "MOTO-HUB handlebar controls").apply {
                    setCallback(callback)
                    setPlaybackState(
                        PlaybackState.Builder()
                            .setActions(mediaActions())
                            .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                            .build()
                    )
                    setPlaybackToLocal(audioAttributes)
                }
                bridges[targetName] = this
                registerVolumeObserver()
                log("[BTN] AVRCP bridge registered for $targetName; capture is disabled until it streams")
                if (pendingCapture) {
                    pendingCapture = false
                    enableCapture()
                }
            } catch (failure: Throwable) {
                log("[BTN] Unable to create AVRCP bridge: ${failure.message}")
            }
        }
    }

    fun setCaptureActive(enabled: Boolean) {
        handler.post {
            pendingCapture = enabled
            if (captureActive == enabled) return@post
            if (enabled) enableCapture() else disableCapture()
        }
    }

    /** Forces an already connected AVRCP peer to re-read this session as the active media player. */
    fun reassertCaptureAfterTransportReady() {
        handler.postDelayed({
            if (!captureActive || session == null) {
                log("[BTN] $targetName media re-assert skipped because capture is not active")
                return@postDelayed
            }
            log("[BTN] $targetName transport ready; re-asserting media focus for AVRCP")
            cancelMediaNotification()
            // Soft re-announce: toggle the session's active state to give the AVRCP peer a
            // play-state transition to notice, without abandoning and re-requesting audio
            // focus in between (see requestMediaFocus() for why that combination could stick).
            // Keep-alive is paused for the flip: its refresh forces isActive=true, and a tick
            // landing inside the gap would collapse the transition the dash needs to notice.
            stopKeepAlive()
            session?.isActive = false
            handler.postDelayed({
                if (!captureActive || session == null) return@postDelayed
                session?.isActive = true
                publishMetadata()
                postMediaNotification()
                if (usesVolumeGestures) pinVolume()
                startKeepAlive()
                log("[BTN] $targetName media focus re-asserted; handlebar input ready")
            }, REASSERT_GAP_MILLIS)
        }, REASSERT_SETTLE_MILLIS)
    }

    fun stop() {
        handler.post {
            pendingCapture = false
            selectDownAt = 0L
            repeatLatched.clear()
            trackDownAt.clear()
            cancelPendingTaps()
            disableCapture()
            unregisterVolumeObserver()
            try { session?.isActive = false } catch (_: Throwable) {}
            try { session?.release() } catch (_: Throwable) {}
            session = null
            bridges.remove(targetName, this)
            log("[BTN] AVRCP bridge stopped for $targetName")
        }
    }

    /** Whether this phone should hold the media volume in order to read volume-key presses. */
    private var usesVolumeGestures = true

    /**
     * Re-evaluates [usesVolumeGestures] against the current calibration, live. Computed only at
     * [enableCapture], the answer went stale the moment the rider taught the handlebar with a
     * session running — on a dash whose volume presses never arrive (CFDL16) the pin then kept
     * hijacking the phone's own volume keys as fake handlebar presses until the NEXT session.
     * Called when the calibration wizard closes and after a companion sync imports calibration.
     */
    fun refreshVolumeGestureUse() {
        handler.post {
            if (!captureActive) return@post
            val use = volumeGesturesInUse()
            if (use == usesVolumeGestures) return@post
            usesVolumeGestures = use
            if (use) {
                if (previousVolume < 0) {
                    previousVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                }
                pinVolume()
                log("[BTN] calibration says volume keys arrive; media volume pinned")
            } else {
                pinnedVolume = -1
                if (previousVolume >= 0) {
                    ignoreVolumeChanges = true
                    try {
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, previousVolume, 0)
                    } catch (_: Throwable) {
                    } finally {
                        handler.postDelayed({ ignoreVolumeChanges = false }, REPIN_IGNORE_MILLIS)
                    }
                }
                log("[BTN] calibration says volume keys never arrive; pin released, phone volume keys are yours again")
            }
        }
    }

    /** Last media volume seen by the observer, for the diagnostic trace only. */
    private var lastObservedVolume = -1

    /**
     * True unless the rider has taught the app that their handlebar's volume presses never
     * arrive. Before calibration the answer is yes: most dashboards do send them, and a
     * missed gesture is worse than a held volume.
     */
    private fun volumeGesturesInUse(): Boolean {
        if (!HandlebarCalibration.isCalibrated(context)) return true
        return HandlebarCalibration.pressFor(context, HandlebarGesture.VOLUME_UP) != null ||
            HandlebarCalibration.pressFor(context, HandlebarGesture.VOLUME_DOWN) != null ||
            HandlebarCalibration.pressFor(context, HandlebarGesture.VOLUME_UP_DOUBLE) != null ||
            HandlebarCalibration.pressFor(context, HandlebarGesture.VOLUME_DOWN_DOUBLE) != null
    }

    private fun enableCapture() {
        if (session == null) {
            log("[BTN] Cannot enable capture before the $targetName service is ready")
            return
        }
        captureActive = true
        // Pinning the media volume is how a volume-key press becomes readable as a gesture -
        // the app holds the level and treats any drift as the rider pressing up or down. On a
        // dashboard that never sends those presses to the phone (a CFDL16 keeps its rocker's
        // short press for its own volume display, road test 2026-07-29) the pin buys nothing
        // and costs the rider control of their own volume, so it is only taken when the rider
        // has a volume-key press that actually arrives.
        usesVolumeGestures = volumeGesturesInUse()
        if (usesVolumeGestures) {
            if (previousVolume < 0) {
                previousVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
            pinVolume()
        } else {
            log("[BTN] volume keys are not delivered by this dashboard; leaving the media volume alone")
        }
        val granted = requestMediaFocus()
        startSilentTrack()
        session?.isActive = true
        publishMetadata()
        postMediaNotification()
        startKeepAlive()
        // Polling continues either way: with gestures off it only feeds the diagnostic trace,
        // which is what proves whether the dashboard moves this phone's volume at all.
        startVolumePolling()
        log("[BTN] capture enabled; audio focus=${if (granted) "granted" else "denied"}")
    }

    /**
     * Requests a transient, ducking focus rather than exclusive [AudioManager.AUDIOFOCUS_GAIN].
     * This bridge only needs enough focus to keep the AVRCP session addressable by the
     * motorcycle's Bluetooth stack (see [startSilentTrack]) - it does not play real audio - so
     * there is no reason to hold exclusive focus indefinitely. Exclusive GAIN combined with the
     * abandon/reacquire cycle previously in [reassertCaptureAfterTransportReady] could leave
     * some OEM Bluetooth stacks (observed on Samsung) with the audio route stuck after a
     * transport-recovery reassert; TRANSIENT_MAY_DUCK plus a focus-preserving reassert avoids
     * dropping and re-taking focus altogether. The request rides [navAttributes], accepts a
     * delayed grant, and never pauses when ducked — losing it entirely is handled by
     * [onAudioFocusChange], which schedules a reclaim instead of silently giving the buttons up.
     */
    private fun requestMediaFocus(): Boolean {
        try { focusRequest?.let(audioManager::abandonAudioFocusRequest) } catch (_: Throwable) {}
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(navAttributes)
            .setOnAudioFocusChangeListener(::onAudioFocusChange)
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(false)
            .build()
        focusRequest = request
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    // ── keeping ownership of the motorcycle's buttons ────────────────────────────────────────────
    //
    // The dash routes AVRCP keys to whichever player looks most alive. Any app that takes audio
    // focus or posts a fresher MediaSession silently steals the handlebar: without an active
    // defense the buttons die at the first Spotify play / nav prompt / notification sound and
    // never come back until the session restarts. Ported from open-cfmoto (field-proven there).

    /** Last time a bike media key was handled — skip focus re-requests while the rider is tapping:
     *  re-requesting focus mid-tap makes the BT stack re-deliver the same press. */
    @Volatile private var lastKeyAt = 0L
    private var keepAliveTicks = 0
    private var reclaimPending = false
    private var lastReclaimAt = 0L
    private val reclaimRunnable = Runnable {
        reclaimPending = false
        if (captureActive) reclaimCapture("focus-loss")
    }
    private val keepAliveRunnable = object : Runnable {
        override fun run() {
            if (!captureActive || session == null) return
            keepAliveTicks++
            refreshPlayingAppearance(reason = "keep-alive")
            val idle = SystemClock.elapsedRealtime() - lastKeyAt > KEY_IDLE_BEFORE_FOCUS_MILLIS
            if (idle && keepAliveTicks % 3 == 0) requestMediaFocus()
            handler.postDelayed(this, KEEP_ALIVE_MILLIS)
        }
    }

    private fun startKeepAlive() {
        handler.removeCallbacks(keepAliveRunnable)
        if (captureActive) handler.postDelayed(keepAliveRunnable, KEEP_ALIVE_MILLIS)
    }

    private fun stopKeepAlive() {
        handler.removeCallbacks(keepAliveRunnable)
    }

    private fun onAudioFocusChange(change: Int) {
        val name = when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> "GAIN"
            AudioManager.AUDIOFOCUS_LOSS -> "LOSS"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "LOSS_TRANSIENT"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "LOSS_TRANSIENT_CAN_DUCK"
            else -> "focus=$change"
        }
        log("[BTN] audio focus -> $name")
        if (!captureActive) return
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                startSilentTrack()
                refreshPlayingAppearance(reason = "focus-gain")
            }
            // Another app is playing over us — expected with MAY_DUCK. Keep the session hot but
            // do not fight for focus: stealing it back exclusively would pause the rider's music.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                refreshPlayingAppearance(reason = "ducked")
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> scheduleReclaim(name)
        }
    }

    private fun scheduleReclaim(reason: String) {
        if (!captureActive) return
        val now = SystemClock.elapsedRealtime()
        if (reclaimPending) return
        reclaimPending = true
        val delay = if (now - lastReclaimAt < RECLAIM_MIN_GAP_MILLIS) {
            RECLAIM_MIN_GAP_MILLIS
        } else {
            RECLAIM_DELAY_MILLIS
        }
        log("[BTN] media focus lost ($reason); reclaiming the handlebar in ${delay}ms")
        handler.postDelayed(reclaimRunnable, delay)
    }

    private fun cancelReclaim() {
        reclaimPending = false
        handler.removeCallbacks(reclaimRunnable)
    }

    /** Pull AVRCP ownership back with duckable nav focus + a session flip — never pauses music.
     *  Keep-alive is paused for the flip (see [reassertCaptureAfterTransportReady]). */
    private fun reclaimCapture(reason: String) {
        if (!captureActive) return
        lastReclaimAt = SystemClock.elapsedRealtime()
        log("[BTN] reclaiming the handlebar ($reason)")
        runCatching {
            stopKeepAlive()
            requestMediaFocus()
            startSilentTrack()
            session?.isActive = false
            handler.postDelayed({
                if (!captureActive) return@postDelayed
                runCatching {
                    refreshPlayingAppearance(reason = "reclaim")
                    if (usesVolumeGestures) pinVolume()
                    startKeepAlive()
                    log("[BTN] handlebar reclaimed")
                }.onFailure { log("[BTN] reclaim failed: ${it.message}") }
            }, REASSERT_GAP_MILLIS)
        }.onFailure { log("[BTN] reclaim failed: ${it.message}") }
    }

    /** Keep metadata / PLAYING state / MediaStyle notification fresh so we stay the button target. */
    private fun refreshPlayingAppearance(reason: String) {
        runCatching {
            publishMetadata()
            session?.isActive = true
            postMediaNotification()
            if (reason != "keep-alive") log("[BTN] playing appearance refreshed ($reason)")
        }.onFailure { log("[BTN] refreshPlayingAppearance failed: ${it.message}") }
    }

    private fun disableCapture() {
        if (!captureActive && focusRequest == null) return
        captureActive = false
        selectDownAt = 0L
        repeatLatched.clear()
        trackDownAt.clear()
        cancelPendingTaps()
        stopKeepAlive()
        cancelReclaim()
        cancelMediaNotification()
        stopSilentTrack()
        handler.removeCallbacks(volumePoll)
        try { focusRequest?.let(audioManager::abandonAudioFocusRequest) } catch (_: Throwable) {}
        focusRequest = null
        if (previousVolume >= 0) {
            try { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, previousVolume, 0) } catch (_: Throwable) {}
        }
        previousVolume = -1
        pinnedVolume = -1
        session?.isActive = false
        log("[BTN] capture disabled; normal media controls restored")
    }

    /** Pins to the rider's own listening volume (captured in [enableCapture]) rather than a
     *  fixed midpoint, so capture doesn't silently jump the phone to half volume. Falls back
     *  to the midpoint only when no prior volume is known. */
    private fun pinVolume() {
        val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val preferred = previousVolume.takeIf { it > 0 } ?: (maximum / 2)
        pinnedVolume = preferred.coerceIn(1, (maximum - 1).coerceAtLeast(1))
        ignoreVolumeChanges = true
        try { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, pinnedVolume, 0) } catch (_: Throwable) {}
        handler.postDelayed({ ignoreVolumeChanges = false }, 150)
    }

    /**
     * Set volume from external UI (e.g. Controls slider) without counting as a handlebar
     * press. While capture is active this must also move [pinnedVolume] to the same value:
     * [consumeVolumeChange] treats any gap between the live stream volume and [pinnedVolume]
     * as a handlebar gesture, so leaving the pin behind would make it look like the rider
     * just pressed volume up/down and immediately snap the slider's new value back to the
     * stale pin. The value is also kept off the literal 0/max endpoints while capturing, same
     * as [pinVolume], so up/down drift stays detectable in both directions.
     */
    fun setListeningVolume(level: Int) {
        try {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val requested = level.coerceIn(0, max)
            val v = if (captureActive) {
                requested.coerceIn(1, (max - 1).coerceAtLeast(1))
            } else {
                requested
            }
            previousVolume = v
            if (captureActive) pinnedVolume = v
            ignoreVolumeChanges = true
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
            } finally {
                handler.postDelayed({ ignoreVolumeChanges = false }, 150)
            }
        } catch (e: Exception) {
            log("[BTN] setListeningVolume failed: $e")
        }
    }

    /** Current music stream volume and max (for the Controls slider). */
    fun volumeLevels(): Pair<Int, Int> {
        val max = try { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } catch (_: Exception) { 15 }
        val now = try { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } catch (_: Exception) { max / 2 }
        return now.coerceIn(0, max) to max.coerceAtLeast(1)
    }

    private fun registerVolumeObserver() {
        if (volumeObserver != null) return
        volumeObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                consumeVolumeChange()
            }
        }.also { observer ->
            context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        }
    }

    private fun startVolumePolling() {
        handler.removeCallbacks(volumePoll)
        handler.post(volumePoll)
    }

    /** Some Android builds do not notify Settings.System for Bluetooth absolute-volume changes. */
    private fun consumeVolumeChange() {
        if (!captureActive) return
        val observed = runCatching {
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        }.getOrNull() ?: return
        // Diagnostic first, before every guard below can swallow it: the open question on a
        // CFDL16 is whether a short rocker press moves the phone's volume AT ALL (in which
        // case it is recoverable) or stays inside the dashboard (in which case nothing can
        // reach us). Only a trace that survives the guards can tell the two apart.
        if (observed != lastObservedVolume) {
            log(
                "[BTN] media volume observed $lastObservedVolume -> $observed " +
                    "(pinned=$pinnedVolume, ignoring=$ignoreVolumeChanges, gestures=$usesVolumeGestures)"
            )
            lastObservedVolume = observed
        }
        if (!usesVolumeGestures || pinnedVolume < 0) return
        if (ignoreVolumeChanges) return
        val current = observed
        if (current == pinnedVolume) return
        val delta = current - pinnedVolume
        // Re-pin under the ignore guard, like pinVolume()/setListeningVolume(): with Bluetooth
        // absolute volume the write round-trips through the peer, and reading that in-flight
        // echo back as a fresh delta would fabricate a phantom press.
        ignoreVolumeChanges = true
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, pinnedVolume, 0)
        } catch (_: Throwable) {
        } finally {
            handler.postDelayed({ ignoreVolumeChanges = false }, REPIN_IGNORE_MILLIS)
        }
        val single = if (delta > 0) HandlebarGesture.VOLUME_UP else HandlebarGesture.VOLUME_DOWN
        log("[BTN] volume ${if (delta > 0) "UP" else "DOWN"}; pinned=$pinnedVolume, delta=$delta")
        val streamMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        when (val read = interpretVolumeDelta(delta, HandlebarControlStore.action(context, single), streamMax)) {
            null -> Unit
            is VolumeDeltaRead.ScrollClicks -> repeat(read.count) { dispatch(read.gesture) }
            is VolumeDeltaRead.Tap -> detectDoubleTap(read.single, read.double, read.forceDouble)
        }
    }

    private fun unregisterVolumeObserver() {
        volumeObserver?.let { observer ->
            runCatching { context.contentResolver.unregisterContentObserver(observer) }
        }
        volumeObserver = null
    }

    private class TapState(var pending: Runnable? = null, var lastAt: Long = 0L)
    private val taps = HashMap<HandlebarGesture, TapState>()

    /**
     * Single vs double on one channel. In EAGER mode ([shouldDispatchSingleEagerly]) the single
     * fires immediately and `pending` is only a "fired recently" marker — a second press inside
     * the window still fires the double on top. In deferred mode the single waits out the
     * window, which is what tells the two apart at the cost of latency on every press.
     */
    private fun detectDoubleTap(
        single: HandlebarGesture,
        double: HandlebarGesture,
        forceDouble: Boolean
    ) {
        val state = taps.getOrPut(single) { TapState() }
        val now = SystemClock.uptimeMillis()
        val decision = resolveTapDispatch(
            forceDouble = forceDouble,
            eagerSingle = shouldDispatchSingleEagerly(double),
            hasPending = state.pending != null,
            gapMillis = now - state.lastAt,
            echoRefractoryMillis = ECHO_REFRACTORY_MILLIS
        )
        state.lastAt = now
        when (decision) {
            TapDispatch.SUPPRESS_ECHO -> Unit
            TapDispatch.DOUBLE -> {
                state.pending?.let(handler::removeCallbacks)
                state.pending = null
                dispatch(double)
            }
            TapDispatch.SINGLE_NOW -> {
                dispatch(single)
                val marker = Runnable { state.pending = null }
                state.pending = marker
                handler.postDelayed(marker, HandlebarTimingPrefs.doubleTapMillis(context))
            }
            TapDispatch.SINGLE_DEFERRED -> {
                val pending = Runnable {
                    state.pending = null
                    dispatch(single)
                }
                state.pending = pending
                handler.postDelayed(pending, HandlebarTimingPrefs.doubleTapMillis(context))
            }
        }
    }

    /**
     * Eager when the rider asked for snappy singles (default), or when the double gesture maps
     * to nothing — then there is nothing to disambiguate and waiting is pure lag.
     *
     * NEVER eager while the calibration wizard is listening: eager mode publishes the single
     * before the double on a double press, and the wizard records the first gesture it sees —
     * at the "double press" step that would record the single AND release it from the press
     * taught moments earlier (one command, one press). Deferred dispatch publishes exactly the
     * disambiguated gesture; latency does not matter while teaching.
     */
    private fun shouldDispatchSingleEagerly(double: HandlebarGesture): Boolean {
        if (HandlebarGestureFeed.isCaptureOnly()) return false
        return HandlebarTimingPrefs.eagerSingles(context) ||
            HandlebarControlStore.action(context, double) == HandlebarAction.NONE
    }

    private fun cancelPendingTaps() {
        taps.values.forEach { state -> state.pending?.let(handler::removeCallbacks) }
        taps.clear()
    }

    private fun dispatch(gesture: HandlebarGesture) {
        if (!captureActive) return
        // Published before anything consumes it, so the mapping screen shows what the
        // handlebar sent even when the gesture is unmapped or swallowed by the dashboard.
        HandlebarGestureFeed.publish(gesture)
        if (HandlebarGestureFeed.isCaptureOnly()) {
            log("[BTN] ${gesture.label} observed for calibration; not acted on")
            return
        }
        val handledByTarget = runCatching { gestureHandler?.invoke(gesture) == true }
            .onFailure { log("[BTN] $targetName gesture handler failed: ${it.message}") }
            .getOrDefault(false)
        if (handledByTarget) {
            log("[BTN] ${gesture.label} -> $targetName")
            return
        }
        val action = HandlebarControlStore.action(context, gesture)
        log("[BTN] ${gesture.label} -> ${action.label}")
        when (action) {
            HandlebarAction.NONE -> Unit
            HandlebarAction.SCROLL_FORWARD -> sendScroll(+1)
            HandlebarAction.SCROLL_BACK -> sendScroll(-1)
            HandlebarAction.DPAD_UP -> sendKey(AndroidAutoInputCodes.KEY_UP)
            HandlebarAction.DPAD_DOWN -> sendKey(AndroidAutoInputCodes.KEY_DOWN)
            HandlebarAction.DPAD_LEFT -> sendKey(AndroidAutoInputCodes.KEY_LEFT)
            HandlebarAction.DPAD_RIGHT -> sendKey(AndroidAutoInputCodes.KEY_RIGHT)
            HandlebarAction.SELECT -> sendKey(AndroidAutoInputCodes.KEY_ENTER)
            HandlebarAction.BACK -> sendKey(AndroidAutoInputCodes.KEY_BACK)
            HandlebarAction.HOME -> sendKey(AndroidAutoInputCodes.KEY_HOME)
            HandlebarAction.ASSISTANT -> sendKey(AndroidAutoInputCodes.KEY_ASSISTANT)
            HandlebarAction.NAV_1 -> navToSavedPlace(context, 0)
            HandlebarAction.NAV_2 -> navToSavedPlace(context, 1)
            HandlebarAction.NAV_3 -> navToSavedPlace(context, 2)
        }
    }

    fun injectSimulatorGesture(gesture: HandlebarGesture) {
        handler.post {
            log("[BTN] simulator injected ${gesture.label}")
            dispatch(gesture)
        }
    }

    private fun navToSavedPlace(context: Context, slot: Int) {
        val query = SavedPlaces.query(context, slot)
        if (query.isBlank()) {
            log("[BTN] saved place ${slot + 1} is not set — set it in Controls → Saved Places")
            return
        }
        log("[BTN] launching navigation to saved place ${slot + 1}: $query")
        NavLauncher.navigate(context, query, log)
    }

    private fun sendKey(keycode: Int) {
        // Routes through AndroidAutoPreviewRuntime (not AaInputBridge directly) so this reaches
        // Core's live AA session over AIDL when Android Auto is delegated there (PRO), not just
        // a local AaInput sink that only exists when AA runs in-process (CORE).
        if (!AndroidAutoPreviewRuntime.sendKey(keycode)) log("[BTN] Android Auto input is not ready; key=$keycode dropped")
    }

    private fun sendScroll(delta: Int) {
        if (!AndroidAutoPreviewRuntime.sendScroll(delta)) log("[BTN] Android Auto input is not ready; scroll=$delta dropped")
    }

    private val callback = object : MediaSession.Callback() {
        override fun onMediaButtonEvent(intent: Intent): Boolean {
            @Suppress("DEPRECATION")
            val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            if (!captureActive || event == null) return false
            val handled = isSelectKey(event.keyCode) || event.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT ||
                event.keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS ||
                event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ||
                event.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND
            if (!handled) return false
            when (event.action) {
                KeyEvent.ACTION_DOWN -> onKeyDown(event.keyCode, event.repeatCount)
                KeyEvent.ACTION_UP -> onKeyUp(event.keyCode)
            }
            return handled
        }

        override fun onPlay() {
            if (captureActive && selectDownAt == 0L) dispatchSelectTap()
        }

        override fun onPause() {
            if (captureActive && selectDownAt == 0L) dispatchSelectTap()
        }
    }

    private var selectDownAt = 0L
    private var lastSelectDispatchAt = 0L
    /** Press instants of non-select media keys, kept only to time their release in the log. */
    private val trackDownAt = mutableMapOf<Int, Long>()
    /** Keys whose current press already fired a hold via key-repeat — their release is spent. */
    private val repeatLatched = mutableSetOf<Int>()

    private fun isSelectKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
        keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE ||
        keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE

    private fun onKeyDown(keyCode: Int, repeatCount: Int = 0) {
        lastKeyAt = SystemClock.elapsedRealtime()
        if (repeatCount > 0) {
            onKeyRepeat(keyCode)
            return
        }
        log("[BTN] media key ${KeyEvent.keyCodeToString(keyCode)} down ($keyCode)")
        when {
            isSelectKey(keyCode) -> if (selectDownAt == 0L) {
                selectDownAt = SystemClock.elapsedRealtime()
            }
            isTrackKey(keyCode) -> {
                trackDownAt[keyCode] = SystemClock.elapsedRealtime()
                // Dashboards that never report a release give us one event per press and
                // nothing else: dispatching here is the only chance to act on it. Where
                // releases DO arrive, the decision waits for one, exactly as select does -
                // a press cannot be known to be a tap until it ends.
                if (!HandlebarControlStore.dashboardReportsHolds(context)) dispatchTrackTap(keyCode)
            }
        }
    }

    /**
     * A key-repeat is proof the button is still down — a hold source independent of a timed
     * release, which some dashes never send. Fires the long gesture once per press (latched)
     * and marks the eventual release as spent. Skipped when the press was already dispatched
     * as a tap on its key-down (track keys before [HandlebarControlStore.dashboardReportsHolds]
     * is learned): firing a hold on top of that tap would run two actions for one press.
     */
    private fun onKeyRepeat(keyCode: Int) {
        if (!HandlebarTimingPrefs.holdsEnabled(context)) return
        if (keyCode in repeatLatched) return
        val longGesture: HandlebarGesture
        val singleGesture: HandlebarGesture
        val downAt: Long
        when {
            isSelectKey(keyCode) -> {
                longGesture = HandlebarGesture.ENTER_LONG
                singleGesture = HandlebarGesture.ENTER
                downAt = selectDownAt
            }
            isTrackKey(keyCode) -> {
                if (!HandlebarControlStore.dashboardReportsHolds(context)) return // tap already sent on down
                if (isForwardKey(keyCode)) {
                    longGesture = HandlebarGesture.TRACK_FORWARD_LONG
                    singleGesture = HandlebarGesture.TRACK_FORWARD
                } else {
                    longGesture = HandlebarGesture.TRACK_BACK_LONG
                    singleGesture = HandlebarGesture.TRACK_BACK
                }
                downAt = trackDownAt[keyCode] ?: 0L
            }
            else -> return
        }
        if (HandlebarControlStore.action(context, longGesture) == HandlebarAction.NONE) return
        // A repeat proves the button is down, but the HOLD fires at the rider's configured
        // hold time, not at whatever repeat delay this BT stack happens to use (some start
        // repeating at ~300ms — well inside what the rider still means as a tap). Repeats
        // keep arriving, so a later one crosses the threshold and latches then.
        if (downAt == 0L) return // repeat without a recorded press we own
        if (SystemClock.elapsedRealtime() - downAt < HandlebarTimingPrefs.selectHoldMillis(context)) return
        repeatLatched.add(keyCode)
        taps[singleGesture]?.pending?.let(handler::removeCallbacks)
        taps[singleGesture]?.pending = null
        log("[BTN] media key ${KeyEvent.keyCodeToString(keyCode)} key-repeat -> hold")
        dispatch(longGesture)
    }

    private fun isTrackKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_MEDIA_NEXT ||
        keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ||
        keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS ||
        keyCode == KeyEvent.KEYCODE_MEDIA_REWIND

    private fun isForwardKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_MEDIA_NEXT ||
        keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD

    private fun dispatchTrackTap(keyCode: Int) {
        if (isForwardKey(keyCode)) {
            detectDoubleTap(
                HandlebarGesture.TRACK_FORWARD,
                HandlebarGesture.TRACK_FORWARD_DOUBLE,
                forceDouble = false
            )
        } else {
            detectDoubleTap(
                HandlebarGesture.TRACK_BACK,
                HandlebarGesture.TRACK_BACK_DOUBLE,
                forceDouble = false
            )
        }
    }

    private fun onKeyUp(keyCode: Int) {
        lastKeyAt = SystemClock.elapsedRealtime()
        if (repeatLatched.remove(keyCode)) {
            // This press already fired its hold from key-repeat; its release is spent.
            if (isSelectKey(keyCode)) selectDownAt = 0L else trackDownAt.remove(keyCode)
            return
        }
        val holdsEnabled = HandlebarTimingPrefs.holdsEnabled(context)
        if (!isSelectKey(keyCode)) {
            if (!isTrackKey(keyCode)) return
            val downAt = trackDownAt.remove(keyCode)
            if (downAt == null) {
                log("[BTN] media key ${KeyEvent.keyCodeToString(keyCode)} released with no recorded press")
                return
            }
            val heldMillis = SystemClock.elapsedRealtime() - downAt
            val holdsKnown = HandlebarControlStore.dashboardReportsHolds(context)
            log("[BTN] media key ${KeyEvent.keyCodeToString(keyCode)} released after ${heldMillis}ms")
            if (!holdsKnown) {
                // First release this dashboard has ever reported: it can time a hold after
                // all. The press just gone was already dispatched on its key-down, so only
                // the NEXT one takes the deferred path - no gesture is lost learning this.
                HandlebarControlStore.setDashboardReportsHolds(context, true)
                log("[BTN] dashboard reports key releases; hold on previous/next is now available")
                return
            }
            if (holdsEnabled && heldMillis >= HandlebarTimingPrefs.selectHoldMillis(context)) {
                dispatch(
                    if (isForwardKey(keyCode)) HandlebarGesture.TRACK_FORWARD_LONG
                    else HandlebarGesture.TRACK_BACK_LONG
                )
            } else {
                dispatchTrackTap(keyCode)
            }
            return
        }
        val startedAt = selectDownAt
        selectDownAt = 0L
        if (startedAt == 0L) {
            dispatchSelectTap()
            return
        }
        val heldMillis = SystemClock.elapsedRealtime() - startedAt
        val isLong = holdsEnabled && heldMillis >= HandlebarTimingPrefs.selectHoldMillis(context)
        log("[BTN] select released after ${heldMillis}ms: ${if (isLong) "hold" else "tap"}")
        if (isLong) {
            taps[HandlebarGesture.ENTER]?.pending?.let(handler::removeCallbacks)
            taps[HandlebarGesture.ENTER]?.pending = null
            dispatch(HandlebarGesture.ENTER_LONG)
        } else {
            dispatchSelectTap()
        }
    }

    /**
     * Dispatches a short select tap, de-duplicating against a paired semantic
     * ([MediaSessionCompat.Callback.onPlay]/[onPause]) and raw-key event for the same
     * physical press: some AVRCP peers emit both for one button action, and each path
     * independently believes it is the only handler for that press. [SELECT_DEDUP_MILLIS]
     * is well under the fastest configured double-tap window (200ms), so a genuine second
     * tap from the rider is never swallowed - only a same-press echo arriving within tens
     * of milliseconds is.
     */
    private fun dispatchSelectTap() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSelectDispatchAt < SELECT_DEDUP_MILLIS) return
        lastSelectDispatchAt = now
        selectPressed()
    }

    private fun selectPressed() {
        detectDoubleTap(HandlebarGesture.ENTER, HandlebarGesture.ENTER_DOUBLE, forceDouble = false)
    }

    private fun startSilentTrack() {
        if (silentTrack != null) return
        runCatching {
            val sampleRate = 8_000
            val frames = sampleRate
            val track = AudioTrack.Builder()
                .setAudioAttributes(navAttributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(frames * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(ShortArray(frames), 0, frames)
            track.setLoopPoints(0, frames, -1)
            // Near-silent, not exactly zero: some OEM audio HALs optimize an all-zero track out
            // of the mix entirely, and with it the "genuinely playing" status that wins AVRCP
            // button routing. 0.01 on the nav stream is inaudible and must not duck music.
            track.setVolume(0.01f)
            track.play()
            silentTrack = track
        }.onFailure { log("[BTN] silent AVRCP track failed: ${it.message}") }
    }

    private fun stopSilentTrack() {
        runCatching { silentTrack?.pause() }
        runCatching { silentTrack?.flush() }
        runCatching { silentTrack?.release() }
        silentTrack = null
    }

    private fun publishMetadata() {
        session?.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "MOTO-HUB controls")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Handlebar controls for $targetName")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, TRACK_DURATION_MS)
                .build()
        )
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(mediaActions())
                .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                .build()
        )
    }

    private fun postMediaNotification() {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Handlebar controls", NotificationManager.IMPORTANCE_LOW)
            )
            manager.notify(
                NOTIFICATION_ID,
                Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(motoHubText("MOTO-HUB controls"))
                    .setContentText(motoHubText("Motorcycle buttons control %1\$s", targetName))
                    .setStyle(Notification.MediaStyle().setMediaSession(session?.sessionToken))
                    .setOngoing(true)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .build()
            )
        }.onFailure { log("[BTN] media notification failed: ${it.message}") }
    }

    private fun cancelMediaNotification() {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }
    }

    private fun mediaActions() = PlaybackState.ACTION_PLAY or
        PlaybackState.ACTION_PAUSE or
        PlaybackState.ACTION_PLAY_PAUSE or
        PlaybackState.ACTION_SKIP_TO_NEXT or
        PlaybackState.ACTION_SKIP_TO_PREVIOUS or
        PlaybackState.ACTION_FAST_FORWARD or
        PlaybackState.ACTION_REWIND

    companion object {
        const val TARGET_ANDROID_AUTO = "Android Auto"

        private const val CHANNEL_ID = "motohub_handlebar_controls"
        private const val NOTIFICATION_ID = 4203
        private const val TRACK_DURATION_MS = 3_600_000L
        private const val VOLUME_POLL_INTERVAL_MILLIS = 250L
        private const val REASSERT_SETTLE_MILLIS = 3_000L
        private const val REASSERT_GAP_MILLIS = 500L
        /** Session-refresh cadence; a soft focus re-request every 3rd tick when idle. */
        private const val KEEP_ALIVE_MILLIS = 4_000L
        /** Don't re-request audio focus while the rider is actively pressing buttons. */
        private const val KEY_IDLE_BEFORE_FOCUS_MILLIS = 2_500L
        private const val RECLAIM_DELAY_MILLIS = 500L
        private const val RECLAIM_MIN_GAP_MILLIS = 2_000L
        private const val ECHO_REFRACTORY_MILLIS = 80L
        private const val SELECT_DEDUP_MILLIS = 100L
        private const val REPIN_IGNORE_MILLIS = 80L
        private val bridges = ConcurrentHashMap<String, MediaButtonBridge>()

        fun setTargetCaptureActive(targetName: String, enabled: Boolean): Boolean {
            val bridge = bridges[targetName] ?: return false
            bridge.setCaptureActive(enabled)
            return true
        }

        fun injectGesture(targetName: String, gesture: HandlebarGesture): Boolean {
            val bridge = bridges[targetName] ?: return false
            bridge.injectSimulatorGesture(gesture)
            return true
        }

        /** Calibration changed — every live bridge re-decides whether to hold the volume pin. */
        fun refreshVolumeGestureUse() {
            bridges.values.forEach { it.refreshVolumeGestureUse() }
        }

        /** Music volume from the live bridge or plain AudioManager (for the Controls slider). */
        fun volumeLevels(context: Context): Pair<Int, Int> {
            bridges.values.firstOrNull()?.let { return it.volumeLevels() }
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val now = audio.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, max)
            return now to max
        }

        /** Set volume from the Controls slider — routes through the bridge when active to avoid
         *  triggering false handlebar events. */
        fun setVolume(context: Context, level: Int) {
            val bridge = bridges.values.firstOrNull()
            if (bridge != null) {
                bridge.setListeningVolume(level)
                return
            }
            try {
                val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, level.coerceIn(0, max), 0)
            } catch (_: Exception) {}
        }
    }
}

internal enum class TapDispatch { SUPPRESS_ECHO, DOUBLE, SINGLE_NOW, SINGLE_DEFERRED }

/**
 * Pure decision for one press on a tap channel. `hasPending` means either a deferred single
 * waiting to fire or an eager "fired recently" marker — in both cases a second press inside
 * the double-tap window, so it resolves to DOUBLE. The refractory guard runs first: two
 * events within [echoRefractoryMillis] are one physical press echoed by the peer, except when
 * the caller already knows better ([forceDouble], a dash-coalesced volume jump).
 */
internal fun resolveTapDispatch(
    forceDouble: Boolean,
    eagerSingle: Boolean,
    hasPending: Boolean,
    gapMillis: Long,
    echoRefractoryMillis: Long = 80L
): TapDispatch = when {
    !forceDouble && gapMillis in 0 until echoRefractoryMillis -> TapDispatch.SUPPRESS_ECHO
    forceDouble || hasPending -> TapDispatch.DOUBLE
    eagerSingle -> TapDispatch.SINGLE_NOW
    else -> TapDispatch.SINGLE_DEFERRED
}

internal sealed interface VolumeDeltaRead {
    /** Repeatable scroll presses fused into one write by the poll window — replay each click. */
    data class ScrollClicks(val gesture: HandlebarGesture, val count: Int) : VolumeDeltaRead
    data class Tap(
        val single: HandlebarGesture,
        val double: HandlebarGesture,
        val forceDouble: Boolean
    ) : VolumeDeltaRead
}

/**
 * Interprets one absolute-volume delta. AVRCP volume is cumulative, so the 250ms poll can fuse
 * quick repeated presses into a single larger write. When the single-press gesture maps to a
 * rotary scroll — a naturally repeatable action — a 2-step jump is replayed as two clicks instead
 * of being mistaken for a gesture. Jumps of [DOUBLE_PRESS_VOLUME_STEPS]+ keep the field-proven
 * meaning of a dash-coalesced double press, which is how BACK/HOME stay reachable from a
 * volume-only handlebar.
 *
 * Real motorcycles break the ±1-step assumption: the CFDL16 dash does not nudge the pinned
 * volume, it overwrites the stream with its own absolute value (road test 2026-07-29: pin 159,
 * bike wrote 70 → delta −89), so a jump of a quarter of the stream range or more is read as ONE
 * press of that sign. A genuine double press arrives as two separate overwrites and still
 * becomes a double through the tap window.
 *
 * Every threshold is a FRACTION of [streamMax], never a fixed number of steps. Phones disagree
 * wildly on how fine that scale is: the usual 0-15 moves one step per key press, while a
 * OnePlus CPH2653 runs 0-160 and moves ten (road test 2026-07-29) - and against a fixed
 * 3-step threshold every single press on that phone was read as a double.
 */
internal fun interpretVolumeDelta(
    delta: Int,
    singleAction: HandlebarAction,
    streamMax: Int
): VolumeDeltaRead? {
    if (delta == 0) return null
    val up = delta > 0
    val single = if (up) HandlebarGesture.VOLUME_UP else HandlebarGesture.VOLUME_DOWN
    val double = if (up) HandlebarGesture.VOLUME_UP_DOUBLE else HandlebarGesture.VOLUME_DOWN_DOUBLE
    val magnitude = abs(delta)
    val absoluteOverwriteFloor = maxOf(streamMax / 4, DOUBLE_PRESS_VOLUME_STEPS + 2)
    if (magnitude >= absoluteOverwriteFloor) {
        return VolumeDeltaRead.Tap(single, double, forceDouble = false)
    }
    // Count PRESSES, not raw steps. One press of a hardware volume key moves about a
    // fifteenth of the range whatever the scale - 1 step of 15, ten of 160 - and judging raw
    // steps made every single press on a fine-grained phone look like a double (OnePlus
    // CPH2653, 0-160, road test 2026-07-29). A delta smaller than one press did not come
    // from a key at all but from a peer writing in its own units, like the T-Box simulator's
    // ±1/±3 on a 255-step stream, and there the raw step count keeps its field-proven meaning.
    val singlePressStep = maxOf(streamMax / 15, 1)
    val presses = magnitude / singlePressStep
    val effective = if (presses >= 1) presses else magnitude
    val scrollMapped = singleAction == HandlebarAction.SCROLL_FORWARD ||
        singleAction == HandlebarAction.SCROLL_BACK
    if (scrollMapped && effective in 2 until DOUBLE_PRESS_VOLUME_STEPS) {
        return VolumeDeltaRead.ScrollClicks(single, effective)
    }
    return VolumeDeltaRead.Tap(single, double, effective >= DOUBLE_PRESS_VOLUME_STEPS)
}
