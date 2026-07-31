package io.motohub.android.session

import android.content.Context
import android.os.Build
import android.util.Log
import io.motohub.android.BuildConfig
import io.motohub.android.feature.settings.MotoHubSettings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LogLevel {
    DEBUG,
    INFO,
    WARNING,
    ERROR
}

data class ProjectionEvent(
    val timestampMillis: Long,
    val source: String,
    val message: String,
    val level: LogLevel = LogLevel.INFO,
    /**
     * In-memory only, never persisted: assigned fresh on every app run so it's
     * guaranteed unique within [ProjectionEventLog.events], unlike timestamp+message,
     * which collide easily (e.g. two identical "PXC event received" heartbeats logged
     * in the same millisecond) and crashed the log screen's LazyColumn on scroll when
     * used as its key.
     */
    val sequence: Long = 0
)

/** Persistent application-wide diagnostic log exposed directly in the UI. */
object ProjectionEventLog {
    // Oldest events drop automatically once this is exceeded (a ring buffer, not a manual
    // clear) - lowered from 2_500 after a very long/verbose session made the log screen
    // heavy enough to hang while scrolling. 800 is still generous for troubleshooting a
    // single connect/stream session.
    private const val MAX_EVENTS = 800
    private const val FILE_NAME = "moto-hub-diagnostics.log"
    private val lock = Any()

    /**
     * The authoritative event store, guarded by [lock]. An ArrayDeque ring instead of the
     * immutable list it used to be: `mutableEvents.value + event` copied all 800 elements on
     * EVERY log call, and the callers include the hottest paths in the app - the T-Box event
     * callback logs one debug line per MEDIA_CONTROL event, touch moves included, so a drag on
     * the TFT paid a full list copy plus a synchronous file write per sample, on the transport's
     * own callback thread. [mutableEvents] is now a throttled SNAPSHOT of this ring, published
     * by the writer thread at [FLUSH_DELAY_MS] cadence; [exportText] reads the ring directly, so
     * exports and the IPC snapshot stay complete and current regardless of the throttle.
     */
    private val ring = ArrayDeque<ProjectionEvent>(MAX_EVENTS)

