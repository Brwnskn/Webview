package com.example

import android.net.Uri
import androidx.compose.runtime.Immutable

enum class AsyncScanStatus(val label: String) {
    IDLE("Idle"),
    RUNNING("Scanning"),
    PAUSED("Paused"),
    COMPLETED("Completed"),
    ERROR("Error");

    companion object {
        fun fromRaw(raw: String): AsyncScanStatus {
            return when (raw.trim().uppercase()) {
                "RUNNING", "STARTED", "SCANNING" -> RUNNING
                "PAUSED", "PAUSE" -> PAUSED
                "COMPLETED", "DONE", "SUCCESS" -> COMPLETED
                "ERROR", "FAILED" -> ERROR
                else -> IDLE
            }
        }
    }
}

@Immutable
data class ScopeBatchProgress(
    val isActive: Boolean = false,
    val isPaused: Boolean = false,
    val currentBatch: Int = 0,
    val totalBatches: Int = 0,
    val itemStart: Int = 0,
    val itemEnd: Int = 0,
    val totalItems: Int = 0,
    val foundCount: Int = 0
) {
    val fraction: Float
        get() = if (totalBatches > 0) (currentBatch.toFloat() / totalBatches.toFloat()).coerceIn(0f, 1f) else 0f

    val statusText: String
        get() = when {
            isPaused && totalBatches > 0 ->
                "Paused at batch $currentBatch/$totalBatches (items $itemStart–$itemEnd) • $foundCount custom"
            isActive && totalBatches > 0 ->
                "Scanning batch $currentBatch/$totalBatches (items $itemStart–$itemEnd)…"
            totalBatches > 0 && currentBatch >= totalBatches ->
                "Completed $totalBatches/$totalBatches batches ($totalItems keys) • $foundCount custom"
            else -> ""
        }
}

enum class WizardStepBadge(val displayText: String) {
    PENDING("[PENDING]"),
    READY("[READY]"),
    CAPTURED("[CAPTURED ✓]")
}

enum class ConsoleTab {
    COORDS,
    ENTITIES,
    PROBER,
    LOGS_ASSETS
}

enum class ProberSubTab {
    TARGET_FINDER,
    STRUCT_PROBER,
    FULL_HEAP
}

enum class LogsAssetsSubTab {
    LOGS,
    ASSETS,
    STAGING
}

@Immutable
data class SavedSiteProfile(
    val hostKey: String,
    val lastUrl: String,
    val pointerCount: Int,
    val boundStructFieldCount: Int = 0,
    val pointerExpressions: List<String> = emptyList()
)

enum class Step2ScanMode {
    WASM_FLOAT_HEAP,
    JS_OBJECTS
}

enum class AssetCaptureStatus(val label: String) {
    OBSERVED("Observed"),
    CACHING("Syncing"),
    CAPTURED("Cached ✓"),
    FAILED("Metadata Only")
}

enum class LogCategory(val tag: String, val label: String) {
    ALL("ALL", "All"),
    SCOPE("SCOPE", "Scope"),
    DELTA("DELTA", "Delta"),
    ENTITY("ENTITY", "Entity"),
    WASM("WASM", "WASM"),
    ASSET("ASSET", "Asset"),
    JS("JS", "JS"),
    SYSTEM("SYS", "Sys"),
    ERROR("ERR", "Err");

    companion object {
        fun fromRaw(raw: String): LogCategory {
            val normalized = raw.trim().uppercase()
            return entries.firstOrNull {
                it != ALL && (it.name == normalized || it.tag == normalized)
            } ?: SYSTEM
        }
    }
}

@Immutable
data class TelemetryLogEntry(
    val id: Long,
    val timestamp: String,
    val epochMs: Long,
    val category: LogCategory,
    val summary: String,
    val payload: String
)

@Immutable
data class CandidateStagingItem(
    val path: String,
    val sourceStep: String,
    val detail: String,
    val scanNumber: Int,
    val seenCount: Int = 1,
    val isPinned: Boolean = false,
    val lastSeenTimestamp: String
)

