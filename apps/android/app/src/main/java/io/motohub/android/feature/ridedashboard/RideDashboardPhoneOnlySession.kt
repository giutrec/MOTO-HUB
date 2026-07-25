package io.motohub.android.feature.ridedashboard

import android.content.Context
import android.view.Surface
import io.motohub.android.data.MotorcycleProfileStore
import io.motohub.android.feature.ridedashboard.nav.NavPoint
import io.motohub.android.feature.ridedashboard.nav.OpenMeteoWeatherClient
import io.motohub.android.feature.ridedashboard.nav.runWeatherUpdateLoop
import io.motohub.android.feature.ridedashboard.widget.DashboardLayoutConfig
import io.motohub.android.feature.ridedashboard.widget.DashboardLayoutStore
import io.motohub.android.feature.ridedashboard.widget.DashboardWidgetRegistry
import io.motohub.android.session.ProjectionEventLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Renders the Ride Dashboard directly onto a phone-side Surface, using the phone's own GPS - no
 * T-Box connection required. Extracted from what used to be inlined in RideDashboardPreviewScreen
 * (Settings' passive preview, [publishRuntimeState] = false) so the exact same logic can also back
 * a first-class phone-only session ([publishRuntimeState] = true) that plugs into the shared
 * ActiveSessionContent UI via [RideDashboardRuntime], exactly like the real T-Box session does.
 * Always uses the OSM map panel - there is no live/phone-mirrored Android Auto feed to embed here.
 */
class RideDashboardPhoneOnlySession(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val publishRuntimeState: Boolean,
    private val onFailure: (String) -> Unit
) {
    private var renderer: RideDashboardRenderer? = null
    private var telemetryProvider: RideTelemetryProvider? = null
    private var weatherJob: Job? = null

    val isRunning: Boolean get() = renderer != null

    fun start(surface: Surface): Boolean {
        stop()
        if (publishRuntimeState) RideDashboardRuntime.publish(RideDashboardRuntimeState.Starting)
        val activeTelemetry = RideTelemetryProvider(context)
        val gpsResult = activeTelemetry.start()
        if (gpsResult.isFailure) {
            val message = "Unable to start GPS: ${gpsResult.exceptionOrNull()?.message}"
            if (publishRuntimeState) RideDashboardRuntime.publish(RideDashboardRuntimeState.Failed(message))
            onFailure(message)
            return false
        }
        telemetryProvider = activeTelemetry
        // Same weather fetch loop the real T-Box session runs - without this the widget stayed
        // stuck on "Loading" forever, since nothing ever published to WeatherWidgetRuntime outside
        // that session. No T-Box Wi-Fi to avoid here, so this uses whatever network the phone
        // already has.
        val weatherClient = OpenMeteoWeatherClient(context, cellularOnly = false)
        weatherJob = coroutineScope.launch {
            runWeatherUpdateLoop(weatherClient) {
                activeTelemetry.snapshot().position?.let { NavPoint(it.latitude, it.longitude) }
            }
        }
        // Same widget layout the rider configured in Customize Dashboard for their active
        // motorcycle - without this the preview always showed the hardcoded defaults.
        val activeMotorcycle = MotorcycleProfileStore(context).load()
        val activeSsid = activeMotorcycle?.ssid
        // Falls back to the shared "no motorcycle" layout (customizable from Garage) instead of
        // always the hardcoded default, so a phone-only rider's customization actually applies.
        val layoutConfig = DashboardLayoutStore(context).load(activeSsid ?: DashboardLayoutStore.PHONE_ONLY_KEY)
        renderer = RideDashboardRenderer(
            context = context,
            surface = surface,
            fps = PHONE_ONLY_FPS,
            bitRate = PHONE_ONLY_DUMMY_BIT_RATE,
            tBoxLabel = "PHONE PREVIEW",
            motorcyclePhotoPath = activeMotorcycle?.photoPath,
            telemetryProvider = activeTelemetry,
            layoutController = RideDashboardLayoutController(),
            mapSource = RideDashboardMapSource.OPEN_STREET_MAP,
            embeddedAndroidAuto = null,
            cellularOnlyMaps = false,
            // The T-Box path stretches on purpose to fill its bike's fixed panel resolution
            // edge-to-edge; this SurfaceView's shape just follows the phone's own orientation, so
            // it must letterbox instead of distorting.
            preserveAspectRatio = true,
            leftWidget = DashboardWidgetRegistry.forId(layoutConfig.leftWidgetId)
                ?: DashboardWidgetRegistry.forId(DashboardLayoutConfig.DEFAULT.leftWidgetId)!!,
            rightWidget = DashboardWidgetRegistry.forId(layoutConfig.rightWidgetId)
                ?: DashboardWidgetRegistry.forId(DashboardLayoutConfig.DEFAULT.rightWidgetId)!!,
            onFailure = { failure ->
                ProjectionEventLog.error("RIDE_DASHBOARD", "Phone-only renderer stopped.", failure)
                val message = "Preview stopped: ${failure.message}"
                if (publishRuntimeState) RideDashboardRuntime.publish(RideDashboardRuntimeState.Stopped(message))
                onFailure(message)
            }
        ).also { it.start() }
        if (publishRuntimeState) RideDashboardRuntime.publish(RideDashboardRuntimeState.Streaming)
        return true
    }

    fun stop(reason: String = "Stopped by the user.") {
        val wasRunning = renderer != null
        renderer?.stop()
        renderer = null
        telemetryProvider?.stop()
        telemetryProvider = null
        weatherJob?.cancel()
        weatherJob = null
        if (publishRuntimeState && wasRunning) {
            RideDashboardRuntime.publish(RideDashboardRuntimeState.Stopped(reason))
        }
    }

    private companion object {
        const val PHONE_ONLY_FPS = 20
        const val PHONE_ONLY_DUMMY_BIT_RATE = 4_000_000
    }
}
