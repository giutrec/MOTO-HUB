// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The clock EXPERIMENT bench behind Settings > Diagnostics > Dash clock lab.
 *
 * [EcBtpTimeLink] is deliberately reactive: it answers a dash that asks for the time and says
 * nothing to one that stays silent. Zontes riders keep reporting the dash clock resetting to
 * 00:00 after an ignition cycle - and their logs show exactly that silence: the link opens,
 * nothing ever speaks, nothing is ever written. Carbit's own `sendSyncTime()` is not an answer,
 * it is an unsolicited BLE push. So the open question is which unsolicited shape this dash
 * actually accepts, and this lab exists to ask it on a real bike.
 *
 * It connects to everything that plausibly is the dash (bonded pass plus a filtered LE scan,
 * the same sources as [EcBtpTimeLink]), dumps the full GATT table, listens for a window, and
 * then pushes the time in every shape worth ruling in or out - epoch with raw offset (the exact
 * Carbit shape), the same frame acked, pure UTC, DST-aware offset, and the formatted-string
 * form - a few seconds apart, logging every byte in both directions. The rider then checks the
 * dash after each ignition cycle and the log says which attempt was on the wire.
 *
 * The listen-before-write rule that protects [EcBtpTimeLink] is deliberately relaxed here:
 * a dash that never speaks is the very case under investigation. The blast radius is still
 * bounded - only peripherals exposing one of Carbit's serial service+characteristic pairs are
 * written to, the frames are valid EC-BTP that generic serial devices ignore, and the whole lab
 * is a button a rider pressed on a diagnostics screen, not something that runs by itself.
 */
