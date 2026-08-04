// Navigation-status (instrument cluster) channel support, following the message flow of
// headunit-revived (AGPLv3): aap/AapNavigation.kt, trimmed to a data feed — no notifications.
package io.motohub.android.aa

import io.motohub.android.aa.proto.NavigationStatus

/**
 * Latest turn-by-turn guidance parsed from the Android Auto navigation-status channel,
 * process-wide for the CORE app. [AapControlNavigation] writes it for whichever AA session
 * is running (full T-Box session or PRO's embedded Dashboard panel); the IPC bridge reads
 * [latest] and forwards every [publish] to its registered companion-app listeners.
 *
 * Maneuver vocabulary is the AA wire enum (NavigationManeuver.NavigationType raw value) so
 * consumers translate once at their edge, not on every hop.
 */
object AaNavigationGuidance {

    data class Snapshot(
        val active: Boolean,
        val rerouting: Boolean = false,
        val maneuverType: Int = -1,
        val roundaboutExitNumber: Int = 0,
        val road: String = "",
        val distanceToManeuverMeters: Int = -1,
        val timeToManeuverSeconds: Int = -1,
        val distanceRemainingMeters: Int = -1,
        val timeToArrivalSeconds: Long = -1L,
        val estimatedTimeAtArrival: String = ""
    ) {
        companion object {
            val INACTIVE = Snapshot(active = false)
        }
    }

    @Volatile
    var latest: Snapshot = Snapshot.INACTIVE
        private set

    @Volatile
    private var listener: ((Snapshot) -> Unit)? = null

    /** Single consumer (the IPC bridge). Replaces any previous listener. */
    fun setListener(newListener: ((Snapshot) -> Unit)?) {
        listener = newListener
    }

    internal fun publish(snapshot: Snapshot) {
        latest = snapshot
        listener?.invoke(snapshot)
    }

    /** Called when an AA session ends so a stale turn can never outlive its session. */
    fun clear() {
        if (latest != Snapshot.INACTIVE) publish(Snapshot.INACTIVE)
    }
}

/**
 * Parses the ID_NAV channel. Google Maps speaks the modern messages (NavigationState /
 * NavigationCurrentPosition); the legacy NextTurnDetail / NextTurnDistanceEvent pair is kept
 * because other AA nav apps still send it — headunit-revived observed both dialects in the
 * wild. All writes funnel into [AaNavigationGuidance].
 */
internal class AapControlNavigation : AapControl {

    private var current = AaNavigationGuidance.Snapshot.INACTIVE