@Immutable
data class InterceptedAsset(
    val url: String,
    val extension: String,
    val method: String,
    val timestamp: String,
    val isForMainFrame: Boolean,
    val fileName: String = "",
    val captureStatus: AssetCaptureStatus = AssetCaptureStatus.OBSERVED,
    val localRelativePath: String? = null,
    val httpStatus: Int? = null,
    val contentType: String = "",
    val byteSize: Long = 0L,
    val isTruncated: Boolean = false,
    val sha256Prefix: String = "",
    val magicSignature: String = "",
    val hexHeaderPreview: String = "",
    val entropyBits: Double = 0.0,
    val structuralSummary: String = "",
    val structuralDetails: String = ""
)

@Immutable
data class IsolatedCoordinatePath(
    val path: String,
    val baselineValue: Double,
    val currentValue: Double,
    val delta: Double
)

@Immutable
data class PlayerCoordinates(
    val path: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Immutable
data class DiscoveredEntitySample(
    val name: String,
    val id: String,
    val path: String,
    val worldX: Double,
    val worldY: Double,
    val worldZ: Double,
    val distanceToPlayer: Double,
    val screenX: Double,
    val screenY: Double,
    val inViewport: Boolean,
    val sampleKeys: List<String>,
    val coordByteAddress: Long = 0L,
    val heapIndex: Int = -1,
    val resolvedName: String? = null,
    val hp: Int? = null,
    val maxHp: Int? = null,
    val mp: Int? = null,
    val maxMp: Int? = null,
    val fp: Int? = null,
    val maxFp: Int? = null,
    val aggroRawValue: Int? = null,
    val isAggro: Boolean? = null,
    val isActiveGameTarget: Boolean = false
) {
    val selectionKey: String
        get() = "${path}_${id}"

    val displayName: String
        get() = resolvedName?.takeIf { it.isNotBlank() } ?: name

    val hasAnyVitals: Boolean
        get() = hp != null || mp != null || fp != null
}

@Immutable
data class ExportReportOutcome(
    val success: Boolean,
    val fileName: String,
    val displayPath: String,
    val contentUri: Uri? = null,
    val mimeType: String = "application/json",
    val errorMessage: String? = null
)

@Immutable
data class WasmNeighborFloat(
    val byteOffset: Int,
    val heapIndex: Int,
    val byteAddress: Long,
    val floatValue: Double,
    val intValue: Int,
    val changedFromPrev: Boolean = false,
    val changeCount: Int = 0
)

@Immutable
data class WasmCandidateAddress(
    val heapType: String,
    val index: Int,
    val byteAddress: Long,
    val currentValue: String
)

@Immutable
data class WasmHeapTrackerState(
    val targetValueInput: String = "",
    val isScanning: Boolean = false,
    val scanProgressPercent: Int = 0,
    val heapSourceLabel: String = "",
    val heapByteLength: Long = 0L,
    val initialSearchValue: Double? = null,
    val lastFilterValue: Double? = null,
    val totalMatches: Int = 0,
    val heap32MatchCount: Int = 0,
    val heapF32MatchCount: Int = 0,
    val filterRound: Int = 0,
    val survivingCandidates: List<WasmCandidateAddress> = emptyList(),
    val isolatedHeapType: String? = null,
    val isolatedIndex: Int? = null,
    val isolatedByteAddress: Long? = null,
    val isolatedCurrentValue: String = "",
    val isNeighborPolling: Boolean = false,
    val neighborPollTickCount: Int = 0,
    val neighborFloats: List<WasmNeighborFloat> = emptyList(),
    val statusMessage: String = "",
    val isExportingMemoryMap: Boolean = false,
    val lastMemoryMapExport: ExportReportOutcome? = null
)

@Immutable
data class WasmFloatDeltaCandidate(
    val heapIndex: Int,
    val byteAddress: Long,
    val baselineValue: Double,
    val currentValue: Double,
    val delta: Double,
    val clusterSize: Int = 1,
    val axisHint: String = "",
    val isPinned: Boolean = false,
    val changedFromPrev: Boolean = false,
    val changeCount: Int = 0
)

@Immutable
data class LockedWasmCoords(
    val xIndex: Int,
    val yIndex: Int?,
    val zIndex: Int?,
    val xByteAddress: Long,
    val yByteAddress: Long?,
    val zByteAddress: Long?,
    val xValue: Double,
    val yValue: Double,
    val zValue: Double,
    val lockedAtTimestamp: String = "",
    val liveTickCount: Int = 0,
    val isLivePolling: Boolean = true
)

enum class WasmPointerStatus(val label: String) {
    MOVING("MOVING ✓"),
    LIVE("LIVE"),
    ZERO("ZERO"),
    STALE("STALE"),
    PENDING("PENDING");

    companion object {
        fun fromRaw(raw: String, fallbackLive: Boolean): WasmPointerStatus {
            return when (raw.trim().uppercase()) {
                "MOVING" -> MOVING
                "LIVE" -> LIVE
                "ZERO" -> ZERO
                "STALE" -> STALE
                "PENDING" -> PENDING
                else -> if (fallbackLive) LIVE else STALE
            }
        }
    }
}

@Immutable
data class WasmPointerChain(
    val id: String,
    val depth: Int,
    val basePointerAddress: Long,
    val offsets: List<Int>,
    val resolvedTargetAddress: Long,
    val resolvedX: Double,
    val resolvedY: Double,
    val resolvedZ: Double,
    val isLikelyStatic: Boolean,
    val isVerifiedLive: Boolean = true,
    val expression: String,
    val isPinned: Boolean = false,
    val status: WasmPointerStatus = if (isVerifiedLive) WasmPointerStatus.LIVE else WasmPointerStatus.STALE,
    val watchBaselineX: Double? = null,
    val watchBaselineY: Double? = null,
    val watchBaselineZ: Double? = null,
    val changedFromPrev: Boolean = false,
    val changeCount: Int = 0,
    val lastDeltaMagnitude: Double = 0.0,
    val isSavedLocally: Boolean = false,
    val isStreamingToHud: Boolean = false
)

@Immutable
data class WasmFloatDeltaState(
    val isSamplingBaseline: Boolean = false,
    val isScanningDeltas: Boolean = false,
    val baselineCaptured: Boolean = false,
    val baselineSampleCount: Int = 0,
    val baselinePrunedJitterCount: Int = 0,
    val heapSourceLabel: String = "",
    val heapByteLength: Long = 0L,
    val totalDeltaMatches: Int = 0,
    val scanRound: Int = 0,
    val isLiveWatching: Boolean = false,
    val liveWatchTickCount: Int = 0,
    val candidates: List<WasmFloatDeltaCandidate> = emptyList(),
    val pinnedIndices: Set<Int> = emptySet(),
    val isWatcherExpanded: Boolean = true,
    val statusMessage: String = "",
    val lockedCoords: LockedWasmCoords? = null,
    val isScanningPointers: Boolean = false,
    val pointerScanTotalL1: Int = 0,
    val pointerScanTotalL2: Int = 0,
    val pointerChains: List<WasmPointerChain> = emptyList(),
    val pointerStatusMessage: String = "",
    val isWatchingPointers: Boolean = false,
    val pointerWatchTickCount: Int = 0,
    val activeHudPointerId: String? = null,
    val isAutoVerifyingOnReload: Boolean = false,
    val savedPointerCountForHost: Int = 0
)

enum class WasmStructFieldKind(
    val shortLabel: String,
    val jsonKey: String,
    val isNameField: Boolean = false,
    val isAggroField: Boolean = false
) {
    HP("HP", "hp"),
    MAX_HP("MaxHP", "maxHp"),
    MP("MP", "mp"),
    MAX_MP("MaxMP", "maxMp"),
    FP("FP", "fp"),
    MAX_FP("MaxFP", "maxFp"),
    NAME("Name", "name", isNameField = true),
    AGGRO("Aggro", "aggro", isAggroField = true);

    companion object {
        fun fromRaw(raw: String): WasmStructFieldKind =
            entries.firstOrNull {
                it.name.equals(raw.trim(), ignoreCase = true) ||
                    it.jsonKey.equals(raw.trim(), ignoreCase = true)
            } ?: HP
    }
}

enum class WasmStructValueType(val shortTag: String) {
    INT32("i32"),
    FLOAT32("f32"),
    INT16("i16"),
    UINT8("u8"),
    INLINE_UTF8("utf8"),
    CHAR_PTR("char*"),
    STD_STRING("std::str");

    companion object {
        fun fromRaw(raw: String): WasmStructValueType =
            entries.firstOrNull {
                it.name.equals(raw.trim(), ignoreCase = true) ||
                    it.shortTag.equals(raw.trim(), ignoreCase = true)
            } ?: INT32
    }
}

@Immutable
data class WasmStructFieldOffset(
    val relOffsetBytes: Int,
    val subOffsetBytes: Int? = null,
    val valueType: WasmStructValueType = WasmStructValueType.INT32,
    val expression: String = ""
) {
    val displayExpression: String
        get() {
            if (expression.isNotBlank()) return expression
            val relHex = formatSignedHex(relOffsetBytes)
            return if (subOffsetBytes != null) {
                "*($relHex)${formatSignedHex(subOffsetBytes)} [${valueType.shortTag}]"
            } else {
                "$relHex [${valueType.shortTag}]"
            }
        }

    companion object {
        fun formatSignedHex(bytes: Int): String {
            val sign = if (bytes < 0) "-" else "+"
            val absHex = kotlin.math.abs(bytes).toString(16).uppercase()
            return "${sign}0x$absHex"
        }
    }
}

@Immutable
data class WasmEntityStructSchema(
    val hp: WasmStructFieldOffset? = null,
    val maxHp: WasmStructFieldOffset? = null,
    val mp: WasmStructFieldOffset? = null,
    val maxMp: WasmStructFieldOffset? = null,
    val fp: WasmStructFieldOffset? = null,
    val maxFp: WasmStructFieldOffset? = null,
    val name: WasmStructFieldOffset? = null,
    val aggro: WasmStructFieldOffset? = null,
    val isSavedLocally: Boolean = false,
    val hostKey: String = ""
) {
    val hasAnyBound: Boolean
        get() = hp != null || maxHp != null || mp != null || maxMp != null || fp != null || maxFp != null || name != null || aggro != null

    val boundCount: Int
        get() = listOfNotNull(hp, maxHp, mp, maxMp, fp, maxFp, name, aggro).size

    fun fieldFor(kind: WasmStructFieldKind): WasmStructFieldOffset? = when (kind) {
        WasmStructFieldKind.HP -> hp
        WasmStructFieldKind.MAX_HP -> maxHp
        WasmStructFieldKind.MP -> mp
        WasmStructFieldKind.MAX_MP -> maxMp
        WasmStructFieldKind.FP -> fp
        WasmStructFieldKind.MAX_FP -> maxFp
        WasmStructFieldKind.NAME -> name
        WasmStructFieldKind.AGGRO -> aggro
    }

    fun withField(kind: WasmStructFieldKind, offset: WasmStructFieldOffset?): WasmEntityStructSchema = when (kind) {
        WasmStructFieldKind.HP -> copy(hp = offset)
        WasmStructFieldKind.MAX_HP -> copy(maxHp = offset)
        WasmStructFieldKind.MP -> copy(mp = offset)
        WasmStructFieldKind.MAX_MP -> copy(maxMp = offset)
        WasmStructFieldKind.FP -> copy(fp = offset)
        WasmStructFieldKind.MAX_FP -> copy(maxFp = offset)
        WasmStructFieldKind.NAME -> copy(name = offset)
        WasmStructFieldKind.AGGRO -> copy(aggro = offset)
    }
}

@Immutable
data class WasmStructProbeCandidate(
    val id: String,
    val relOffsetBytes: Int,
    val subOffsetBytes: Int? = null,
    val valueType: WasmStructValueType,
    val expression: String,
    val playerDisplayValue: String,
    val playerNumericValue: Double? = null,
    val adjacentMaxOffsetBytes: Int? = null,
    val adjacentMaxValue: Int? = null,
    val mobPreviews: List<String> = emptyList(),
    val nonZeroMobCount: Int = 0
) {
    fun toFieldOffset(): WasmStructFieldOffset = WasmStructFieldOffset(
        relOffsetBytes = relOffsetBytes,
        subOffsetBytes = subOffsetBytes,
        valueType = valueType,
        expression = expression
    )
}

@Immutable
data class PlayerVitalsSnapshot(
    val playerName: String? = null,
    val hp: Int? = null,
    val maxHp: Int? = null,
    val mp: Int? = null,
    val maxMp: Int? = null,
    val fp: Int? = null,
    val maxFp: Int? = null,
    val observedPeakHp: Int? = null,
    val observedPeakMp: Int? = null,
    val observedPeakFp: Int? = null,
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    val hasAnyContent: Boolean
        get() = !playerName.isNullOrBlank() || hp != null || mp != null || fp != null

    val effectiveMaxHp: Int?
        get() = maxHp?.takeIf { it > 0 } ?: observedPeakHp?.takeIf { it > 0 }

    val effectiveMaxMp: Int?
        get() = maxMp?.takeIf { it > 0 } ?: observedPeakMp?.takeIf { it > 0 }

    val effectiveMaxFp: Int?
        get() = maxFp?.takeIf { it > 0 } ?: observedPeakFp?.takeIf { it > 0 }
}

enum class WasmStructProbeAnchor {
    PLAYER,
    SELECTED_TARGET
}

enum class TargetScanFilterMode(val wireName: String, val label: String) {
    CHANGED_NON_ZERO("CHANGED_NON_ZERO", "Diff Mob"),
    UNCHANGED("UNCHANGED", "Same Mob"),
    DESELECTED_ZERO("DESELECTED_ZERO", "Deselected (0)"),
    SAME_TYPE_RESELECT("SAME_TYPE_RESELECT", "Same-Type Mob")
}

@Immutable
data class SelectedTargetCandidate(
    val slotByteAddress: Long,
    val slotHex: String,
    val currentValue: Long,
    val currentHex: String,
    val isPointerLike: Boolean,
    val nearPlayerOffsetBytes: Int? = null,
    val matchedEntityId: String? = null,
    val matchedEntityName: String? = null,
    val resolvedCoordAddress: Long = 0L,
    val resolvedX: Double? = null,
    val resolvedY: Double? = null,
    val resolvedZ: Double? = null,
    val previewName: String? = null,
    val previewHp: Int? = null,
    val previewMaxHp: Int? = null,
    val previewAggroRaw: Int? = null
)

@Immutable
data class LiveSelectedTargetSnapshot(
    val slotByteAddress: Long,
    val slotHex: String,
    val targetStructAddress: Long,
    val targetCoordAddress: Long,
    val hasTarget: Boolean,
    val matchedEntityId: String? = null,
    val resolvedName: String? = null,
    val hp: Int? = null,
    val maxHp: Int? = null,
    val aggroRawValue: Int? = null,
    val isAggro: Boolean? = null,
    val worldX: Double? = null,
    val worldY: Double? = null,
    val worldZ: Double? = null,
    val distanceToPlayer: Double? = null,
    val tickCount: Int = 0
) {
    val effectiveAnchorAddress: Long
        get() = when {
            targetCoordAddress > 0L -> targetCoordAddress
            targetStructAddress > 0L -> targetStructAddress
            else -> 0L
        }
}

@Immutable
data class WasmTargetScannerState(
    val isScanning: Boolean = false,
    val scanRound: Int = 0,
    val totalCandidates: Int = 0,
    val candidates: List<SelectedTargetCandidate> = emptyList(),
    val lockedSlotAddress: Long? = null,
    val isWatchingTarget: Boolean = false,
    val liveTarget: LiveSelectedTargetSnapshot? = null,
    val statusMessage: String = ""
)

@Immutable
data class WasmStructProberState(
    val isExpanded: Boolean = true,
    val probeAnchor: WasmStructProbeAnchor = WasmStructProbeAnchor.PLAYER,
    val activeField: WasmStructFieldKind = WasmStructFieldKind.HP,
    val queryInput: String = "",
    val isProbing: Boolean = false,
    val probeRound: Int = 0,
    val candidates: List<WasmStructProbeCandidate> = emptyList(),
    val schema: WasmEntityStructSchema = WasmEntityStructSchema(),
    val statusMessage: String = ""
)

@Immutable
data class DiagnosticsUiState(
    val urlInput: String = "",
    val activeUrl: String = "about:blank",
    val pageTitle: String = "",
    val isLoading: Boolean = false,
    val loadProgress: Int = 0,
    val isDesktopMode: Boolean = true,
    val scanStatus: AsyncScanStatus = AsyncScanStatus.IDLE,
    val scanStatusDetail: String = "",
    val activeConsoleTab: ConsoleTab = ConsoleTab.COORDS,
    val activeProberSubTab: ProberSubTab = ProberSubTab.STRUCT_PROBER,
    val activeLogsAssetsSubTab: LogsAssetsSubTab = LogsAssetsSubTab.LOGS,
    val savedSiteProfiles: List<SavedSiteProfile> = emptyList(),
    // Multi-Scan Candidate Staging Buffer
    val scanCounter: Int = 0,
    val stagingBuffer: List<CandidateStagingItem> = emptyList(),
    val showPinnedOnly: Boolean = false,
    val stagingSearchQuery: String = "",
    // Step 1 state + Batched Chunked Scanning (100 items / batch) + Manual Path Inspector
    val step1Completed: Boolean = false,
    val customGlobals: List<String> = emptyList(),
    val detectedGameNamespaces: List<String> = emptyList(),
    val step1DurationMs: Double? = null,
    val scopeBatchProgress: ScopeBatchProgress = ScopeBatchProgress(),
    val manualPropertyPathInput: String = "",
    val lastManualQuerySummary: String = "",
    // Step 2 state + Live 200ms Reference Polling (JS Objects & WASM Float Heap Mode)
    val step2ScanMode: Step2ScanMode = Step2ScanMode.WASM_FLOAT_HEAP,
    val baselineCaptured: Boolean = false,
    val baselineMetricCount: Int = 0,
    val isolatedPaths: List<IsolatedCoordinatePath> = emptyList(),
    val discoveredPlayerPath: String? = null,
    val playerPropertyKeys: String = "",
    val livePlayerCoords: PlayerCoordinates? = null,
    val livePollActive: Boolean = false,
    val livePollTickCount: Int = 0,
    val wasmFloatDelta: WasmFloatDeltaState = WasmFloatDeltaState(),
    // Step 3 state (Visual Entity Inspection Console + WASM Nearby Entity Scanner + Read-Only Location Tracker)
    val discoveredEntityArrayPath: String? = null,
    val entityProjectionMode: String = "",
    val entityViewportWidth: Int = 0,
    val entityViewportHeight: Int = 0,
    val discoveredEntities: List<DiscoveredEntitySample> = emptyList(),
    val lastEntityCount: Int = 0,
    val isWasmEntitiesLivePolling: Boolean = false,
    val wasmEntityLiveTickCount: Int = 0,
    val trackedEntityKey: String? = null,
    val playerVitals: PlayerVitalsSnapshot? = null,
    val targetScanner: WasmTargetScannerState = WasmTargetScannerState(),
    val structProber: WasmStructProberState = WasmStructProberState(),
    // Step 4/5 Passive asset & console state
    val wasmHeap: WasmHeapTrackerState = WasmHeapTrackerState(),
    val interceptedAssets: List<InterceptedAsset> = emptyList(),
    val logs: List<TelemetryLogEntry> = emptyList(),
    val selectedFilter: LogCategory = LogCategory.ALL,
    val isJsDrawerOpen: Boolean = false,
    val manualJsInput: String = "",
    val isConsoleExpanded: Boolean = false,
    val consoleSplitFraction: Float = 0.48f,
    val isExporting: Boolean = false,
    val lastExport: ExportReportOutcome? = null,
    val isExportingLogDump: Boolean = false,
    val lastLogDumpExport: ExportReportOutcome? = null,
    val estimatedFps: Int? = null,
    val canvasCount: Int = 0
) {
    val pinnedCandidateCount: Int
        get() = stagingBuffer.count { it.isPinned }

    val cachedAssetCount: Int
        get() = interceptedAssets.count { it.captureStatus == AssetCaptureStatus.CAPTURED }

    val totalCachedAssetBytes: Long
        get() = interceptedAssets.filter { it.captureStatus == AssetCaptureStatus.CAPTURED }.sumOf { it.byteSize }

    val hasLiveWasmPlayerAnchor: Boolean
        get() = wasmFloatDelta.lockedCoords != null ||
            wasmFloatDelta.activeHudPointerId != null ||
            wasmFloatDelta.pointerChains.any {
                (it.status == WasmPointerStatus.MOVING || it.status == WasmPointerStatus.LIVE) &&
                    it.resolvedTargetAddress > 0L
            }

    val step1Badge: WizardStepBadge
        get() = if (step1Completed && (customGlobals.isNotEmpty() || canvasCount > 0)) {
            WizardStepBadge.CAPTURED
        } else {
            WizardStepBadge.READY
        }

    val step2Badge: WizardStepBadge
        get() = when {
            (!discoveredPlayerPath.isNullOrBlank() && livePlayerCoords != null) ||
                wasmFloatDelta.lockedCoords != null ||
                wasmFloatDelta.activeHudPointerId != null ||
                wasmFloatDelta.pointerChains.any { it.status == WasmPointerStatus.MOVING } ||
                (wasmFloatDelta.candidates.isNotEmpty() && (wasmFloatDelta.isLiveWatching || wasmFloatDelta.pinnedIndices.isNotEmpty())) ->
                WizardStepBadge.CAPTURED
            step1Completed || baselineCaptured || wasmFloatDelta.baselineCaptured || wasmFloatDelta.pointerChains.isNotEmpty() ->
                WizardStepBadge.READY
            else -> WizardStepBadge.PENDING
        }

    val step3Badge: WizardStepBadge
        get() = when {
            !discoveredEntityArrayPath.isNullOrBlank() && discoveredEntities.isNotEmpty() -> WizardStepBadge.CAPTURED
            !discoveredPlayerPath.isNullOrBlank() || hasLiveWasmPlayerAnchor -> WizardStepBadge.READY
            else -> WizardStepBadge.PENDING
        }

    val step4Badge: WizardStepBadge
        get() = when {
            lastExport?.success == true -> WizardStepBadge.CAPTURED
            step2Badge == WizardStepBadge.CAPTURED && step3Badge == WizardStepBadge.CAPTURED -> WizardStepBadge.READY
            else -> WizardStepBadge.PENDING
        }
}

sealed interface UrlValidationResult {
    data class Valid(val normalizedUrl: String) : UrlValidationResult
    data class Invalid(val reason: String) : UrlValidationResult
}