@SuppressLint("MissingPermission")
internal class EcBtpClockLab(
    context: Context,
    private val log: (String) -> Unit,
    private val onFinished: () -> Unit
) {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)

    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private val connections = mutableListOf<BluetoothGatt>()
    private val watched = mutableSetOf<String>()
    private val framesReceived = AtomicInteger(0)
    private val devicesWritten = AtomicInteger(0)

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ec-btp-clock-lab").apply { isDaemon = true }
    }

    @Volatile
    private var scanner: BluetoothLeScanner? = null

    @Volatile
    private var scanCallback: ScanCallback? = null

    /** Starts the bench; it closes itself after [LAB_DURATION_MILLIS] and then calls [onFinished]. */
    fun start() {
        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            log("This phone has no Bluetooth adapter; the lab cannot run.")
            finish()
            return
        }
        if (!adapter.isEnabled) {
            log("Bluetooth is off. Turn it on and run the lab again.")
            finish()
            return
        }
        if (!ThinkerRideGate.hasBlePermissions(appContext)) {
            log(ThinkerRideGate.missingPermissionMessage("MOTO-HUB") + " The lab cannot run until then.")
            finish()
            return
        }

        log(
            "Clock lab started. Keep the dash powered on: the lab runs for " +
                "${LAB_DURATION_MILLIS / 1000}s, pushes the time in ${ATTEMPT_COUNT} different " +
                "shapes, and logs everything. Check the dash clock after the next ignition cycle."
        )

        val bonded = runCatching { adapter.bondedDevices }.getOrNull().orEmpty()
        log("${bonded.size} bonded Bluetooth device(s); opening the plausible ones and scanning for unbonded dashes.")
        bonded.filter { candidateWorthOpening(it) }.forEach { openGatt(it, "bonded") }
        beginScan(adapter.bluetoothLeScanner)

        runCatching {
            scheduler.schedule({ finish() }, LAB_DURATION_MILLIS, TimeUnit.MILLISECONDS)
        }
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

    private fun finish() {
        if (closed.get()) return
        log(
            "Clock lab finished: wrote to ${devicesWritten.get()} device(s), received " +
                "${framesReceived.get()} notification(s). Share the application log if the dash " +
                "clock is still wrong after the next ignition cycle."
        )
        close()
        onFinished()
    }

    /** Same leniency as [EcBtpTimeLink.candidateWorthOpening]: no cached UUIDs is not a no. */
    private fun candidateWorthOpening(device: BluetoothDevice): Boolean {
        val cached = runCatching { device.uuids }.getOrNull()
        if (cached.isNullOrEmpty()) return true
        return cached.any { EcBtpTimeLink.SERVICE_UUIDS.contains(it.uuid) }
    }

    private fun beginScan(leScanner: BluetoothLeScanner?) {
        if (leScanner == null) {
            log("Bluetooth LE scanning is unavailable on this phone; only bonded devices are tried.")
            return
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (closed.get()) return
                val device = runCatching { result.device }.getOrNull() ?: return
                openGatt(device, "scan, rssi ${result.rssi}")
            }

            override fun onScanFailed(errorCode: Int) {
                log("The Bluetooth scan could not start (code $errorCode); only bonded devices are tried.")
                endScan()
            }
        }
        val filters = EcBtpTimeLink.SERVICE_UUIDS.map { uuid ->
            ScanFilter.Builder().setServiceUuid(ParcelUuid(uuid)).build()
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val started = runCatching { leScanner.startScan(filters, settings, callback) }
        if (started.isFailure) {
            log("Could not scan for the dash (${started.exceptionOrNull()?.message ?: "unknown error"}); only bonded devices are tried.")
            return
        }
        scanner = leScanner
        scanCallback = callback
        log("Scanning ${SCAN_WINDOW_MILLIS / 1000}s for a dash advertising one of ${EcBtpTimeLink.SERVICE_UUIDS.size} serial services.")
        runCatching {
            scheduler.schedule({
                endScan()
                if (!closed.get() && synchronized(lock) { watched.isEmpty() }) {
                    log(
                        "Scan over: nothing in range advertises the known serial services and no " +
                            "bonded device carries them. Is the dash powered on and its Bluetooth awake?"
                    )
                }
            }, SCAN_WINDOW_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    private fun endScan() {
        val active = scanner ?: return
        val callback = scanCallback ?: return
        scanner = null
        scanCallback = null
        runCatching { active.stopScan(callback) }
    }

    private fun openGatt(device: BluetoothDevice, origin: String) {
        val address = runCatching { device.address }.getOrNull() ?: return
        synchronized(lock) {
            if (closed.get()) return
            if (!watched.add(address)) return
        }
        val label = runCatching { device.name }.getOrNull() ?: address
        log("Opening $label ($address, $origin).")

        val callback = object : BluetoothGattCallback() {
            @Volatile
            private var dataCharacteristic: BluetoothGattCharacteristic? = null

            /** The attempt whose reply a following notification most plausibly is. */
            @Volatile
            private var lastAttempt: String = "before any write"

            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (closed.get()) {
                        runCatching { gatt.disconnect() }
                        return
                    }
                    log("$label: connected, discovering services.")
                    runCatching { gatt.discoverServices() }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    log("$label: link closed (status $status).")
                    forget(gatt)
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                dumpGattTable(label, gatt)
                val service = EcBtpTimeLink.SERVICE_UUIDS.firstNotNullOfOrNull { uuid ->
                    runCatching { gatt.getService(uuid) }.getOrNull()
                }
                val characteristic = service?.let { dataCharacteristicOf(it) }
                if (characteristic == null) {
                    log("$label: no EC-BTP serial service+characteristic pair; leaving it alone.")
                    runCatching { gatt.disconnect() }
                    return
                }
                dataCharacteristic = characteristic
                subscribe(gatt, characteristic)
                log(
                    "$label: experimenting on ${characteristic.uuid} " +
                        "(${describeProperties(characteristic.properties)}). Listening " +
                        "${LISTEN_WINDOW_MILLIS / 1000}s first - a dash that asks by itself is " +
                        "the answer EcBtpTimeLink already handles."
                )
                scheduleAttempts(gatt, label)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                log(
                    "$label: acked write completed with GATT status $status " +
                        if (status == BluetoothGatt.GATT_SUCCESS) "(accepted)." else "(REFUSED)."
                )
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                val value = characteristic.value ?: return
                onCharacteristicChanged(gatt, characteristic, value)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                if (closed.get()) return
                framesReceived.incrementAndGet()
                val parsed = EcBtpProtocol.parse(value)
                val kind = when {
                    parsed == null -> "not an EC-BTP frame"
                    parsed.command == EcBtpProtocol.CMD_SYNC_TIME -> "EC-BTP SYNC_TIME request"
                    parsed.command == EcBtpProtocol.CMD_QUERY_TIME -> "EC-BTP QUERY_TIME request"
                    else -> "EC-BTP command 0x${(parsed.command.toInt() and 0xFF).toString(16)}"
                }
                log("$label -> ${value.size} byte(s) ($kind, $lastAttempt): ${hex(value)}")
                // A dash that asks gets the stock answer too, so the lab never leaves it worse
                // than EcBtpTimeLink would.
                val reply = when (parsed?.command) {
                    EcBtpProtocol.CMD_SYNC_TIME ->
                        EcBtpProtocol.syncTimeReply(System.currentTimeMillis(), TimeZone.getDefault().rawOffset)
                    EcBtpProtocol.CMD_QUERY_TIME ->
                        EcBtpProtocol.queryTimeReply(Date(), TimeZone.getDefault())
                    else -> null
                } ?: return
                val target = dataCharacteristic ?: return
                val written = runCatching {
                    BleCompat.writeCharacteristic(
                        gatt, target, reply, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    )
                }.getOrNull()
                log("$label: answered its request with ${reply.size} byte(s) (result $written).")
            }

            private fun scheduleAttempts(gatt: BluetoothGatt, label: String) {
                var delay = LISTEN_WINDOW_MILLIS
                attempts().forEachIndexed { index, attempt ->
                    runCatching {
                        scheduler.schedule({
                            if (closed.get()) return@schedule
                            val target = dataCharacteristic ?: return@schedule
                            // Built at fire time, not schedule time: a frame carrying the
                            // timestamp of when the button was pressed would set a clock that is
                            // already tens of seconds behind.
                            val frame = attempt.build()
                            lastAttempt = "after attempt ${index + 1}"
                            val writeType = if (attempt.acked) {
                                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            } else {
                                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                            }
                            val result = runCatching {
                                BleCompat.writeCharacteristic(gatt, target, frame, writeType)
                            }.getOrNull()
                            if (index == 0) devicesWritten.incrementAndGet()
                            log(
                                "$label: attempt ${index + 1}/${ATTEMPT_COUNT} - ${attempt.title}: " +
                                    "${frame.size} byte(s), submit result $result. ${hex(frame)}"
                            )
                        }, delay, TimeUnit.MILLISECONDS)
                    }
                    delay += ATTEMPT_GAP_MILLIS
                }
            }
        }

        val opened = runCatching {
            device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        }.getOrNull()
        if (opened == null) {
            log("$label: could not open a Bluetooth link.")
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

    /** One unsolicited shape worth ruling in or out, in the order they go on the wire. */
    private class Attempt(val title: String, val acked: Boolean, val build: () -> ByteArray)

    private fun attempts(): List<Attempt> = listOf(
        Attempt("SYNC_TIME epoch+rawOffset, unacked (the exact Carbit sendSyncTime shape)", acked = false) {
            EcBtpProtocol.syncTimeReply(System.currentTimeMillis(), TimeZone.getDefault().rawOffset)
        },
        Attempt("SYNC_TIME epoch+rawOffset, acked write (same frame, dash must answer the write)", acked = true) {
            EcBtpProtocol.syncTimeReply(System.currentTimeMillis(), TimeZone.getDefault().rawOffset)
        },
        Attempt("SYNC_TIME pure UTC epoch, no zone offset", acked = false) {
            EcBtpProtocol.syncTimeReply(System.currentTimeMillis(), 0)
        },
        Attempt("SYNC_TIME epoch+DST-aware offset", acked = false) {
            EcBtpProtocol.syncTimeReply(
                System.currentTimeMillis(),
                TimeZone.getDefault().getOffset(System.currentTimeMillis())
            )
        },
        Attempt("QUERY_TIME formatted local timestamp, pushed unasked", acked = false) {
            EcBtpProtocol.queryTimeReply(Date(), TimeZone.getDefault())
        }
    )

    private fun dataCharacteristicOf(service: BluetoothGattService): BluetoothGattCharacteristic? =
        EcBtpTimeLink.CHARACTERISTIC_UUIDS.firstNotNullOfOrNull { uuid ->
            runCatching { service.getCharacteristic(uuid) }.getOrNull()
        }

    /** The whole table, because the fix for a dash we have never met may hide in a UUID we have never met. */
    private fun dumpGattTable(label: String, gatt: BluetoothGatt) {
        val services = runCatching { gatt.services }.getOrNull().orEmpty()
        log("$label: GATT table, ${services.size} service(s):")
        services.forEach { service ->
            log("$label:   service ${service.uuid}")
            service.characteristics.forEach { characteristic ->
                log(
                    "$label:     char ${characteristic.uuid} " +
                        "[${describeProperties(characteristic.properties)}]"
                )
            }
        }
    }

    private fun describeProperties(properties: Int): String = buildList {
        if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("read")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("write")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("write-nr")
        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("notify")
        if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("indicate")
    }.ifEmpty { listOf("none") }.joinToString("+")

    private fun subscribe(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        runCatching { gatt.setCharacteristicNotification(characteristic, true) }
        val descriptor = runCatching { characteristic.getDescriptor(EcBtpTimeLink.CCC_UUID) }.getOrNull() ?: return
        runCatching {
            BleCompat.writeDescriptor(gatt, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }
    }

    private fun forget(gatt: BluetoothGatt) {
        synchronized(lock) { connections.remove(gatt) }
        runCatching { gatt.close() }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02X".format(it) }

    private companion object {
        /** Long enough for a slow dash to boot its Bluetooth after ignition; the rider is watching. */
        const val LAB_DURATION_MILLIS = 60_000L
        const val SCAN_WINDOW_MILLIS = 20_000L
        const val LISTEN_WINDOW_MILLIS = 5_000L
        const val ATTEMPT_GAP_MILLIS = 4_000L
        const val ATTEMPT_COUNT = 5
    }
}