    override fun execute(message: AapMessage): Int {
        when (message.type) {
            NavigationStatus.MsgType.INSTRUMENT_CLUSTER_START_VALUE -> {
                AaLog.i("Nav: instrument cluster start")
                publish(AaNavigationGuidance.Snapshot.INACTIVE)
            }
            NavigationStatus.MsgType.INSTRUMENT_CLUSTER_STOP_VALUE -> {
                AaLog.i("Nav: instrument cluster stop")
                publish(AaNavigationGuidance.Snapshot.INACTIVE)
            }
            NavigationStatus.MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATUS_VALUE -> runParsing("status") {
                val status = message.parse(NavigationStatus.NavigationClusterStatus.newBuilder()).buildPartial()
                val statusEnum = status.status
                AaLog.i("Nav: cluster status %s", statusEnum)
                val active = statusEnum == NavigationStatus.NavigationClusterStatus.NavigationStatusEnum.ACTIVE ||
                    statusEnum == NavigationStatus.NavigationClusterStatus.NavigationStatusEnum.REROUTING
                publish(
                    if (active) {
                        current.copy(
                            active = true,
                            rerouting = statusEnum ==
                                NavigationStatus.NavigationClusterStatus.NavigationStatusEnum.REROUTING
                        )
                    } else {
                        // Guidance ended: drop the last turn with it rather than freezing it.
                        AaNavigationGuidance.Snapshot.INACTIVE
                    }
                )
            }
            NavigationStatus.MsgType.NEXTTURNDETAILS_VALUE -> runParsing("turn detail") {
                val detail = message.parse(NavigationStatus.NextTurnDetail.newBuilder()).buildPartial()
                AaLog.i(
                    "Nav: next turn road=%s event=%s side=%s",
                    detail.road, detail.nextTurn, detail.side
                )
                publish(
                    current.copy(
                        active = true,
                        maneuverType = legacyTurnToManeuverType(detail),
                        roundaboutExitNumber = if (detail.hasTurnNumber()) detail.turnNumber else 0,
                        road = detail.road.orEmpty().ifBlank { current.road }
                    )
                )
            }
            NavigationStatus.MsgType.NEXTTURNDISTANCEANDTIME_VALUE -> runParsing("turn distance") {
                val event = message.parse(NavigationStatus.NextTurnDistanceEvent.newBuilder()).buildPartial()
                publish(
                    current.copy(
                        active = true,
                        distanceToManeuverMeters = if (event.hasDistanceMeters()) event.distanceMeters else -1,
                        timeToManeuverSeconds = if (event.hasTimeToTurnSeconds()) event.timeToTurnSeconds else -1
                    )
                )
            }
            NavigationStatus.MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATE_VALUE -> runParsing("state") {
                val state = message.parse(NavigationStatus.NavigationState.newBuilder()).buildPartial()
                val step = state.stepsList.firstOrNull() ?: return@runParsing
                val maneuver = if (step.hasManeuver()) step.maneuver else null
                val road = step.takeIf { it.hasRoad() }?.road?.name.orEmpty()
                    .ifBlank { step.takeIf { it.hasCue() }?.cue?.alternateTextList?.firstOrNull().orEmpty() }
                AaLog.i(
                    "Nav: state steps=%d maneuver=%s road=%s",
                    state.stepsCount, maneuver?.type, road
                )
                publish(
                    current.copy(
                        active = true,
                        maneuverType = maneuver?.type?.number ?: current.maneuverType,
                        roundaboutExitNumber = maneuver
                            ?.takeIf { it.hasRoundaboutExitNumber() }?.roundaboutExitNumber ?: 0,
                        road = road.ifBlank { current.road }
                    )
                )
            }
            NavigationStatus.MsgType.INSTRUMENT_CLUSTER_NAVIGATION_CURRENT_POSITION_VALUE -> runParsing("position") {
                val position = message.parse(NavigationStatus.NavigationCurrentPosition.newBuilder()).buildPartial()
                val stepDistance = position.takeIf { it.hasStepDistance() }?.stepDistance
                val destination = position.destinationDistancesList.firstOrNull()
                publish(
                    current.copy(
                        active = true,
                        distanceToManeuverMeters = stepDistance
                            ?.takeIf { it.hasDistance() && it.distance.hasMeters() }
                            ?.distance?.meters ?: current.distanceToManeuverMeters,
                        timeToManeuverSeconds = stepDistance
                            ?.takeIf { it.hasTimeToStepSeconds() }
                            ?.timeToStepSeconds?.toInt() ?: current.timeToManeuverSeconds,
                        distanceRemainingMeters = destination
                            ?.takeIf { it.hasDistance() && it.distance.hasMeters() }
                            ?.distance?.meters ?: current.distanceRemainingMeters,
                        timeToArrivalSeconds = destination
                            ?.takeIf { it.hasTimeToArrivalSeconds() }
                            ?.timeToArrivalSeconds ?: current.timeToArrivalSeconds,
                        estimatedTimeAtArrival = destination
                            ?.takeIf { it.hasEstimatedTimeAtArrival() }
                            ?.estimatedTimeAtArrival ?: current.estimatedTimeAtArrival
                    )
                )
            }
            else -> AaLog.i("Nav: unhandled message type %d", message.type)
        }
        return 0
    }

    private fun publish(snapshot: AaNavigationGuidance.Snapshot) {
        if (snapshot == current) return
        current = snapshot
        AaNavigationGuidance.publish(snapshot)
    }

