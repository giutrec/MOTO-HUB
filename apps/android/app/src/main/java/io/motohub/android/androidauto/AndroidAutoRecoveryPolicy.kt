// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.androidauto

/** Pure recovery-timing logic for a full Android Auto session — no AGPL dependency, kept in
 *  shared code (and tested from both flavors) even though only Core's AndroidAutoSessionService
 *  currently calls it. */
internal fun shouldAutoRecoverAndroidAuto(
    hasReachedStreaming: Boolean,
    enabled: Boolean
): Boolean = hasReachedStreaming && enabled

internal fun isAndroidAutoWatchdogStalled(
    nowElapsed: Long,
    lastProgressElapsed: Long,
    thresholdMillis: Long
): Boolean = lastProgressElapsed > 0L && nowElapsed - lastProgressElapsed >= thresholdMillis