    /** Encoded lines waiting for the writer thread; guarded by [lock], swapped at flush. */
    private var pendingLines = StringBuilder()
    private val flushScheduled = java.util.concurrent.atomic.AtomicBoolean(false)
    private val writer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "MotoHubLogWriter").apply { isDaemon = true }
    }

    private val mutableEvents = MutableStateFlow<List<ProjectionEvent>>(emptyList())
    val events: StateFlow<List<ProjectionEvent>> = mutableEvents.asStateFlow()
    private var logFile: File? = null
    private var appContext: Context? = null
    private val sequenceCounter = java.util.concurrent.atomic.AtomicLong(0)

    fun initialize(context: Context) {
        appContext = context.applicationContext
        synchronized(lock) {
            if (logFile != null) return
            logFile = File(context.applicationContext.filesDir, FILE_NAME)
            val restored = runCatching {
                logFile?.takeIf(File::exists)
                    ?.readLines(Charsets.UTF_8)
                    ?.mapNotNull(::decodeLine)
                    ?.takeLast(MAX_EVENTS)
                    .orEmpty()
            }.getOrElse { emptyList() }
                .map { it.copy(sequence = sequenceCounter.incrementAndGet()) }
            ring.clear()
            ring.addAll(restored)
            mutableEvents.value = restored
        }
        record(
            source = "APP",
            message = "Process started: MOTO-HUB ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), " +
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
                "${Build.MANUFACTURER} ${Build.MODEL}."
        )
    }

    fun record(
        source: String,
        message: String,
        level: LogLevel = LogLevel.INFO,
        throwable: Throwable? = null
    ) {
        // Master switch (Settings > Diagnostics > Enable logging): when off, nothing is
        // written anywhere - not Logcat, not memory, not the log file - not just the
        // verbose extras. appContext is only null for the instant before initialize()
        // runs, which never calls record(); defaulting to enabled there is unreachable
        // in practice but keeps this fail-open rather than silently swallowing events.
        val loggingEnabled = appContext?.let(MotoHubSettings::loggingEnabled) ?: true
        if (!loggingEnabled) return
        val detail = redact(buildString {
            append(message)
            if (throwable != null) {
                append("\n")
                append(Log.getStackTraceString(throwable))
            }
        })
        if (level == LogLevel.ERROR) {
            SentryIntegration.captureDiagnosticError(source, detail)
        }
        when (level) {
            LogLevel.DEBUG -> Log.d(LOG_TAG, "$source: $detail")
            LogLevel.INFO -> Log.i(LOG_TAG, "$source: $detail")
            LogLevel.WARNING -> Log.w(LOG_TAG, "$source: $detail")
            LogLevel.ERROR -> Log.e(LOG_TAG, "$source: $detail")
        }
        val event = ProjectionEvent(System.currentTimeMillis(), source, detail, level, sequenceCounter.incrementAndGet())
        synchronized(lock) {
            // Cheap by design: ring append + trim and an encoded pending line. The list copy
            // for the UI and the file write both happen on the writer thread at flush time -
            // never on the caller's thread, which can be the T-Box callback delivering a touch.
            ring.addLast(event)
            if (ring.size > MAX_EVENTS) ring.removeFirst()
            pendingLines.append(encodeLine(event)).append('\n')
        }
        scheduleFlush()
    }

    /** Batches file writes and snapshot publication; one task per [FLUSH_DELAY_MS] window. */
    private fun scheduleFlush() {
        if (!flushScheduled.compareAndSet(false, true)) return
        runCatching {
            writer.schedule(::flush, FLUSH_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.onFailure { flushScheduled.set(false) }
    }

    private fun flush() {
        // Reopen the gate BEFORE swapping: an event recorded after the swap below must be able
        // to schedule the next window even while this one is still writing.
        flushScheduled.set(false)
        val lines: String
        val snapshot: List<ProjectionEvent>
        val file: File?
        synchronized(lock) {
            lines = pendingLines.toString()
            if (pendingLines.isNotEmpty()) pendingLines = StringBuilder()
            snapshot = ring.toList()
            file = logFile
        }
        mutableEvents.value = snapshot
        if (file == null || lines.isEmpty()) return
        runCatching {
            if (snapshot.size == MAX_EVENTS && file.length() > MAX_FILE_BYTES) {
                rewrite(file, snapshot)
            } else {
                file.appendText(lines, Charsets.UTF_8)
            }
        }.onFailure { Log.e(LOG_TAG, "Unable to persist diagnostic log", it) }
    }

    fun clear() {
        synchronized(lock) {
            ring.clear()
            pendingLines = StringBuilder()
            mutableEvents.value = emptyList()
        }
        // On the writer thread so it cannot interleave with a flush already in progress.
        runCatching { writer.execute { logFile?.runCatching { writeText("", Charsets.UTF_8) } } }
        record("LOG", "Diagnostic log cleared by the user.")
    }

    fun debug(source: String, message: String) = record(source, message, LogLevel.DEBUG)

    fun warning(source: String, message: String, throwable: Throwable? = null) =
        record(source, message, LogLevel.WARNING, throwable)

    fun error(source: String, message: String, throwable: Throwable? = null) =
        record(source, message, LogLevel.ERROR, throwable)

    fun exportText(): String {
        // From the ring, not the throttled StateFlow snapshot: an export (share sheet, the
        // companion's IPC log mirror) must contain everything recorded up to this instant.
        val snapshot = synchronized(lock) { ring.toList() }
        return buildString {
            appendLine("MOTO-HUB diagnostics")
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Entries: ${snapshot.size}")
            appendLine("Generated: ${formatTime(System.currentTimeMillis())}")
            appendLine("----------------------------------------")
            snapshot.forEach { event ->
                append(formatTime(event.timestampMillis))
                append("  ")
                append(event.level.name.padEnd(7))
                append("  ")
                append(event.source)
                append(": ")
                appendLine(event.message)
            }
        }
    }

    private fun rewrite(file: File, events: List<ProjectionEvent>) {
        file.writeText(events.joinToString(separator = "\n", postfix = "\n", transform = ::encodeLine))
    }

    private fun encodeLine(event: ProjectionEvent): String = listOf(
        event.timestampMillis.toString(),
        event.level.name,
        encode(event.source),
        encode(event.message)
    ).joinToString("\t")

    private fun decodeLine(line: String): ProjectionEvent? = runCatching {
        val fields = line.split('\t', limit = 4)
        if (fields.size != 4) return@runCatching null
        ProjectionEvent(
            timestampMillis = fields[0].toLong(),
            level = LogLevel.valueOf(fields[1]),
            source = decode(fields[2]),
            message = decode(fields[3])
        )
    }.getOrNull()

    private fun encode(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decode(value: String): String =
        String(Base64.getDecoder().decode(value), Charsets.UTF_8)

    internal fun redact(value: String): String = value
        .replace(SECRET_PATTERN, "$1=<redacted>")
        .replace(BEARER_TOKEN_PATTERN, "Bearer <redacted>")
        .replace(API_KEY_LITERAL_PATTERN, "<redacted-key>")
        .replace(MAC_ADDRESS_PATTERN, "<redacted-mac>")
        .replace(IPV4_ADDRESS_PATTERN, "<redacted-ip>")
        .take(MAX_MESSAGE_CHARS)

    private fun formatTime(timestampMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timestampMillis))

    private const val LOG_TAG = "MotoHubProjection"
    /**
     * Batching window for the writer thread: one file write and one UI snapshot per window
     * instead of one per event. Short enough that the log screen still reads as live and a
     * crash loses at most a quarter-second of tail; the logcat mirror in [record] is
     * synchronous anyway, so nothing is lost for adb-attached debugging.
     */
    private const val FLUSH_DELAY_MS = 250L
    private const val MAX_FILE_BYTES = 2L * 1024L * 1024L
    // A single event could reach this size (e.g. a raw CLIENT_INFO JSON dump under verbose
    // T-Box logging) - 16_000 let one entry's Text composable choke the log screen's layout
    // pass badly enough to hang while scrolling. 2_000 is still ample to read a JSON blob or
    // stack trace; anything genuinely longer is truncated rather than rendered whole.
    private const val MAX_MESSAGE_CHARS = 2_000
    // Quote-tolerant so this also catches quoted-JSON shapes like "btPin": "1234" or
    // "pwd":"1234", not just bare key=value/key: value - needed now that verbose T-Box
    // logging (Settings > Diagnostics) can log a raw CLIENT_INFO JSON blob, which carries
    // btPin among other fields.
    // huid/uuid joined when verbose T-Box logging became the default: the raw CLIENT_INFO dump
    // carries both, they identify the dashboard hardware forever, and logs get pasted into
    // public Discord threads. Redacting them here is what made the default flip safe.
    // api_key/authorization/token joined the list when the AI assistant landed in the companion
    // edition: the rider enters a provider key that, unlike the T-Box credentials, is worth real
    // money if it leaks through a shared diagnostic log. Kept identical in both editions on
    // purpose - this file is shared, and a redaction rule that exists in only one of them is a
    // trap the next person to move code between them will fall into.
    private val SECRET_PATTERN = Regex(
        "(?i)\"?(password|pwd|passphrase|psk|btpin|bt_pin|api_?key|api-key|authorization|" +
            "access_?token|refresh_?token|token|secret|huid|uuid)\"?\\s*[:=]\\s*\"?[^\\s,;\"]+\"?"
    )
    // SECRET_PATTERN stops the value at the first space, so "Authorization: Bearer sk-..." would
    // otherwise redact only the word "Bearer" and leave the token itself in the clear.
    private val BEARER_TOKEN_PATTERN = Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]{8,}")
    // Last resort for a key pasted somewhere with no surrounding label at all. Covers the
    // sk-/sk-proj-/sk-or- prefixes shared by OpenAI and the compatible providers.
    private val API_KEY_LITERAL_PATTERN = Regex("\\bsk-[A-Za-z0-9_-]{12,}\\b")
    // Catches MAC addresses and literal IPv4 addresses wherever they surface in a message
    // or exception text (e.g. "failed to connect to /192.168.49.1"), not just at known
    // call sites - ARCHITECTURE.md commits to replacing IP/MAC values with placeholders
    // in the exported log.
    private val MAC_ADDRESS_PATTERN = Regex(
        "\\b[0-9A-Fa-f]{2}(?:[:-][0-9A-Fa-f]{2}){5}\\b"
    )
    private val IPV4_ADDRESS_PATTERN = Regex(
        "\\b(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d?\\d)\\b"
    )
}