    private inline fun runParsing(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            AaLog.e("Nav: failed to parse $what: $e")
        }
    }

    /**
     * Folds the legacy (event, side) pair into the modern NavigationType vocabulary the
     * parcel speaks, so downstream consumers only ever deal with one enum.
     */
    private fun legacyTurnToManeuverType(detail: NavigationStatus.NextTurnDetail): Int {
        val left = detail.side == NavigationStatus.NextTurnDetail.Side.LEFT
        fun pick(leftType: NavigationStatus.NavigationManeuver.NavigationType,
                 rightType: NavigationStatus.NavigationManeuver.NavigationType) =
            (if (left) leftType else rightType).number
        if (!detail.hasNextTurn()) return -1
        return when (detail.nextTurn) {
            NavigationStatus.NextTurnDetail.NextEvent.DEPART ->
                NavigationStatus.NavigationManeuver.NavigationType.DEPART.number
            NavigationStatus.NextTurnDetail.NextEvent.NAME_CHANGE ->
                NavigationStatus.NavigationManeuver.NavigationType.NAME_CHANGE.number
            NavigationStatus.NextTurnDetail.NextEvent.SLIGHT_TURN -> pick(
                NavigationStatus.NavigationManeuver.NavigationType.TURN_SLIGHT_LEFT,
                NavigationStatus.NavigationManeuver.NavigationType.TURN_SLIGHT_RIGHT
            )
            NavigationStatus.NextTurnDetail.NextEvent.TURN -> pick(
                NavigationStatus.NavigationManeuver.NavigationType.TURN_NORMAL_LEFT,
                NavigationStatus.NavigationManeuver.NavigationType.TURN_NORMAL_RIGHT
            )
            NavigationStatus.NextTurnDetail.NextEvent.SHARP_TURN -> pick(
                NavigationStatus.NavigationManeuver.NavigationType.TURN_SHARP_LEFT,
                NavigationStatus.NavigationManeuver.NavigationType.TURN_SHARP_RIGHT
            )
            NavigationStatus.NextTurnDetail.NextEvent.U_TURN -> pick(
                NavigationStatus.NavigationManeuver.NavigationType.U_TURN_LEFT,
                NavigationStatus.NavigationManeuver.NavigationType.U_TURN_RIGHT
            )
            NavigationStatus.NextTurnDetail.NextEvent.ON_RAMP -> pick(
                NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_NORMAL_LEFT,
                NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_NORMAL_RIGHT
            )
            NavigationStatus.NextTurnDetail.NextEvent.OFFRAMP -> pick(
                NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_NORMAL_LEFT,
                NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_NORMAL_RIGHT
            )
            NavigationStatus.NextTurnDetail.NextEvent.FORK -> pick(
                NavigationStatus.NavigationManeuver.NavigationType.FORK_LEFT,
                NavigationStatus.NavigationManeuver.NavigationType.FORK_RIGHT
            )
            NavigationStatus.NextTurnDetail.NextEvent.MERGE -> pick(
                NavigationStatus.NavigationManeuver.NavigationType.MERGE_LEFT,
                NavigationStatus.NavigationManeuver.NavigationType.MERGE_RIGHT
            )
            NavigationStatus.NextTurnDetail.NextEvent.ROUNDABOUT_ENTER ->
                NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER.number
            NavigationStatus.NextTurnDetail.NextEvent.ROUNDABOUT_EXIT ->
                NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_EXIT.number
            NavigationStatus.NextTurnDetail.NextEvent.ROUNDABOUT_ENTER_AND_EXIT ->
                NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CW.number
            NavigationStatus.NextTurnDetail.NextEvent.STRAIGHT ->
                NavigationStatus.NavigationManeuver.NavigationType.STRAIGHT.number
            NavigationStatus.NextTurnDetail.NextEvent.FERRY_BOAT ->
                NavigationStatus.NavigationManeuver.NavigationType.FERRY_BOAT.number
            NavigationStatus.NextTurnDetail.NextEvent.FERRY_TRAIN ->
                NavigationStatus.NavigationManeuver.NavigationType.FERRY_TRAIN.number
            NavigationStatus.NextTurnDetail.NextEvent.DESTINATION ->
                NavigationStatus.NavigationManeuver.NavigationType.DESTINATION.number
            else -> -1
        }
    }
}
