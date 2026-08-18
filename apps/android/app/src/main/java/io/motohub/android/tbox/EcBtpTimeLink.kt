package io.motohub.android.tbox

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import java.util.Date
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Answers the dashboard's Bluetooth clock questions, and says nothing to anything else.
 *
 * MOTO-HUB answers `ECP_C2P_QUERY_TIME` over Wi-Fi byte-for-byte as the official app does - the
 * two handlers were compared field by field against Carbit's own `ih/n0.java` - and a rider's Voge
 * still sits at 00:00 while Carbit Ride keeps it right on the same bike. The reason is that PXC is
 * not where that clock is written at all: Carbit's `sendSyncTime()` builds
 * [EcBtpProtocol.CMD_SYNC_TIME] with `System.currentTimeMillis() + rawOffset` and pushes it over
 * **BLE**, and that write is what survives an ignition cycle. This is that second channel.
 *
 * **This is a diagnostic first and a fix second.** Both requests are reactive: if this dash never
 * asks, nothing here will ever fire, and the log saying so is the answer that closes the question.
 * Every frame that arrives is logged whether or not it is a clock request.
 *
 * Four rules keep it off everyone else's hardware, because the service UUIDs this protocol rides on
 * (`ffe0/ffe1`, `fff0/fff1`, …) are generic serial-over-BLE identifiers shared with intercoms, OBD
 * dongles, TPMS sensors and countless toys:
 *
 *  1. **Opt-in.** The caller only builds this when the rider turned the setting on; off by default.
 *  2. **Only devices advertising one of these services.** The bonded list alone was the original
 *     rule and it was wrong: Carbit reaches the dash as an *unbonded* BLE peripheral - its own
 *     stack drives the Nordic scanner compat library and writes through a GATT service in the
 *     list below - and an unbonded peripheral is in nobody's `bondedDevices` and publishes no
 *     cached UUIDs. Every Voge log therefore said "no bonded Bluetooth devices" while Carbit was
 *     setting that very dash's clock. So both sources are used: bonded devices whose cached UUID
 *     list does not rule them out, plus a [SCAN_WINDOW_MILLIS] scan whose [ScanFilter]s name
 *     exactly these service UUIDs, so the Bluetooth controller drops every other advertiser
 *     before it reaches this process.
 *  3. **Listen before writing.** Not one byte is transmitted until that device has sent a
 *     *syntactically valid* EC-BTP frame - right start byte, self-consistent length, correct XOR,
 *     right terminator. An intercom or an OBD dongle cannot produce one by accident, so it never
 *     hears from us. This is the safety property that matters; [EcBtpProtocol.parse] is its gate.
 *  4. **Never alongside ThinkerRide.** KOVE dashes hold their own GATT link and two concurrent
 *     connections are a known way to destabilise the Android stack, so the caller must not start
 *     both.
 *
 * All Bluetooth calls are wrapped against [SecurityException]: the runtime grant can be revoked
 * mid-session, and that must degrade to a log line rather than a crash.
 */
