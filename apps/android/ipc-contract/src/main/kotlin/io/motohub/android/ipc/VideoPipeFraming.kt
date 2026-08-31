// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.ipc

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Framing for the high-rate video pipe opened by `ITBoxTransportService.openVideoStream()`.
 *
 * The pipe used to carry one shape only - a positive length followed by that many bytes of an
 * H.264 access unit - and that shape is preserved exactly, because a companion app built before
 * this class still writes it and must keep working against a Core built after it.
 *
 * A still frame needs one thing an access unit does not: its **frame id**. The Yunmo dashboards
 * that want stills acknowledge by id, so an id that is always zero throws away the only liveness
 * signal the link has. Rather than a second pipe - which would need its own session claim, its own
 * transport watchdog and its own broken-pipe recovery, all of which already exist for this one -
 * the length word is reused as a discriminator:
 *
 * ```
 * H.264 access unit  (every contract version)
 *     int   size            // > 0
 *     byte  payload[size]
 *
 * Extended frame     (CONTRACT_VERSION_VIDEO_STILLS and later)
 *     int   marker          // < 0, one of the MARKER_ constants
 *     int   frameId
 *     int   size            // > 0
 *     byte  payload[size]
 * ```
 *
 * A size was never allowed to be zero or negative, so no previously legal stream can be read as an
 * extended frame and no extended frame can be read as a legal access unit. A writer must still
 * gate on [AndroidAutoIpcState.CONTRACT_VERSION_VIDEO_STILLS] before emitting one: an older Core
 * rejects the negative length and closes the pipe, which is a clean failure but not a useful one.
 */
object VideoPipeFraming {

    /** A JPEG still, as produced by `JpegDisplaySource`. */
    const val MARKER_JPEG_STILL = -1

    /** Upper bound on any single frame, mirroring the reader's own guard. */
    const val MAX_FRAME_BYTES = 2 * 1024 * 1024

    /** One frame off the pipe. [frameId] is meaningless for [isStill] == false. */
    data class Frame(val payload: ByteArray, val isStill: Boolean, val frameId: Int) {
        // Data classes compare arrays by identity, which would make two equal frames unequal.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Frame) return false
            return isStill == other.isStill && frameId == other.frameId &&
                payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int =
            31 * (31 * payload.contentHashCode() + isStill.hashCode()) + frameId
    }

    /** Writes an H.264 access unit in the original, unversioned shape. */
    fun writeAccessUnit(out: DataOutputStream, accessUnit: ByteArray) {
        require(accessUnit.size in 1..MAX_FRAME_BYTES) {
            "Access unit size out of range: ${accessUnit.size}"
        }
        out.writeInt(accessUnit.size)
        out.write(accessUnit)
    }

    /** Writes a JPEG still. Only legal against a Core at [MARKER_JPEG_STILL]'s contract or later. */
    fun writeStill(out: DataOutputStream, jpeg: ByteArray, frameId: Int) {
        require(jpeg.size in 1..MAX_FRAME_BYTES) { "Still size out of range: ${jpeg.size}" }
        out.writeInt(MARKER_JPEG_STILL)
        out.writeInt(frameId)
        out.writeInt(jpeg.size)
        out.write(jpeg)
    }

    /**
     * Reads one frame, or throws [java.io.EOFException] when the writer closed the pipe.
     *
     * An unknown negative marker is a protocol error rather than something to skip: the frame's
     * length is only readable once the marker is understood, so there is no safe way to resume.
     */
    fun read(input: DataInputStream): Frame {
        val header = input.readInt()
        if (header > 0) {
            require(header <= MAX_FRAME_BYTES) { "Invalid video access unit size: $header" }
            val payload = ByteArray(header)
            input.readFully(payload)
            return Frame(payload, isStill = false, frameId = 0)
        }
        require(header == MARKER_JPEG_STILL) { "Unknown video pipe frame marker: $header" }
        val frameId = input.readInt()
        val size = input.readInt()
        require(size in 1..MAX_FRAME_BYTES) { "Invalid still size: $size" }
        val payload = ByteArray(size)
        input.readFully(payload)
        return Frame(payload, isStill = true, frameId = frameId)
    }
}
