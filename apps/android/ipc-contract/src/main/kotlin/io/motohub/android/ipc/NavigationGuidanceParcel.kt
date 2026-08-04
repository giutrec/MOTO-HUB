package io.motohub.android.ipc

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Turn-by-turn guidance CORE extracts from the Android Auto instrument-cluster navigation
 * channel while any AA session (full or embedded) is running with a navigating app (Google
 * Maps, Waze, …). Broadcast to registered [INavigationGuidanceListener]s on every change and
 * once at registration with the latest known state.
 *
 * [maneuverType] carries the AA wire enum raw value (NavigationManeuver.NavigationType) so the
 * contract stays stable if the receiving side's own maneuver vocabulary evolves; -1 when the
 * app has not described the next turn. Distances/times are -1 when the app did not send them.
 */
@Parcelize
data class NavigationGuidanceParcel(
    /** True while the nav app reports an active (or rerouting) guidance session. */
    val active: Boolean,
    /** True only while the nav app is recalculating the route. */
    val rerouting: Boolean = false,
    /** AA NavigationManeuver.NavigationType raw value; -1 when unknown. */
    val maneuverType: Int = -1,
    /** Roundabout exit ordinal when the maneuver is a roundabout; 0 otherwise. */
    val roundaboutExitNumber: Int = 0,
    /** Road/cue text for the next maneuver; empty when the app sent none. */
    val road: String = "",
    val distanceToManeuverMeters: Int = -1,
    val timeToManeuverSeconds: Int = -1,
    /** Remaining distance/time to the final destination; -1 when unknown. */
    val distanceRemainingMeters: Int = -1,
    val timeToArrivalSeconds: Long = -1L,
    /** Destination ETA formatted by the nav app itself; empty when not provided. */
    val estimatedTimeAtArrival: String = ""
) : Parcelable