@SuppressLint("MissingPermission")
internal class EcBtpTimeLink(
    context: Context,
    private val log: (String) -> Unit,
    private val now: () -> Date = { Date() },
    private val zone: () -> TimeZone = { TimeZone.getDefault() }
) {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)

    private val closed = AtomicBoolean(false)
    private val connections = mutableListOf<BluetoothGatt>()
    private val lock = Any()

    /** Addresses already being watched, so the bonded pass and the scan cannot both open one. */
    private val watched = mutableSetOf<String>()

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ec-btp-scan").apply { isDaemon = true }
    }

    @Volatile
    private var scanner: BluetoothLeScanner? = null

    @Volatile
    private var scanCallback: ScanCallback? = null

    /**
     * Watches every device that could plausibly be this dashboard, from both sources, and listens.
     *
     * Returns how many bonded devices were opened immediately. Scan results arrive later and are
     * logged as they come, so a field log distinguishes the three answers that matter: nothing was
     * ever found, something was found and never spoke, or something spoke and was answered.
     */
    fun start(): Int {
        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            log("EC-BTP: this phone has no Bluetooth adapter; the dash clock cannot be set over Bluetooth.")
            return 0
        }
        if (!adapter.isEnabled) {
            log("EC-BTP: Bluetooth is off, so the dashboard cannot be asked for its clock over it.")
            return 0
        }

        if (!ThinkerRideGate.hasBlePermissions(appContext)) {
            // Worth its own line: without this grant the scan below throws and the rider sees a
            // setting that is on and does nothing at all.
            log(
                "EC-BTP: " + ThinkerRideGate.missingPermissionMessage("MOTO-HUB") +
                    " Until then the dash clock cannot be set over Bluetooth."
            )
            return 0
        }

        val bonded = runCatching { adapter.bondedDevices }.getOrNull().orEmpty()
        val candidates = bonded.filter { candidateWorthOpening(it) }
        log(
            "EC-BTP: ${bonded.size} bonded device(s), ${candidates.size} of them could carry this " +
                "protocol. Scanning as well, because the dash need not be bonded at all. " +
                "Listening only; nothing is sent until something speaks EC-BTP."
        )
        candidates.forEach { device -> openGatt(device) }
        beginScan(adapter.bluetoothLeScanner)
        return candidates.size
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        endScan()
        scheduler.shutdownNow()
        synchronized(lock) {
            connections.forEach { gatt ->
                runCatching { gatt.disconnect() }
                runCatching { gatt.close() }
            }
            connections.clear()
            watched.clear()
        }
    }

    /**
     * Starts a bounded scan for peripherals advertising one of [SERVICE_UUIDS].
     *
     * Bounded because a dash that is going to ask for the time asks within seconds of the session
     * starting, and an unbounded LE scan is a battery cost the rider did not ask for. The filters
     * are handed to the Bluetooth controller, so an intercom or a tyre sensor is dropped below this
     * process rather than being connected to and then let go.
     */
    private fun beginScan(leScanner: BluetoothLeScanner?) {
        if (leScanner == null) {
            log("EC-BTP: Bluetooth LE scanning is unavailable on this phone; only bonded devices can be watched.")
            return
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (closed.get()) return
                val device = runCatching { result.device }.getOrNull() ?: return
                openGatt(device)
            }

            override fun onScanFailed(errorCode: Int) {
                log("EC-BTP: the Bluetooth scan could not start (code $errorCode); only bonded devices are watched.")
                endScan()
            }
        }
        val filters = SERVICE_UUIDS.map { uuid ->
            ScanFilter.Builder().setServiceUuid(ParcelUuid(uuid)).build()
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val started = runCatching { leScanner.startScan(filters, settings, callback) }
        if (started.isFailure) {
            val failure = started.exceptionOrNull()
            val reason = if (failure is SecurityException) {
                "the Bluetooth scan permission is not granted"
            } else {
                failure?.message ?: "an unknown error"
            }
            log("EC-BTP: could not scan for the dashboard ($reason); only bonded devices are watched.")
            return
        }
        scanner = leScanner
        scanCallback = callback
        log("EC-BTP: scanning ${SCAN_WINDOW_MILLIS / 1000}s for a dashboard advertising one of ${SERVICE_UUIDS.size} serial services.")
        runCatching {
            scheduler.schedule({ endScan() }, SCAN_WINDOW_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    private fun endScan() {
        val active = scanner ?: return
        val callback = scanCallback ?: return
        scanner = null
        scanCallback = null
        runCatching { active.stopScan(callback) }
    }

    /**
     * Whether a bonded device is worth opening a GATT connection to at all.
     *
     * `getUuids()` is the cached service list from bonding, so when it is populated this filter is
     * free: a headset or a tyre sensor is ruled out without ever being touched. When it is null the
     * device is allowed through, because a dash that has not been service-discovered yet would
     * otherwise be skipped forever - and [onServicesDiscovered] drops it immediately anyway if the
     * service is not really there.
     */
    private fun candidateWorthOpening(device: BluetoothDevice): Boolean {
        val cached: Array<ParcelUuid>? = runCatching { device.uuids }.getOrNull()
        if (cached.isNullOrEmpty()) return true
        return cached.any { SERVICE_UUIDS.contains(it.uuid) }
    }

    private fun openGatt(device: BluetoothDevice) {
        val address = runCatching { device.address }.getOrNull() ?: return
        synchronized(lock) {
            if (closed.get()) return
            // The bonded pass and the scan can both surface the same dash, and a second GATT
            // connection to one peripheral is exactly the thing that destabilises the stack.
            if (!watched.add(address)) return
        }
        val label = runCatching { device.name }.getOrNull() ?: address
        log("EC-BTP: watching $label ($address).")
        val callback = object : BluetoothGattCallback() {
            /** Set once this peer has proven it speaks EC-BTP; nothing is written before that. */
            private val proven = AtomicBoolean(false)

            @Volatile
            private var dataCharacteristic: BluetoothGattCharacteristic? = null

            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (closed.get()) {
                        runCatching { gatt.disconnect() }
                        return
                    }
                    runCatching { gatt.discoverServices() }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    forget(gatt)
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val service = SERVICE_UUIDS.firstNotNullOfOrNull { uuid ->
                    runCatching { gatt.getService(uuid) }.getOrNull()
                }
                val characteristic = service?.let { dataCharacteristicOf(it) }
                if (characteristic == null) {
                    // Not a dashboard, or not one that speaks this protocol. Let go at once rather
                    // than sitting on someone's intercom.
                    log("EC-BTP: $label exposes no EC-BTP data characteristic; disconnecting.")
                    runCatching { gatt.disconnect() }
                    return
                }
                dataCharacteristic = characteristic
                subscribe(gatt, characteristic)
                log("EC-BTP: listening to $label on ${characteristic.uuid}.")
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                if (closed.get()) return
                val frame = EcBtpProtocol.parse(value)
                if (frame == null) {
                    // Deliberately not silent: a device chattering something we cannot parse is
                    // exactly what a rider's log needs to show, and it is also the evidence that
                    // this peer is NOT a dashboard.
                    log("EC-BTP: $label sent ${value.size} byte(s) that are not an EC-BTP frame; staying silent.")
                    return
                }
                if (proven.compareAndSet(false, true)) {
                    log("EC-BTP: $label speaks EC-BTP - replies to its clock requests are now allowed.")
                }
                val command = frame.command
                log("EC-BTP: $label -> command 0x${(command.toInt() and 0xFF).toString(16)}, ${frame.payload.size} payload byte(s).")
                val reply = when (command) {
                    EcBtpProtocol.CMD_SYNC_TIME ->
                        EcBtpProtocol.syncTimeReply(now().time, zone().rawOffset)
                    EcBtpProtocol.CMD_QUERY_TIME ->
                        EcBtpProtocol.queryTimeReply(now(), zone())
                    else -> null
                }
                if (reply == null) return
                val target = dataCharacteristic ?: return
                val written = runCatching {
                    BleCompat.writeCharacteristic(
                        gatt,
                        target,
                        reply,
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    )
                }.getOrNull()
                log("EC-BTP: answered $label's clock request with ${reply.size} byte(s) (result $written).")
            }
        }

        val opened = runCatching {
            device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        }.getOrNull()
        if (opened == null) {
            log("EC-BTP: could not open a Bluetooth link to $label.")
            return
        }
        synchronized(lock) {
            if (closed.get()) {
                runCatching { opened.disconnect() }
                runCatching { opened.close() }
            } else {
                connections += opened
            }
        }
    }

    private fun dataCharacteristicOf(service: BluetoothGattService): BluetoothGattCharacteristic? =
        CHARACTERISTIC_UUIDS.firstNotNullOfOrNull { uuid ->
            runCatching { service.getCharacteristic(uuid) }.getOrNull()
        }

    private fun subscribe(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        runCatching { gatt.setCharacteristicNotification(characteristic, true) }
        val descriptor = runCatching { characteristic.getDescriptor(CCC_UUID) }.getOrNull() ?: return
        runCatching {
            BleCompat.writeDescriptor(gatt, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }
    }

    private fun forget(gatt: BluetoothGatt) {
        val address = runCatching { gatt.device?.address }.getOrNull()
        synchronized(lock) {
            connections.remove(gatt)
            if (address != null) watched.remove(address)
        }
        runCatching { gatt.close() }
    }

    private companion object {
        /**
         * How long to scan for an unbonded dashboard.
         *
         * A dash that asks for the time asks within seconds of the session starting - in every
         * field log the whole PXC opening burst lands inside ten - so a window this size is
         * generous, and leaving an LE scan running for a whole ride is battery the rider did not
         * agree to spend.
         */
        const val SCAN_WINDOW_MILLIS = 30_000L

        /** Carbit's service list (`pe/a.java:17`), in its own order. */
        val SERVICE_UUIDS = listOf(
            UUID.fromString("00001c00-d102-11e1-9b23-000efb0000b2"),
            UUID.fromString("0000474d-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("00001c00-d102-11e1-9b23-000efb0000c3"),
            UUID.fromString("0000474e-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("00001c00-d102-11e1-9b23-000efb0000c6"),
            UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        )

        /** The matching data characteristics (`pe/a.java:20`). */
        val CHARACTERISTIC_UUIDS = listOf(
            UUID.fromString("00001c0f-d102-11e1-9b23-000efb0000b2"),
            UUID.fromString("00004b59-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("00001c0f-d102-11e1-9b23-000efb0000c6"),
            UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        )

        val CCC_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
