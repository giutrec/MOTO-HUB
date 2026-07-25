package io.motohub.android.feature.trips

import androidx.compose.runtime.Composable

/**
 * Core never shows the Trips tab (see HubBottomNavigation - "Nav and Trips are PRO-only
 * features") and the Trips UI (TripsScreen.kt, GPX export, the completed-trip map) lives in
 * app/src/pro. This no-op only exists so MainActivity.kt (shared) can reference a single
 * `TripsTabWrapper()` symbol regardless of flavor. TripRecordingService/TripRecordingRuntime/
 * TripStore stay shared - Core's own Android Auto session still auto-records trips in the
 * background even though it never shows this tab.
 */
@Composable
fun TripsTabWrapper(
    recordingState: TripRecordingState,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onDiscardRecording: () -> Unit
) {
}
