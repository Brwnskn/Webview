package com.example

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Secure native telemetry bridge exposed to the diagnostic WebView execution context.
 *
 * Security & performance controls:
 * - Strictly observational: receives read-only telemetry logs, batched global scan progress,
 *   200 ms live player coordinates, WASM neighbor/float snapshots, and user-initiated report exports.
 * - Strict payload length bounds, control-character sanitization, and sliding-window rate
 *   limiting prevent memory exhaustion or log flooding from web pages.
 * - Export writes are gated behind an explicit user-triggered arming flag ([armExport]).
 * - High-frequency 200 ms coordinate & WASM watcher updates marshal cleanly onto the main thread.
 */
class TelemetryBridge(
    private val onTelemetryReceived: (category: String, payload: String) -> Unit,
    private val onReportPayloadReceived: (jsonContent: String) -> Unit,
    private val onScanStatusChanged: (status: AsyncScanStatus, detail: String) -> Unit = { _, _ -> },
    private val onPlayerCoordsUpdated: (path: String, x: Double, y: Double, z: Double) -> Unit = { _, _, _, _ -> },
    private val onBatchProgressChanged: (progress: ScopeBatchProgress) -> Unit = {},
    private val onWasmNeighborsUpdated: (jsonPayload: String) -> Unit = {},
    private val onWasmFloatCandidatesUpdated: (jsonPayload: String) -> Unit = {},
    private val onWasmPointersUpdated: (jsonPayload: String) -> Unit = {},
    private val onWasmEntitiesUpdated: (jsonPayload: String) -> Unit = {},
    private val onWasmTargetUpdated: (jsonPayload: String) -> Unit = {}
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val exportArmed = AtomicBoolean(false)
    private val lastLogWindowStartMs = AtomicLong(0L)
    private val logsInCurrentWindow = AtomicLong(0L)
    private val lastCoordUpdateMs = AtomicLong(0L)
    private val lastWasmNeighborMs = AtomicLong(0L)
    private val lastWasmFloatMs = AtomicLong(0L)
    private val lastWasmPointerMs = AtomicLong(0L)
    private val lastWasmEntityMs = AtomicLong(0L)
    private val lastWasmTargetMs = AtomicLong(0L)

    fun armExport() {
        exportArmed.set(true)
    }

    fun disarmExport(): Boolean = exportArmed.getAndSet(false)

    @JavascriptInterface
    fun logTelemetry(category: String, payload: String) {
        if (!allowLogUnderRateLimit()) return

        val safeCategory = sanitizeCategory(category)
        val safePayload = sanitizePayload(payload, MAX_LOG_PAYLOAD_CHARS)

        mainHandler.post {
            onTelemetryReceived(safeCategory, safePayload)
        }
    }

    @JavascriptInterface
    fun updateScanStatus(status: String, detail: String) {
        val parsedStatus = AsyncScanStatus.fromRaw(status)
        val safeDetail = sanitizePayload(detail, MAX_STATUS_DETAIL_CHARS)
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()

        mainHandler.post {
            onScanStatusChanged(parsedStatus, safeDetail)
        }
    }

    /**
     * Receives live 200 ms player coordinates from Step 2B direct JS reference polling
     * or locked `window.HEAPF32` coordinate watchers.
     */
    @JavascriptInterface
    fun updatePlayerCoords(path: String, x: Double, y: Double, z: Double) {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return

        val now = System.currentTimeMillis()
        val prev = lastCoordUpdateMs.get()
        if (now - prev < MIN_COORD_INTERVAL_MS) return
        if (!lastCoordUpdateMs.compareAndSet(prev, now)) return

        val safePath = sanitizePayload(path, MAX_PATH_CHARS)
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
            .ifEmpty { "window" }

        mainHandler.post {
            onPlayerCoordsUpdated(safePath, x, y, z)
        }
    }

    /**
     * Receives batched Step 1 global scan progress updates (100 items per async batch).
     */
    @JavascriptInterface
    fun updateBatchProgress(
        currentBatch: Int,
        totalBatches: Int,
        itemStart: Int,
        itemEnd: Int,
        totalItems: Int,
        foundCount: Int,
        isActive: Boolean,
        isPaused: Boolean
    ) {
        val progress = ScopeBatchProgress(
            isActive = isActive,
            isPaused = isPaused,
            currentBatch = currentBatch.coerceAtLeast(0),
            totalBatches = totalBatches.coerceAtLeast(0),
            itemStart = itemStart.coerceAtLeast(0),
            itemEnd = itemEnd.coerceAtLeast(0),
            totalItems = totalItems.coerceAtLeast(0),
            foundCount = foundCount.coerceAtLeast(0)
        )
        mainHandler.post {
            onBatchProgressChanged(progress)
        }
    }

    /**
     * Receives live 200 ms ±4..±16 byte neighboring WASM float snapshots.
     */
    @JavascriptInterface
    fun updateWasmNeighbors(jsonPayload: String) {
        val now = System.currentTimeMillis()
        val prev = lastWasmNeighborMs.get()
        if (now - prev < MIN_WASM_TICK_INTERVAL_MS) return
        if (!lastWasmNeighborMs.compareAndSet(prev, now)) return

        val safeJson = sanitizePayload(jsonPayload, MAX_WASM_TICK_PAYLOAD_CHARS)
        mainHandler.post {
            onWasmNeighborsUpdated(safeJson)
        }
    }

    /**
     * Receives live 200 ms WASM Float Heap Delta Watcher candidate & locked coordinate ticks.
     */
    @JavascriptInterface
    fun updateWasmFloatCandidates(jsonPayload: String) {
        val now = System.currentTimeMillis()
        val prev = lastWasmFloatMs.get()
        if (now - prev < MIN_WASM_TICK_INTERVAL_MS) return
        if (!lastWasmFloatMs.compareAndSet(prev, now)) return

        val safeJson = sanitizePayload(jsonPayload, MAX_WASM_TICK_PAYLOAD_CHARS)
        mainHandler.post {
            onWasmFloatCandidatesUpdated(safeJson)
        }
    }

    /**
     * Receives live 200 ms WASM Pointer Chain Watch ticks (MOVING / LIVE / ZERO / STALE + live XYZ).
     */
    @JavascriptInterface
    fun updateWasmPointers(jsonPayload: String) {
        val now = System.currentTimeMillis()
        val prev = lastWasmPointerMs.get()
        if (now - prev < MIN_WASM_TICK_INTERVAL_MS) return
        if (!lastWasmPointerMs.compareAndSet(prev, now)) return

        val safeJson = sanitizePayload(jsonPayload, MAX_WASM_TICK_PAYLOAD_CHARS)
        mainHandler.post {
            onWasmPointersUpdated(safeJson)
        }
    }

    /**
     * Receives live 500 ms WASM Nearby Entity position refresh ticks.
     */
    @JavascriptInterface
    fun updateWasmEntities(jsonPayload: String) {
        val now = System.currentTimeMillis()
        val prev = lastWasmEntityMs.get()
        if (now - prev < MIN_WASM_ENTITY_INTERVAL_MS) return
        if (!lastWasmEntityMs.compareAndSet(prev, now)) return

        val safeJson = sanitizePayload(jsonPayload, MAX_LOG_PAYLOAD_CHARS)
        mainHandler.post {
            onWasmEntitiesUpdated(safeJson)
        }
    }

    /**
     * Receives live 200 ms read-only Selected Target pointer & decoded mob stats snapshots.
     */
    @JavascriptInterface
    fun updateWasmTarget(jsonPayload: String) {
        val now = System.currentTimeMillis()
        val prev = lastWasmTargetMs.get()
        if (now - prev < MIN_WASM_TICK_INTERVAL_MS) return
        if (!lastWasmTargetMs.compareAndSet(prev, now)) return

        val safeJson = sanitizePayload(jsonPayload, MAX_WASM_TICK_PAYLOAD_CHARS)
        mainHandler.post {
            onWasmTargetUpdated(safeJson)
        }
    }

    @JavascriptInterface
    fun exportReport(jsonContent: String) {
        if (!exportArmed.compareAndSet(true, false)) {
            mainHandler.post {
                onTelemetryReceived(
                    LogCategory.ERROR.tag,
                    "Blocked unsolicited exportReport() call from page context."
                )
            }
            return
        }

        val boundedPayload = jsonContent.take(MAX_EXPORT_PAYLOAD_CHARS).trim()
        val validatedJson = try {
            JSONObject(boundedPayload).toString()
        } catch (_: Exception) {
            JSONObject().put("rawMetricsError", "Invalid JSON payload received from WebView").toString()
        }

        mainHandler.post {
            onReportPayloadReceived(validatedJson)
        }
    }

    private fun allowLogUnderRateLimit(): Boolean {
        val now = System.currentTimeMillis()
        val windowStart = lastLogWindowStartMs.get()
        if (now - windowStart > RATE_LIMIT_WINDOW_MS) {
            lastLogWindowStartMs.set(now)
            logsInCurrentWindow.set(1L)
            return true
        }
        return logsInCurrentWindow.incrementAndGet() <= MAX_LOGS_PER_WINDOW
    }

    private fun sanitizeCategory(raw: String?): String {
        if (raw.isNullOrBlank()) return LogCategory.SYSTEM.tag
        return raw.trim()
            .uppercase()
            .filter { it in 'A'..'Z' || it in '0'..'9' || it == '_' }
            .take(MAX_CATEGORY_CHARS)
            .ifEmpty { LogCategory.SYSTEM.tag }
    }

    private fun sanitizePayload(raw: String?, maxChars: Int): String {
        if (raw == null) return ""
        val trimmed = if (raw.length > maxChars) {
            raw.substring(0, maxChars) + "… [truncated]"
        } else {
            raw
        }
        return trimmed.replace('\u0000', ' ')
    }

    companion object {
        const val BRIDGE_NAME = "TelemetryBridge"
        private const val MAX_CATEGORY_CHARS = 16
        private const val MAX_STATUS_DETAIL_CHARS = 120
        private const val MAX_PATH_CHARS = 180
        private const val MAX_LOG_PAYLOAD_CHARS = 65_536
        private const val MAX_WASM_TICK_PAYLOAD_CHARS = 32_768
        private const val MAX_EXPORT_PAYLOAD_CHARS = 1_500_000
        private const val RATE_LIMIT_WINDOW_MS = 1_000L
        private const val MAX_LOGS_PER_WINDOW = 60L
        private const val MIN_COORD_INTERVAL_MS = 120L
        private const val MIN_WASM_TICK_INTERVAL_MS = 120L
        private const val MIN_WASM_ENTITY_INTERVAL_MS = 250L
    }
}
