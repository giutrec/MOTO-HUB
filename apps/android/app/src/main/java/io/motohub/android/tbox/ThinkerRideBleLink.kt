package io.motohub.android.tbox

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred

/**
 * The BLE side of a ThinkerRide session: finds the dash by its GATT service, opens the link,
 * runs the JSON handshake and keeps the 5s heartbeat alive. TCP stays in [ThinkerRideTransport];
 * nothing here touches sockets.
 *
 * Every command is fire-and-forget on the write characteristic (the protocol uses
 * write-without-response), so ordering is enforced by time: the dash firmware drops packets that
 * arrive back-to-back, hence [ThinkerRideProtocol.BLE_WRITE_SPACING_MS] between every queued
 * write. All Bluetooth calls are wrapped against [SecurityException] because the runtime grant
 * can be revoked mid-session; a revoked permission surfaces as [onLinkLost], never a crash.
 */
@SuppressLint("MissingPermission")
internal class ThinkerRideBleLink(
    context: Context,
    private val log: (String) -> Unit,
    private val onLinkLost: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val serviceUuid = UUID.fromString(ThinkerRideProtocol.BLE_SERVICE_UUID)
    private val writeUuid = UUID.fromString(ThinkerRideProtocol.BLE_WRITE_CHARACTERISTIC_UUID)
    private val notifyUuid = UUID.fromString(ThinkerRideProtocol.BLE_NOTIFY_CHARACTERISTIC_UUID)
    private val cccUuid = UUID.fromString(ThinkerRideProtocol.BLE_CCC_DESCRIPTOR_UUID)

    private val scheduler = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "MotoHubThinkerRideBle").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    @Volatile
    private var heartbeat: ScheduledFuture<*>? = null

    @Volatile
    private var scanStop: ScheduledFuture<*>? = null

    private val closed = AtomicBoolean(false)
    private val ready = CompletableDeferred<Result<String>>()

    /** Set once the dash acknowledges pairing (`send_pairresult` = 1) over notify. */
    val pairConfirmed = AtomicBoolean(false)

    /**
     * Scans for the dash, connects, subscribes to notifications, sends the opening handshake and
     * starts the heartbeat. Resolves with the peripheral name once commands can be written.
     */
    suspend fun connect(timeoutMillis: Long): Result<String> {
        val adapter = bluetoothManager?.adapter
            ?: return Result.failure(IllegalStateException("This phone has no Bluetooth adapter."))
        if (!isEnabled()) {
            return Result.failure(
                IllegalStateException("Bluetooth is off. This dashboard pairs over Bluetooth; turn it on and connect again.")
            )
        }
        val scanner = adapter.bluetoothLeScanner
            ?: return Result.failure(IllegalStateException("Bluetooth LE scanning is unavailable on this phone."))

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (closed.get() || gatt != null) return
                val name = runCatching { result.device.name }.getOrNull() ?: "ThinkerRide dashboard"
                log("BLE dashboard found: $name (${result.device.address}); connecting.")
                stopScan(scanner, this)
                openGatt(result, name)
            }

            override fun onScanFailed(errorCode: Int) {
                completeReady(Result.failure(IllegalStateException("Bluetooth scan failed (code $errorCode).")))
            }
        }

        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val started = runCatching { scanner.startScan(listOf(filter), settings, scanCallback) }
        started.exceptionOrNull()?.let { failure ->
            return Result.failure(
                if (failure is SecurityException) missingPermissionFailure() else failure
            )
        }
        log("Scanning for the dashboard's Bluetooth service ($serviceUuid).")

        scanStop = scheduler.schedule({
            if (!ready.isCompleted && gatt == null) {
                stopScan(scanner, scanCallback)
                completeReady(
                    Result.failure(
                        IllegalStateException(
                            "No ThinkerRide dashboard was found over Bluetooth. Make sure the " +
                                "dash is on its phone-connection screen and within range."
                        )
                    )
                )
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS)

        val outcome = ready.await()
        if (outcome.isFailure) {
            stopScan(scanner, scanCallback)
            close()
        }
        return outcome
    }

    /** Sends the projection start/stop pair; safe to call from any thread. */
    fun sendMirrorStatus(active: Boolean) {
        ThinkerRideProtocol.bleMirrorStatusPackets(active).forEachIndexed { index, packet ->
            scheduler.schedule(
                { writeCommand(packet) },
                index * ThinkerRideProtocol.BLE_WRITE_SPACING_MS,
                TimeUnit.MILLISECONDS
            )
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        heartbeat?.cancel(false)
        scanStop?.cancel(false)
        val activeGatt = gatt
        gatt = null
        writeCharacteristic = null
        runCatching {
            activeGatt?.disconnect()
            activeGatt?.close()
        }
        scheduler.shutdownNow()
        completeReady(Result.failure(IllegalStateException("Bluetooth link closed.")))
    }

    private fun isEnabled(): Boolean =
        runCatching { bluetoothManager?.adapter?.isEnabled == true }.getOrDefault(false)

    private fun stopScan(scanner: android.bluetooth.le.BluetoothLeScanner, callback: ScanCallback) {
        runCatching { scanner.stopScan(callback) }
    }

    private fun openGatt(result: ScanResult, name: String) {
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(connectedGatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        log("BLE connected; discovering the mirroring service.")
                        runCatching { connectedGatt.discoverServices() }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (closed.get()) return
                        if (!ready.isCompleted) {
                            completeReady(
                                Result.failure(IllegalStateException("The dashboard closed the Bluetooth link (status $status)."))
                            )
                        } else {
                            onLinkLost("The dashboard closed the Bluetooth link (status $status).")
                        }
                    }
                }
            }

            override fun onServicesDiscovered(connectedGatt: BluetoothGatt, status: Int) {
                val service = connectedGatt.getService(serviceUuid)
                val write = service?.getCharacteristic(writeUuid)
                if (service == null || write == null) {
                    completeReady(
                        Result.failure(IllegalStateException("The dashboard does not expose the ThinkerRide mirroring service."))
                    )
                    return
                }
                writeCharacteristic = write
                service.getCharacteristic(notifyUuid)?.let { notify ->
                    runCatching {
                        connectedGatt.setCharacteristicNotification(notify, true)
                        notify.getDescriptor(cccUuid)?.let { descriptor ->
                            connectedGatt.writeDescriptor(
                                descriptor,
                                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            )
                        }
                    }
                }
                log("Mirroring service ready; sending the Bluetooth handshake.")
                queueHandshake()
                completeReady(Result.success(name))
            }

            override fun onCharacteristicChanged(
                connectedGatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                if (characteristic.uuid != notifyUuid) return
                val text = value.toString(StandardCharsets.UTF_8)
                log("Dash -> BLE: $text")
                if (ThinkerRideProtocol.isPairConfirmation(text)) {
                    pairConfirmed.set(true)
                    log("Dashboard confirmed Bluetooth pairing (send_pairresult=1).")
                }
            }
        }
        val opened = runCatching {
            result.device.connectGatt(appContext, false, callback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
        }
        gatt = opened.getOrNull()
        if (gatt == null) {
            completeReady(
                Result.failure(opened.exceptionOrNull() ?: IllegalStateException("Unable to open the Bluetooth link."))
            )
        }
    }

    private fun queueHandshake() {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        ThinkerRideProtocol.bleHandshakePackets(timestamp).forEachIndexed { index, packet ->
            scheduler.schedule(
                { writeCommand(packet) },
                index * ThinkerRideProtocol.BLE_WRITE_SPACING_MS,
                TimeUnit.MILLISECONDS
            )
        }
        heartbeat = scheduler.scheduleWithFixedDelay(
            { writeCommand(ThinkerRideProtocol.bleHeartbeatPacket()) },
            ThinkerRideProtocol.BLE_HEARTBEAT_INTERVAL_MS,
            ThinkerRideProtocol.BLE_HEARTBEAT_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
    }

    private fun writeCommand(json: String) {
        val activeGatt = gatt ?: return
        val characteristic = writeCharacteristic ?: return
        val written = runCatching {
            activeGatt.writeCharacteristic(
                characteristic,
                json.toByteArray(StandardCharsets.UTF_8),
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            )
        }
        val failure = written.exceptionOrNull()
        when {
            failure is SecurityException -> onLinkLost(missingPermissionFailure().message.orEmpty())
            failure != null -> log("BLE write failed: ${failure.message}")
            written.getOrNull() != BluetoothGatt.GATT_SUCCESS ->
                log("BLE write refused (code ${written.getOrNull()}): $json")
        }
    }

    private fun completeReady(outcome: Result<String>) {
        if (!ready.isCompleted) ready.complete(outcome)
    }

    private fun missingPermissionFailure(): IllegalStateException = IllegalStateException(
        "MOTO-HUB does not have the \"Nearby devices\" (Bluetooth) permission, which this " +
            "dashboard needs for pairing. Allow it in the app settings, then connect again."
    )
}
