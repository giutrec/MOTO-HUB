// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.encoding

import android.content.Context
import android.os.PowerManager
import io.motohub.android.feature.settings.MotoHubSettings
import io.motohub.android.feature.settings.VideoPowerMode
import kotlin.math.min

/**
 * Adjusts a live encoder to what the phone and the bike link can actually carry.
 *
 * Thermal pressure protects the phone from sustained encoder throttling, and stays a Power mode
 * AUTO courtesy: a rider who picked a fixed mode asked for that frame rate and gets it.
 *
 * Lost frames are the downstream signal, and they are not a preference - a link that is discarding
 * frames wastes the bitrate spent on them and, on a GOP stream, smears the TFT until the next
 * keyframe. So the link backoff applies in every mode, multiplicative down and slow back up, with
 * the rider's mode as the ceiling it recovers to. Loss counts both access units the transport
 * refused and frames it accepted and then dropped: the CORE video pipe does the latter, and
 * counting only the former left the controller blind exactly when it was needed. A rider's CFMOTO
 * MTX800 on 2026-08-17 lost ~280 dashboard frames in that pipe against a single rejection, at a
 * fixed Smooth 30fps that nothing was allowed to lower.
 */
data class AdaptiveVideoDecision(
    val bitrate: Int,
    val frameRate: Int,
    val linkFactor: Float
)

object AdaptiveVideoPolicy {
    const val MIN_BITRATE = 600_000
    const val DROP_THRESHOLD = 8
    const val LINK_MIN = 0.4f
    const val LINK_MAX = 1.0f
    const val LINK_BACKOFF = 0.8f
    const val LINK_RECOVERY_STEP = 0.05f

    /** The slowest the link backoff may pace a stream. Below this the TFT reads as a slideshow,
     *  and a link that cannot carry 12fps has a problem no encoder setting is going to solve. */
    const val MIN_FRAME_RATE = 12

    fun thermalBitrateFactor(status: Int): Float = when {
        status <= PowerManager.THERMAL_STATUS_LIGHT -> 1.0f
        status == PowerManager.THERMAL_STATUS_MODERATE -> 0.8f
        status == PowerManager.THERMAL_STATUS_SEVERE -> 0.6f
        else -> 0.5f
    }

    fun thermalFrameRateCap(status: Int, baseFrameRate: Int): Int = when {
        status <= PowerManager.THERMAL_STATUS_LIGHT -> baseFrameRate
        status == PowerManager.THERMAL_STATUS_MODERATE -> min(baseFrameRate, 20)
        status == PowerManager.THERMAL_STATUS_SEVERE -> min(baseFrameRate, 15)
        else -> min(baseFrameRate, 12)
    }

    fun nextLinkFactor(previous: Float, lostFrames: Int): Float = if (
        lostFrames >= DROP_THRESHOLD
    ) {
        (previous * LINK_BACKOFF).coerceAtLeast(LINK_MIN)
    } else {
        (previous + LINK_RECOVERY_STEP).coerceAtMost(LINK_MAX)
    }

    /** Fewer, larger-budget frames on a link that is losing them. Cutting only the bitrate leaves
     *  the same frame count queued on a transport whose queue is what overflowed. */
    fun linkFrameRateCap(baseFrameRate: Int, linkFactor: Float): Int =
        (baseFrameRate * linkFactor).toInt()
            .coerceAtLeast(MIN_FRAME_RATE.coerceAtMost(baseFrameRate))
            .coerceAtMost(baseFrameRate)

    /**
     * @param baseFrameRate the ceiling this session may run at: the encoder's own rate under Power
     * mode AUTO, the mode's rate otherwise.
     * @param thermalStatus always `THERMAL_STATUS_NONE` outside AUTO, so a fixed mode never gets
     * throttled for heat behind the rider's back.
     */
    fun decide(
        baseBitrate: Int,
        baseFrameRate: Int,
        thermalStatus: Int,
        previousLinkFactor: Float,
        lostFrames: Int
    ): AdaptiveVideoDecision {
        val linkFactor = nextLinkFactor(previousLinkFactor, lostFrames)
        val factor = min(thermalBitrateFactor(thermalStatus), linkFactor)
        return AdaptiveVideoDecision(
            bitrate = (baseBitrate * factor).toInt().coerceIn(
                MIN_BITRATE.coerceAtMost(baseBitrate),
                baseBitrate
            ),
            frameRate = min(
                thermalFrameRateCap(thermalStatus, baseFrameRate),
                linkFrameRateCap(baseFrameRate, linkFactor)
            ),
            linkFactor = linkFactor
        )
    }
}

