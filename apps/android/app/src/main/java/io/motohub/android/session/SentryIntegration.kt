package io.motohub.android.session

import android.content.Context
import android.util.Log
import io.motohub.android.BuildConfig
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid
import java.util.concurrent.atomic.AtomicInteger

/**
 * Centralized Sentry setup for CORE.
 *
 * The DSN is supplied by the build configuration, so public source builds can omit telemetry
 * while release/CI builds provide it through the ignored private properties file or environment.
 * Diagnostic errors are sent as already-redacted messages rather than raw Throwable objects: the
 * in-app log can contain connection details, and the application must never upload those values.
 */
object SentryIntegration {
    private const val LOG_TAG = "MotoHubSentry"
    private const val MAX_DIAGNOSTIC_EVENTS_PER_PROCESS = 50
    private val diagnosticEventsSent = AtomicInteger(0)
    @Volatile private var enabled = false

    fun initialize(context: Context) {
        val dsn = BuildConfig.SENTRY_DSN.trim()
        if (dsn.isEmpty()) return

        runCatching {
            SentryAndroid.init(context.applicationContext) { options ->
                options.dsn = dsn
                options.environment = "core"
                options.release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}"
                options.dist = BuildConfig.VERSION_CODE.toString()
                options.isSendDefaultPii = false
                options.isEnableAutoSessionTracking = true
                options.isDebug = BuildConfig.SENTRY_DEBUG
            }
            enabled = true
        }.onFailure { failure ->
            Log.e(LOG_TAG, "Sentry initialization failed; continuing without telemetry", failure)
        }
    }

    /** Sends a bounded, redacted diagnostic event without changing the local log behavior. */
    fun captureDiagnosticError(source: String, message: String) {
        if (!enabled) return
        if (diagnosticEventsSent.incrementAndGet() > MAX_DIAGNOSTIC_EVENTS_PER_PROCESS) return

        runCatching {
            Sentry.withScope { scope ->
                scope.setTag("motohub.source", source)
                scope.setTag("motohub.edition", "core")
                Sentry.captureMessage(message, SentryLevel.ERROR)
            }
        }.onFailure { failure ->
            Log.w(LOG_TAG, "Unable to send diagnostic event", failure)
        }
    }
}
