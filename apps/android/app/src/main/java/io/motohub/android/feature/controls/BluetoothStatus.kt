package io.motohub.android.feature.controls

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import io.motohub.android.i18n.motoHubText
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The handlebar buttons do not ride the T-Box/Wi-Fi link — the dash sends them as ordinary
 * Bluetooth AVRCP commands to whatever phone is PAIRED to the motorcycle. A phone that never
 * paired gets nothing, and until now the app could not say so: the rider just saw buttons that
 * did nothing. This reports what is CONNECTED right now and deep-links to the system Bluetooth
 * settings so pairing is one tap away.
 *
 * Connected means connected: the names come from the A2DP/HEADSET profile proxies'
 * `connectedDevices`, never from the bond list — `bondedDevices` is every device the phone has
 * EVER paired with, and showing its first entry as "connected" once claimed a powered-off PC
 * was the motorcycle.
 */
object BluetoothStatus {

    data class Status(
        val supported: Boolean,
        val enabled: Boolean,
        val permitted: Boolean,
        /** Names of the audio devices connected RIGHT NOW (empty when none, or no permission). */
        val connectedNames: List<String>
    ) {
        val connected: Boolean get() = connectedNames.isNotEmpty()

        /** One-line summary for the handlebar settings card. */
        fun describe(): String = when {
            !supported -> motoHubText("This phone has no Bluetooth.")
            !enabled -> motoHubText("Bluetooth is off — turn it on, then pair the motorcycle.")
            !permitted -> motoHubText(
                "Allow Bluetooth access so the app can see whether the motorcycle is connected."
            )
            connected -> motoHubText(
                "Connected now: %1\$s. If that is the motorcycle, the handlebar buttons can " +
                    "reach the phone.",
                connectedNames.joinToString(", ")
            )
            else -> motoHubText(
                "No audio device is connected right now. With the motorcycle on, its dash " +
                    "should appear here — if it never does, pair it from Bluetooth settings."
            )
        }
    }

    /**
     * Queries the live connection state and delivers [onResult] on the main thread — once.
     * Asynchronous because the real connected-device list only exists behind
     * [BluetoothAdapter.getProfileProxy]; a short timeout covers a proxy that never binds.
     */
    // Guarded by hasConnectPermission() + runCatching; lint cannot follow the indirection.
    @SuppressLint("MissingPermission")
    fun query(context: Context, onResult: (Status) -> Unit) {
        val appContext = context.applicationContext
        val adapter: BluetoothAdapter? =
            (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null) {
            onResult(Status(supported = false, enabled = false, permitted = false, connectedNames = emptyList()))
            return
        }
        val enabled = adapter.isEnabled
        val permitted = hasConnectPermission(appContext)
        if (!enabled || !permitted) {
            onResult(Status(supported = true, enabled = enabled, permitted = permitted, connectedNames = emptyList()))
            return
        }

        val handler = Handler(Looper.getMainLooper())
        val names = LinkedHashSet<String>()
        val remaining = mutableSetOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)
        val delivered = AtomicBoolean(false)
        fun deliver() {
            if (delivered.compareAndSet(false, true)) {
                onResult(Status(supported = true, enabled = true, permitted = true, connectedNames = names.toList()))
            }
        }

        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                handler.post {
                    runCatching {
                        proxy.connectedDevices.forEach { device ->
                            runCatching { device.name }.getOrNull()?.let(names::add)
                        }
                    }
                    runCatching { adapter.closeProfileProxy(profile, proxy) }
                    remaining.remove(profile)
                    if (remaining.isEmpty()) deliver()
                }
            }

            override fun onServiceDisconnected(profile: Int) = Unit
        }

        handler.post {
            var pendingProxies = false
            for (profile in remaining.toList()) {
                val requested = runCatching {
                    adapter.getProfileProxy(appContext, listener, profile)
                }.getOrDefault(false)
                if (!requested) remaining.remove(profile) else pendingProxies = true
            }
            if (!pendingProxies) {
                deliver()
            } else {
                // A proxy that never binds must not leave the card on "loading" forever.
                handler.postDelayed({ deliver() }, PROXY_TIMEOUT_MILLIS)
            }
        }
    }

    /** True if BLUETOOTH_CONNECT is held (always true before Android 12, where it doesn't exist). */
    fun hasConnectPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /** Opens the system Bluetooth settings so the rider can pair/connect the motorcycle. */
    fun openBluetoothSettings(context: Context) {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    private const val PROXY_TIMEOUT_MILLIS = 1_500L
}