/** Owns the live policy state for one projection encoder. */
class AdaptiveVideoController(
    context: Context,
    private val log: (String) -> Unit
) {
    // Services can construct their fields before attachBaseContext(). Resolve the application
    // context only when the first stream tick runs, after Android has attached the component.
    private val componentContext = context
    private val appContext by lazy { componentContext.applicationContext ?: componentContext }
    private val powerManager by lazy { appContext.getSystemService(PowerManager::class.java) }
    private var linkFactor = AdaptiveVideoPolicy.LINK_MAX
    private var lastLostFrames = 0L
    private var appliedBitrate = -1
    private var appliedFrameRate = -1

    fun reset() {
        linkFactor = AdaptiveVideoPolicy.LINK_MAX
        lastLostFrames = 0L
        appliedBitrate = -1
        appliedFrameRate = -1
    }

    /**
     * @param linkDown true while the session has no working transport at all - a broken video
     * pipe, a recovery in flight. Every access unit is rejected then, which is not the "weak bike
     * link" this policy exists to soften: reading it as one collapsed the bitrate to the floor
     * within twenty seconds and handed the recovered session a picture degraded for no reason.
     * The outage's rejections are absorbed, not judged.
     */
    fun onTick(encoder: AvcEncoder?, linkDown: Boolean = false) {
        val activeEncoder = encoder ?: return
        val baseBitrate = activeEncoder.targetBitrate()
        if (baseBitrate <= 0) return
        if (linkDown) {
            lastLostFrames = lostFramesTotal(activeEncoder)
            return
        }

        val mode = MotoHubSettings.videoPowerMode(appContext)
        val autoMode = mode == VideoPowerMode.AUTO
        // Outside AUTO the rider named a frame rate, so it becomes the ceiling the link backoff
        // works under, and heat is left out of it entirely.
        val ceilingFrameRate = if (autoMode) {
            activeEncoder.baseFrameRate
        } else {
            mode.frameRate.coerceAtMost(activeEncoder.baseFrameRate)
        }
        val thermalStatus = if (autoMode) {
            runCatching { powerManager?.currentThermalStatus }
                .getOrNull()
                ?: PowerManager.THERMAL_STATUS_NONE
        } else {
            PowerManager.THERMAL_STATUS_NONE
        }
        val totalLost = lostFramesTotal(activeEncoder)
        val lostThisTick = (totalLost - lastLostFrames)
            .coerceAtLeast(0L)
            .toInt()
        lastLostFrames = totalLost
        val decision = AdaptiveVideoPolicy.decide(
            baseBitrate = baseBitrate,
            baseFrameRate = ceilingFrameRate,
            thermalStatus = thermalStatus,
            previousLinkFactor = linkFactor,
            lostFrames = lostThisTick
        )
        linkFactor = decision.linkFactor
        if (decision.bitrate != appliedBitrate) {
            activeEncoder.setEncoderBitrate(decision.bitrate)
            appliedBitrate = decision.bitrate
            log(
                "[adaptive] ${mode.label} thermal=${thermalLabel(thermalStatus)} " +
                    "lost/tick=$lostThisTick link=${(linkFactor * 100).toInt()}% " +
                    "bitrate=${decision.bitrate / 1000}kbps"
            )
        }
        if (decision.frameRate != appliedFrameRate) {
            activeEncoder.setFrameCap(decision.frameRate)
            appliedFrameRate = decision.frameRate
            log(
                "[adaptive] ${mode.label} thermal=${thermalLabel(thermalStatus)} " +
                    "link=${(linkFactor * 100).toInt()}% fps=${decision.frameRate}"
            )
        }
    }

    /** Access units the transport refused, plus frames it accepted and then dropped. Both are one
     *  picture that never reached the TFT. */
    private fun lostFramesTotal(encoder: AvcEncoder): Long =
        encoder.rejectedAccessUnitsTotal() + encoder.transportDroppedFramesTotal()

    private fun thermalLabel(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> status.toString()
    }
}
