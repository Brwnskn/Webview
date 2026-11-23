package com.example

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

sealed interface DiagnosticsEffect {
    data class LoadUrl(val url: String) : DiagnosticsEffect
    data object ReloadWebView : DiagnosticsEffect
    data class ApplyUserAgent(val desktopMode: Boolean, val reloadAfter: Boolean) : DiagnosticsEffect
    data class EvaluateJs(val script: String) : DiagnosticsEffect
    data class ShowToast(val message: String) : DiagnosticsEffect
    data class ShareReport(val uri: Uri, val fileName: String, val mimeType: String = "application/json") : DiagnosticsEffect
}

class DiagnosticsViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(DiagnosticsUiState())
    val uiState: StateFlow<DiagnosticsUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<DiagnosticsEffect>(extraBufferCapacity = 16)
    val effects: SharedFlow<DiagnosticsEffect> = _effects.asSharedFlow()

    private val logIdGenerator = AtomicLong(1L)
    private val seenAssetUrls = LinkedHashSet<String>()
    private var pendingExportJob: Job? = null
    private var pendingExportContext: Context? = null
    private var sitePointerStore: SitePointerStore? = null
    private var siteStructSchemaStore: SiteStructSchemaStore? = null
    private var lastValidUrl: String = ""

    fun attachContext(context: Context) {
        val appCtx = context.applicationContext
        var initialized = false
        if (sitePointerStore == null) {
            sitePointerStore = SitePointerStore(appCtx)
            initialized = true
        }
        if (siteStructSchemaStore == null) {
            siteStructSchemaStore = SiteStructSchemaStore(appCtx)
            initialized = true
        }
        refreshSavedSiteProfiles()
        if (initialized) {
            val currentUrl = _uiState.value.activeUrl
            if (currentUrl != "about:blank") {
                lastValidUrl = currentUrl
                restoreSavedPointersForUrl(currentUrl, autoVerify = false)
            }
        }
    }

    internal fun setSitePointerStoreForTesting(store: SitePointerStore) {
        sitePointerStore = store
        refreshSavedSiteProfiles()
    }

    internal fun setSiteStructSchemaStoreForTesting(store: SiteStructSchemaStore) {
        siteStructSchemaStore = store
        refreshSavedSiteProfiles()
    }

    private fun effectivePersistenceUrl(state: DiagnosticsUiState = _uiState.value): String {
        val candidates = listOf(state.activeUrl, lastValidUrl, state.urlInput)
        return candidates.firstOrNull { SitePointerStore.normalizeHostKey(it) != null }.orEmpty()
    }

    private fun refreshSavedSiteProfiles() {
        val pointerRecords = sitePointerStore?.listSavedSites().orEmpty()
        val schemaCounts = siteStructSchemaStore?.listSavedSchemaHosts().orEmpty()

        val profilesByHost = LinkedHashMap<String, SavedSiteProfile>()
        pointerRecords.forEach { rec ->
            profilesByHost[rec.hostKey] = SavedSiteProfile(
                hostKey = rec.hostKey,
                lastUrl = rec.lastUrl,
                pointerCount = rec.pointers.size,
                boundStructFieldCount = schemaCounts[rec.hostKey] ?: 0,
                pointerExpressions = rec.pointers.take(3).map { it.expression }
            )
        }
        schemaCounts.forEach { (host, count) ->
            if (!profilesByHost.containsKey(host)) {
                profilesByHost[host] = SavedSiteProfile(
                    hostKey = host,
                    lastUrl = "https://$host",
                    pointerCount = 0,
                    boundStructFieldCount = count,
                    pointerExpressions = emptyList()
                )
            }
        }
        _uiState.update { it.copy(savedSiteProfiles = profilesByHost.values.toList()) }
    }

    fun onOpenSavedSiteProfile(profile: SavedSiteProfile) {
        val target = profile.lastUrl.ifBlank { "https://${profile.hostKey}" }
        _uiState.update {
            it.copy(
                urlInput = target,
                activeConsoleTab = ConsoleTab.COORDS
            )
        }
        onLoadUrlRequested()
    }

    fun onDeleteSavedSiteProfile(hostKey: String) {
        sitePointerStore?.clearPointersForHost(hostKey)
        siteStructSchemaStore?.clearSchemaForHost(hostKey)
        refreshSavedSiteProfiles()
        val activeHost = SitePointerStore.normalizeHostKey(_uiState.value.activeUrl)
        if (activeHost == hostKey) {
            _uiState.update { state ->
                state.copy(
                    wasmFloatDelta = state.wasmFloatDelta.copy(
                        savedPointerCountForHost = 0,
                        pointerChains = state.wasmFloatDelta.pointerChains.map {
                            it.copy(isSavedLocally = false, isPinned = false)
                        }
                    ),
                    structProber = state.structProber.copy(
                        schema = WasmEntityStructSchema(hostKey = hostKey, isSavedLocally = false)
                    )
                )
            }
        }
        _effects.tryEmit(DiagnosticsEffect.ShowToast("Cleared saved pointers & offsets for $hostKey"))
    }

    fun onUrlInputChanged(input: String) {
        _uiState.update { it.copy(urlInput = input) }
    }

    fun onLoadUrlRequested() {
        val raw = _uiState.value.urlInput
        when (val validation = normalizeAndValidateUrl(raw)) {
            is UrlValidationResult.Valid -> {
                val targetUrl = validation.normalizedUrl
                if (SitePointerStore.normalizeHostKey(targetUrl) != null) {
                    lastValidUrl = targetUrl
                }
                val savedForTarget = sitePointerStore?.loadPointersForUrl(targetUrl).orEmpty()
                val savedSchema = siteStructSchemaStore?.loadSchemaForUrl(targetUrl) ?: WasmEntityStructSchema()
                seenAssetUrls.clear()
                _uiState.update {
                    it.copy(
                        urlInput = if (targetUrl == "about:blank") "" else targetUrl,
                        activeUrl = targetUrl,
                        scanStatus = AsyncScanStatus.IDLE,
                        scanStatusDetail = "",
                        step1Completed = false,
                        customGlobals = emptyList(),
                        detectedGameNamespaces = emptyList(),
                        step1DurationMs = null,
                        scopeBatchProgress = ScopeBatchProgress(),
                        lastManualQuerySummary = "",
                        baselineCaptured = false,
                        baselineMetricCount = 0,
                        isolatedPaths = emptyList(),
                        discoveredPlayerPath = null,
                        playerPropertyKeys = "",
                        livePlayerCoords = null,
                        livePollActive = false,
                        livePollTickCount = 0,
                        discoveredEntityArrayPath = null,
                        entityProjectionMode = "",
                        entityViewportWidth = 0,
                        entityViewportHeight = 0,
                        discoveredEntities = emptyList(),
                        lastEntityCount = 0,
                        isWasmEntitiesLivePolling = false,
                        wasmEntityLiveTickCount = 0,
                        playerVitals = null,
                        structProber = WasmStructProberState(
                            schema = savedSchema,
                            statusMessage = if (savedSchema.hasAnyBound) {
                                "Loaded ${savedSchema.boundCount} saved struct offset(s) for ${savedSchema.hostKey}."
                            } else ""
                        ),
                        wasmHeap = WasmHeapTrackerState(),
                        wasmFloatDelta = WasmFloatDeltaState(
                            pointerChains = savedForTarget,
                            savedPointerCountForHost = savedForTarget.size,
                            pointerStatusMessage = if (savedForTarget.isNotEmpty()) {
                                "Loaded ${savedForTarget.size} saved pointer(s) for this site. Will auto-verify once WASM loads."
                            } else ""
                        ),
                        interceptedAssets = emptyList(),
                        estimatedFps = null,
                        canvasCount = 0
                    )
                }
                appendLog(LogCategory.SYSTEM, "Navigating to $targetUrl", targetUrl)
                _effects.tryEmit(DiagnosticsEffect.LoadUrl(targetUrl))
            }
            is UrlValidationResult.Invalid -> {
                appendLog(LogCategory.ERROR, "Blocked invalid URL", validation.reason)
                _effects.tryEmit(DiagnosticsEffect.ShowToast(validation.reason))
            }
        }
    }

    fun onReloadRequested() {
        val current = _uiState.value.activeUrl
        _uiState.update {
            it.copy(
                livePollActive = false,
                isWasmEntitiesLivePolling = false,
                wasmHeap = it.wasmHeap.copy(isNeighborPolling = false, isScanning = false),
                wasmFloatDelta = it.wasmFloatDelta.copy(
                    isLiveWatching = false,
                    isSamplingBaseline = false,
                    isScanningDeltas = false,
                    isScanningPointers = false,
                    isWatchingPointers = false,
                    isAutoVerifyingOnReload = false,
                    lockedCoords = it.wasmFloatDelta.lockedCoords?.copy(isLivePolling = false)
                )
            )
        }
        appendLog(LogCategory.SYSTEM, "Reloading $current", current)
        _effects.tryEmit(DiagnosticsEffect.ReloadWebView)
    }

    fun onToggleDesktopMode() {
        val nextDesktop = !_uiState.value.isDesktopMode
        _uiState.update { it.copy(isDesktopMode = nextDesktop) }
        val modeLabel = if (nextDesktop) "Desktop User-Agent" else "Mobile User-Agent"
        appendLog(LogCategory.SYSTEM, "Switched to $modeLabel", modeLabel)
        _effects.tryEmit(
            DiagnosticsEffect.ApplyUserAgent(
                desktopMode = nextDesktop,
                reloadAfter = _uiState.value.activeUrl != "about:blank"
            )
        )
    }

    fun onSelectConsoleTab(tab: ConsoleTab) {
        _uiState.update { state ->
            // Automatically expand from Mini HUD when user taps a tab
            val nextSplit = if (state.consoleSplitFraction < 0.25f) 0.48f else state.consoleSplitFraction
            state.copy(
                activeConsoleTab = tab,
                consoleSplitFraction = nextSplit,
                isConsoleExpanded = nextSplit >= 0.72f
            )
        }
    }

    fun onSelectProberSubTab(subTab: ProberSubTab) {
        _uiState.update { it.copy(activeProberSubTab = subTab) }
    }

    fun onSelectLogsAssetsSubTab(subTab: LogsAssetsSubTab) {
        _uiState.update { it.copy(activeLogsAssetsSubTab = subTab) }
    }

    fun onSetConsoleSplitFraction(fraction: Float) {
        val clamped = fraction.coerceIn(0.14f, 0.88f)
        _uiState.update {
            it.copy(
                consoleSplitFraction = clamped,
                isConsoleExpanded = clamped >= 0.72f
            )
        }
    }

    fun onSnapConsoleMini() {
        onSetConsoleSplitFraction(0.15f)
    }

    fun onSnapConsoleSplit() {
        onSetConsoleSplitFraction(0.48f)
    }

    fun onSnapConsoleFull() {
        onSetConsoleSplitFraction(0.88f)
    }

    fun onPageStarted(url: String) {
        if (SitePointerStore.normalizeHostKey(url) != null) {
            lastValidUrl = url
        }
        _uiState.update {
            it.copy(
                activeUrl = url,
                urlInput = if (url == "about:blank") it.urlInput else url,
                isLoading = true,
                loadProgress = 10,
                livePollActive = false,
                isWasmEntitiesLivePolling = false,
                wasmHeap = it.wasmHeap.copy(isNeighborPolling = false, isScanning = false),
                wasmFloatDelta = it.wasmFloatDelta.copy(
                    isLiveWatching = false,
                    isSamplingBaseline = false,
                    isScanningDeltas = false,
                    isScanningPointers = false,
                    isWatchingPointers = false,
                    isAutoVerifyingOnReload = false,
                    lockedCoords = it.wasmFloatDelta.lockedCoords?.copy(isLivePolling = false)
                )
            )
        }
    }

    fun onPageFinished(url: String, title: String?) {
        if (SitePointerStore.normalizeHostKey(url) != null) {
            lastValidUrl = url
        }
        _uiState.update {
            it.copy(
                activeUrl = url,
                pageTitle = title.orEmpty(),
                isLoading = false,
                loadProgress = 100
            )
        }
        if (url != "about:blank") {
            appendLog(
                LogCategory.SYSTEM,
                "Page ready: ${title?.takeIf { it.isNotBlank() } ?: url}",
                url
            )
            restoreSavedPointersForUrl(url, autoVerify = true)
        }
    }

    private fun restoreSavedPointersForUrl(url: String, autoVerify: Boolean) {
        val store = sitePointerStore
        val savedFromDisk = store?.loadPointersForUrl(url).orEmpty()
        val hostName = SitePointerStore.normalizeHostKey(url).orEmpty()
        val savedSchema = siteStructSchemaStore?.loadSchemaForUrl(url)
            ?: _uiState.value.structProber.schema.takeIf { it.hostKey == hostName }
            ?: WasmEntityStructSchema(hostKey = hostName)

        // Restore & sync saved entity struct schema (HP / MP / FP / Name) immediately on load
        _uiState.update { state ->
            val effectiveSchema = if (savedSchema.hasAnyBound) savedSchema else state.structProber.schema
            state.copy(
                structProber = state.structProber.copy(
                    schema = effectiveSchema,
                    statusMessage = if (savedSchema.hasAnyBound && state.structProber.statusMessage.isBlank()) {
                        "Restored ${savedSchema.boundCount} saved struct offset(s) for $hostName."
                    } else state.structProber.statusMessage
                )
            )
        }
        val activeSchema = _uiState.value.structProber.schema
        if (activeSchema.hasAnyBound) {
            _effects.tryEmit(
                DiagnosticsEffect.EvaluateJs(
                    WasmStructProberScripts.buildSyncStructSchemaScript(
                        SiteStructSchemaStore.serializeSchema(activeSchema)
                    )
                )
            )
        }

        val currentState = _uiState.value
        val existingChains = currentState.wasmFloatDelta.pointerChains
        val sameHost = SitePointerStore.normalizeHostKey(currentState.activeUrl) ==
            SitePointerStore.normalizeHostKey(url)

        val merged = buildList {
            val seen = LinkedHashSet<String>()
            savedFromDisk.forEach { saved ->
                if (seen.add(saved.id)) {
                    add(
                        saved.copy(
                            isPinned = true,
                            isSavedLocally = true,
                            status = WasmPointerStatus.PENDING,
                            isVerifiedLive = false,
                            changedFromPrev = false,
                            changeCount = 0,
                            lastDeltaMagnitude = 0.0,
                            isStreamingToHud = (currentState.wasmFloatDelta.activeHudPointerId == saved.id)
                        )
                    )
                }
            }
            if (sameHost) {
                existingChains.forEach { ex ->
                    if (seen.add(ex.id)) {
                        add(
                            ex.copy(
                                status = WasmPointerStatus.PENDING,
                                isVerifiedLive = false,
                                changedFromPrev = false,
                                changeCount = 0,
                                lastDeltaMagnitude = 0.0
                            )
                        )
                    }
                }
            }
        }

        if (merged.isEmpty()) {
            _uiState.update { state ->
                state.copy(
                    wasmFloatDelta = state.wasmFloatDelta.copy(
                        savedPointerCountForHost = 0,
                        isAutoVerifyingOnReload = false
                    )
                )
            }
            return
        }

        val hudId = currentState.wasmFloatDelta.activeHudPointerId
            ?.takeIf { id -> merged.any { it.id == id } }
        val statusMsg = if (autoVerify) {
            "Auto-verifying ${merged.size} saved pointer(s) for $hostName (waiting for WASM heap)…"
        } else {
            "Loaded ${savedFromDisk.size} saved pointer(s) for $hostName."
        }

        _uiState.update { state ->
            state.copy(
                wasmFloatDelta = state.wasmFloatDelta.copy(
                    pointerChains = merged,
                    savedPointerCountForHost = savedFromDisk.size,
                    isAutoVerifyingOnReload = autoVerify,
                    pointerStatusMessage = statusMsg
                )
            )
        }

        if (autoVerify) {
            appendLog(
                LogCategory.WASM,
                "Auto-verifying ${merged.size} saved pointer chain(s) for $hostName…",
                "Waiting for WASM HEAPF32 initialization, then starting 200ms Live Watch"
            )
            val payloadJson = buildPointerChainsPayloadJson(merged)
            _effects.tryEmit(
                DiagnosticsEffect.EvaluateJs(
                    WasmPointerAndEntityScripts.buildAutoVerifySavedPointersScript(
                        chainsJsonArray = payloadJson,
                        hudPointerId = hudId
                    )
                )
            )
        }
    }

    fun onProgressChanged(progress: Int) {
        _uiState.update {
            it.copy(
                loadProgress = progress.coerceIn(0, 100),
                isLoading = progress in 1..99
            )
        }
    }

    fun onWebViewError(description: String, failingUrl: String?) {
        appendLog(
            LogCategory.ERROR,
            "WebView load error: $description",
            failingUrl ?: _uiState.value.activeUrl
        )
    }

    fun onBlockedSchemeAttempt(url: String) {
        appendLog(LogCategory.ERROR, "Blocked disallowed URI scheme navigation", url)
    }

    fun onScanStatusChanged(status: AsyncScanStatus, detail: String) {
        _uiState.update {
            it.copy(
                scanStatus = status,
                scanStatusDetail = detail
            )
        }
    }

    fun onBatchProgressChanged(progress: ScopeBatchProgress) {
        _uiState.update { state ->
            val nextStatus = when {
                progress.isPaused -> AsyncScanStatus.PAUSED
                progress.isActive -> AsyncScanStatus.RUNNING
                progress.totalBatches > 0 && progress.currentBatch >= progress.totalBatches -> AsyncScanStatus.COMPLETED
                else -> state.scanStatus
            }
            state.copy(
                scopeBatchProgress = progress,
                scanStatus = nextStatus,
                scanStatusDetail = progress.statusText.ifBlank { state.scanStatusDetail }
            )
        }
    }

    fun onPlayerCoordsUpdated(path: String, x: Double, y: Double, z: Double) {
        _uiState.update { state ->
            state.copy(
                discoveredPlayerPath = state.discoveredPlayerPath ?: path,
                livePollActive = true,
                livePollTickCount = state.livePollTickCount + 1,
                livePlayerCoords = PlayerCoordinates(
                    path = path,
                    x = x,
                    y = y,
                    z = z,
                    updatedAtMs = System.currentTimeMillis()
                )
            )
        }
    }

    // Multi-Scan Candidate Staging Buffer Actions
    fun onTogglePinCandidate(path: String) {
        _uiState.update { state ->
            val updated = state.stagingBuffer.map { item ->
                if (item.path == path) item.copy(isPinned = !item.isPinned) else item
            }
            state.copy(stagingBuffer = sortStagingBuffer(updated))
        }
    }

    fun onTogglePinnedOnlyFilter() {
        _uiState.update { it.copy(showPinnedOnly = !it.showPinnedOnly) }
    }

    fun onStagingSearchQueryChanged(query: String) {
        _uiState.update { it.copy(stagingSearchQuery = query) }
    }

    fun onClearUnpinnedCandidates() {
        _uiState.update { state ->
            state.copy(stagingBuffer = state.stagingBuffer.filter { it.isPinned })
        }
    }

    fun onPinExternalPath(path: String, sourceStep: String, detail: String) {
        val nowTs = formatTimestamp(System.currentTimeMillis())
        _uiState.update { state ->
            val nextScan = state.scanCounter.coerceAtLeast(1)
            val merged = mergeIntoStagingBuffer(
                existing = state.stagingBuffer,
                incoming = listOf(path to detail),
                sourceStep = sourceStep,
                scanNumber = nextScan,
                timestamp = nowTs,
                forcePin = true
            )
            state.copy(stagingBuffer = merged)
        }
        _effects.tryEmit(DiagnosticsEffect.ShowToast("Pinned to Staging: $path"))
    }

    fun onLockCandidateForLivePolling(candidatePath: String) {
        onScanStatusChanged(AsyncScanStatus.RUNNING, "Binding live 200ms ref: $candidatePath")
        appendLog(
            LogCategory.DELTA,
            "Binding live 200ms reference to $candidatePath…",
            candidatePath
        )
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(DiagnosticsScripts.buildLockCandidateRefScript(candidatePath)))
    }

    // Step 1: Non-Blocking 100-Item Batched Global Variable Inspection (with Pause / Resume)
    fun runStep1InspectScope() {
        _uiState.update {
            it.copy(
                scanStatus = AsyncScanStatus.RUNNING,
                scanStatusDetail = "Starting batched global scan (100/batch)…",
                scopeBatchProgress = ScopeBatchProgress(
                    isActive = true,
                    isPaused = false,
                    currentBatch = 1,
                    totalBatches = 1,
                    itemStart = 1,
                    itemEnd = 100,
                    totalItems = 100,
                    foundCount = 0
                )
            )
        }
        appendLog(
            LogCategory.SCOPE,
            "Step 1: Starting batched global scan (100 items/batch)…",
            "Processing window keys in staggered async batches of 100 items; custom globals stream live into Staging"
        )
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(DiagnosticsScripts.buildInspectGlobalScopeScript()))
    }

    fun pauseStep1BatchScan() {
        _uiState.update { state ->
            state.copy(
                scanStatus = AsyncScanStatus.PAUSED,
                scopeBatchProgress = state.scopeBatchProgress.copy(isPaused = true)
            )
        }
        appendLog(LogCategory.SCOPE, "Step 1: Paused batched scan", "You can search/pin staged items and resume anytime.")
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(DiagnosticsScripts.buildPauseScopeScanScript()))
    }

    fun resumeStep1BatchScan() {
        _uiState.update { state ->
            state.copy(
                scanStatus = AsyncScanStatus.RUNNING,
                scopeBatchProgress = state.scopeBatchProgress.copy(isPaused = false)
            )
        }
        appendLog(LogCategory.SCOPE, "Step 1: Resumed batched scan", "Continuing remaining 100-item batches…")
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(DiagnosticsScripts.buildResumeScopeScanScript()))
    }

    // Step 1 Manual Property Inspector (fallback for closure-scoped or known custom paths)
    fun onManualPathInputChanged(input: String) {
        _uiState.update { it.copy(manualPropertyPathInput = input) }
    }

    fun onQueryManualPropertyPath(overridePath: String? = null) {
        val targetPath = (overridePath ?: _uiState.value.manualPropertyPathInput).trim()
        if (targetPath.isEmpty()) {
            _effects.tryEmit(DiagnosticsEffect.ShowToast("Enter a path like game.player or window.AppInstance"))
            return
        }
        if (overridePath != null) {
            _uiState.update { it.copy(manualPropertyPathInput = targetPath) }
        }
        onScanStatusChanged(AsyncScanStatus.RUNNING, "Querying path: $targetPath…")
        appendLog(
            LogCategory.SCOPE,
            "Manual Path Query: $targetPath",
            "Inspecting property path directly on window and staging if resolved"
        )
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(DiagnosticsScripts.buildQueryPropertyPathScript(targetPath)))
    }

    // Step 2A: Capture Standing Baseline
    fun runStep2ACaptureBaseline() {
        onScanStatusChanged(AsyncScanStatus.RUNNING, "Step 2A: Capturing standing baseline…")
        appendLog(
            LogCategory.DELTA,
            "Step 2A: Capturing standing baseline…",
            "Snapshotting numeric properties across filtered custom globals"
        )
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(DiagnosticsScripts.buildCaptureBaselineScript()))
    }

    // Step 2B: Isolate Player Coords + start 200ms direct object-reference polling
    fun runStep2BComputeDelta() {
        onScanStatusChanged(AsyncScanStatus.RUNNING, "Step 2B: Isolating live reference…")
        appendLog(
            LogCategory.DELTA,
            "Step 2B: Isolating live player object reference…",
            "Comparing live runtime state against standing baseline and starting 200ms direct-reference polling"
        )
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(DiagnosticsScripts.buildComputeDeltaScript()))
    }

    // Step 3: Player-Anchored Visual Entity Discovery (WASM HEAPF32 Nearby Entity Scan + JS Fallback)
    fun runStep3ScanEntities() {
        val state = _uiState.value
        val fd = state.wasmFloatDelta
        val activeHudChain = fd.activeHudPointerId?.let { id ->
            fd.pointerChains.firstOrNull { it.id == id && it.resolvedTargetAddress > 0L && it.status != WasmPointerStatus.STALE && it.status != WasmPointerStatus.ZERO }
        }
        val bestLiveChain = activeHudChain
            ?: fd.pointerChains.firstOrNull { it.status == WasmPointerStatus.MOVING && it.resolvedTargetAddress > 0L }
            ?: fd.pointerChains.firstOrNull { it.status == WasmPointerStatus.LIVE && it.resolvedTargetAddress > 0L }
        val locked = fd.lockedCoords
        val anchor = state.discoveredPlayerPath

        val isWasmAnchor = bestLiveChain != null ||
            locked != null ||
            (anchor != null && (anchor.startsWith("window.HEAPF32") || anchor.startsWith("WASM_PTR:")))

        if (isWasmAnchor) {
            val playerAddr = bestLiveChain?.resolvedTargetAddress
                ?: locked?.xByteAddress
                ?: 0L
            val px = bestLiveChain?.resolvedX
                ?: locked?.xValue
                ?: state.livePlayerCoords?.x
                ?: 0.0
            val py = bestLiveChain?.resolvedY
                ?: locked?.yValue
                ?: state.livePlayerCoords?.y
                ?: 0.0
            val pz = bestLiveChain?.resolvedZ
                ?: locked?.zValue
                ?: state.livePlayerCoords?.z
                ?: 0.0
            val structOffset = bestLiveChain?.offsets?.lastOrNull() ?: 0

            if (playerAddr <= 0L && px == 0.0 && py == 0.0 && pz == 0.0) {
                val msg = "Stream a live WASM pointer chain to HUD or lock coordinates first before scanning WASM entities."
                appendLog(LogCategory.ENTITY, msg, msg)
                _effects.tryEmit(DiagnosticsEffect.ShowToast(msg))
                return
            }

            val hexPlayer = formatHexAddress(playerAddr)
            onScanStatusChanged(AsyncScanStatus.RUNNING, "Step 3 (WASM): Scanning HEAPF32 near $hexPlayer…")
            appendLog(
                LogCategory.ENTITY,
                "Step 3 (WASM): Scanning HEAPF32 for nearby entities around $hexPlayer…",
                "Player=(${String.format(Locale.US, "%.2f", px)}, ${String.format(Locale.US, "%.2f", py)}, ${String.format(Locale.US, "%.2f", pz)}), structOffset=+0x${structOffset.toString(16).uppercase(Locale.US)}, radius=1.5..250u"
            )
            _effects.tryEmit(
                DiagnosticsEffect.EvaluateJs(
                    WasmPointerAndEntityScripts.buildScanWasmNearbyEntitiesScript(
                        playerByteAddress = playerAddr,
                        playerX = px,
                        playerY = py,
                        playerZ = pz,
                        structCoordOffset = structOffset
                    )
                )
            )
            return
        }

        if (anchor.isNullOrBlank()) {
            val msg = "Complete Step 2B (or stream a live WASM pointer / lock coords) so Step 3 can anchor to your player coordinates."
            appendLog(LogCategory.ENTITY, msg, msg)
            _effects.tryEmit(DiagnosticsEffect.ShowToast(msg))
            return
        }
        onScanStatusChanged(AsyncScanStatus.RUNNING, "Step 3: Climbing parent tree from $anchor…")
        appendLog(
            LogCategory.ENTITY,
            "Step 3: Scanning sibling entity collections anchored to $anchor…",
            "Climbing 1–3 parent levels up from $anchor to inspect sibling Arrays/Maps/Sets & project to 2D canvas"
        )
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(DiagnosticsScripts.buildScanEntitiesScript(anchor)))
    }

    fun onToggleWasmEntitiesLiveRefresh() {
        val state = _uiState.value
        if (state.isWasmEntitiesLivePolling) {
            _uiState.update { it.copy(isWasmEntitiesLivePolling = false) }
            _effects.tryEmit(DiagnosticsEffect.EvaluateJs(WasmPointerAndEntityScripts.buildStopWasmEntitiesLiveRefreshScript()))
            appendLog(LogCategory.ENTITY, "Stopped 500ms live WASM entity refresh", "Paused entity coordinate polling")
        } else {
            if (state.discoveredEntities.isEmpty()) {
                _effects.tryEmit(DiagnosticsEffect.ShowToast("Run Step 3 (Scan Nearby Entities) first."))
                return
            }
            _uiState.update { it.copy(isWasmEntitiesLivePolling = true, wasmEntityLiveTickCount = 0) }
            _effects.tryEmit(DiagnosticsEffect.EvaluateJs(WasmPointerAndEntityScripts.buildStartWasmEntitiesLiveRefreshScript()))
            appendLog(LogCategory.ENTITY, "Started 500ms live WASM entity position refresh", "Tracking ${state.discoveredEntities.size} nearby WASM entity structs")
        }
    }

    fun onWasmEntitiesUpdatedFromBridge(jsonPayload: String) {
        val json = try {
            JSONObject(jsonPayload)
        } catch (_: Exception) {
            return
        }
        if (json.optString("stage", "") != "WASM_ENTITIES_TICK") return
        val tick = json.optInt("tick", 1)
        val parsed = parseWasmEntitiesArray(json.optJSONArray("entities"))
        _uiState.update { state ->
            state.copy(
                discoveredEntities = parsed,
                lastEntityCount = parsed.size,
                isWasmEntitiesLivePolling = true,
                wasmEntityLiveTickCount = tick
            )
        }
    }

    fun onToggleTrackEntityForLocationVerify(entityKey: String) {
        _uiState.update { state ->
            val nextKey = if (state.trackedEntityKey == entityKey) null else entityKey
            state.copy(trackedEntityKey = nextKey)
        }
    }

    fun onClearTrackedEntity() {
        _uiState.update { it.copy(trackedEntityKey = null) }
    }

    // Read-Only Selected Target Differential Scanner (Mob A -> Diff Mob / Same Mob / Deselected 0 / Same-Type)
    fun onCaptureSelectedTarget() {
        val playerAddr = resolveActivePlayerCoordByteAddress(_uiState.value)
        _uiState.update { state ->
            state.copy(
                scanStatus = AsyncScanStatus.RUNNING,
                scanStatusDetail = "Target Finder: capturing candidate pointer slots…",
                targetScanner = state.targetScanner.copy(
                    isScanning = true,
                    statusMessage = "Capturing Mob A candidate pointer & ID slots in HEAPU32…"
                )
            )
        }
        _effects.tryEmit(
            DiagnosticsEffect.EvaluateJs(
                WasmSelectedTargetScripts.buildCaptureSelectedTargetScript(playerByteAddress = playerAddr)
            )
        )
    }

    fun onFilterSelectedTarget(mode: TargetScanFilterMode) {
        val state = _uiState.value
        if (state.targetScanner.scanRound <= 0) {
            val msg = "Select Mob A in-game and tap '1. Scan Mob A' first."
            _uiState.update { it.copy(targetScanner = it.targetScanner.copy(statusMessage = msg)) }
            _effects.tryEmit(DiagnosticsEffect.ShowToast(msg))
            return
        }
        val playerAddr = resolveActivePlayerCoordByteAddress(state)
        _uiState.update { current ->
            current.copy(
                scanStatus = AsyncScanStatus.RUNNING,
                scanStatusDetail = "Target Finder: filtering (${mode.label})…",
                targetScanner = current.targetScanner.copy(
                    isScanning = true,
                    statusMessage = "Filtering ${current.targetScanner.totalCandidates} slot(s) for ${mode.label}…"
                )
            )
        }
        _effects.tryEmit(
            DiagnosticsEffect.EvaluateJs(
                WasmSelectedTargetScripts.buildFilterSelectedTargetScript(
                    mode = mode,
                    playerByteAddress = playerAddr
                )
            )
        )
    }

    fun onLockSelectedTargetSlot(slotByteAddress: Long) {
        if (slotByteAddress <= 0L) return
        val playerAddr = resolveActivePlayerCoordByteAddress(_uiState.value)
        val slotHex = formatHexAddress(slotByteAddress)
        _uiState.update { state ->
            state.copy(
                targetScanner = state.targetScanner.copy(
                    lockedSlotAddress = slotByteAddress,
                    isWatchingTarget = true,
                    statusMessage = "Watching selected target slot $slotHex live (200ms read-only)…"
                )
            )
        }
        _effects.tryEmit(
            DiagnosticsEffect.EvaluateJs(
                WasmSelectedTargetScripts.buildLockTargetSlotScript(
                    slotByteAddress = slotByteAddress,
                    playerByteAddress = playerAddr
                )
            )
        )
        appendLog(
            LogCategory.WASM,
            "Locked Selected Target slot $slotHex (200ms read-only watch)",
            "Slot byte address=$slotByteAddress"
        )
    }

    fun onStopSelectedTargetWatch() {
        _uiState.update { state ->
            state.copy(
                targetScanner = state.targetScanner.copy(
                    isWatchingTarget = false,
                    statusMessage = "Selected target live watch paused."
                )
            )
        }
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(WasmSelectedTargetScripts.buildStopTargetWatchScript()))
    }

    fun onResetSelectedTargetScan() {
        _uiState.update { state ->
            state.copy(
                targetScanner = WasmTargetScannerState(
                    statusMessage = "Target Finder reset. Select Mob A in-game and tap '1. Scan Mob A'."
                ),
                discoveredEntities = state.discoveredEntities.map { it.copy(isActiveGameTarget = false) }
            )
        }
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(WasmSelectedTargetScripts.buildResetTargetScanScript()))
    }

    fun onWasmTargetUpdatedFromBridge(jsonPayload: String) {
        val json = try {
            JSONObject(jsonPayload)
        } catch (_: Exception) {
            return
        }
        if (json.optString("stage", "") != "WASM_TARGET_WATCH_TICK") return

        fun optNullableInt(key: String): Int? =
            if (json.has(key) && !json.isNull(key)) json.optInt(key) else null
        fun optNullableDouble(key: String): Double? =
            if (json.has(key) && !json.isNull(key)) json.optDouble(key).takeIf { it.isFinite() } else null
        fun optNullableBool(key: String): Boolean? =
            if (json.has(key) && !json.isNull(key)) json.optBoolean(key) else null
        fun optNullableString(key: String): String? =
            if (json.has(key) && !json.isNull(key)) json.optString(key, "").takeIf { it.isNotBlank() } else null

        val snap = LiveSelectedTargetSnapshot(
            slotByteAddress = json.optLong("slotByteAddress", 0L),
            slotHex = json.optString("slotHex", formatHexAddress(json.optLong("slotByteAddress", 0L))),
            targetStructAddress = json.optLong("targetStructAddress", 0L),
            targetCoordAddress = json.optLong("targetCoordAddress", 0L),
            hasTarget = json.optBoolean("hasTarget", false),
            matchedEntityId = optNullableString("matchedEntityId"),
            resolvedName = optNullableString("resolvedName"),
            hp = optNullableInt("hp"),
            maxHp = optNullableInt("maxHp"),
            aggroRawValue = optNullableInt("aggroRawValue"),
            isAggro = optNullableBool("isAggro"),
            worldX = optNullableDouble("worldX"),
            worldY = optNullableDouble("worldY"),
            worldZ = optNullableDouble("worldZ"),
            distanceToPlayer = optNullableDouble("distanceToPlayer"),
            tickCount = json.optInt("tick", 1)
        )

        _uiState.update { state ->
            val updatedEntities = state.discoveredEntities.map { ent ->
                val matchesTarget = snap.hasTarget && (
                    (snap.targetCoordAddress > 0L && ent.coordByteAddress > 0L && kotlin.math.abs(ent.coordByteAddress - snap.targetCoordAddress) <= 64L) ||
                        (snap.matchedEntityId != null && ent.id == snap.matchedEntityId)
                    )
                if (matchesTarget) {
                    ent.copy(
                        isActiveGameTarget = true,
                        resolvedName = snap.resolvedName ?: ent.resolvedName,
                        hp = snap.hp ?: ent.hp,
                        maxHp = snap.maxHp ?: ent.maxHp,
                        aggroRawValue = snap.aggroRawValue ?: ent.aggroRawValue,
                        isAggro = snap.isAggro ?: ent.isAggro
                    )
                } else if (ent.isActiveGameTarget) {
                    ent.copy(isActiveGameTarget = false)
                } else {
                    ent
                }
            }
            state.copy(
                discoveredEntities = updatedEntities,
                targetScanner = state.targetScanner.copy(
                    isWatchingTarget = true,
                    lockedSlotAddress = snap.slotByteAddress.takeIf { it > 0L } ?: state.targetScanner.lockedSlotAddress,
                    liveTarget = snap
                )
            )
        }
    }

    fun onProbeSelectedTargetInStructProber(field: WasmStructFieldKind) {
        _uiState.update { state ->
            state.copy(
                activeConsoleTab = ConsoleTab.PROBER,
                activeProberSubTab = ProberSubTab.STRUCT_PROBER,
                structProber = state.structProber.copy(
                    probeAnchor = WasmStructProbeAnchor.SELECTED_TARGET,
                    activeField = field,
                    candidates = emptyList(),
                    probeRound = 0,
                    statusMessage = "Anchored to Selected Target mob — probe ${field.shortLabel} read-only."
                )
            )
        }
    }

    /**
     * Strictly read-only: links/assigns the currently locked Selected Target's decoded Name, HP,
     * Aggro status, and world position onto the matching entry in the Entities list (or adds it
     * to the Entities list & read-only JS tracker if it wasn't scanned yet).
     */
    fun onAssignSelectedTargetToEntityList() {
        val state = _uiState.value
        val target = state.targetScanner.liveTarget
        if (target == null || !target.hasTarget) {
            _effects.tryEmit(DiagnosticsEffect.ShowToast("Select a mob in-game with a locked Target slot first."))
            return
        }

        val coordAddr = target.effectiveAnchorAddress
        val hexStruct = formatHexAddress(target.targetStructAddress.takeIf { it > 0L } ?: coordAddr)
        val hexCoord = formatHexAddress(coordAddr)
        val targetPath = "HEAPF32[${(coordAddr / 4L).toInt()}] @ $hexCoord"
        val targetName = target.resolvedName ?: "Targeted Mob ($hexStruct)"

        var assignedSelectionKey: String? = null
        _uiState.update { current ->
            val existingIdx = current.discoveredEntities.indexOfFirst { ent ->
                (coordAddr > 0L && ent.coordByteAddress > 0L && kotlin.math.abs(ent.coordByteAddress - coordAddr) <= 64L) ||
                    (target.matchedEntityId != null && ent.id == target.matchedEntityId)
            }

            val updatedList = if (existingIdx >= 0) {
                current.discoveredEntities.mapIndexed { idx, ent ->
                    if (idx == existingIdx) {
                        val merged = ent.copy(
                            resolvedName = target.resolvedName ?: ent.resolvedName,
                            hp = target.hp ?: ent.hp,
                            maxHp = target.maxHp ?: ent.maxHp,
                            aggroRawValue = target.aggroRawValue ?: ent.aggroRawValue,
                            isAggro = target.isAggro ?: ent.isAggro,
                            worldX = target.worldX ?: ent.worldX,
                            worldY = target.worldY ?: ent.worldY,
                            worldZ = target.worldZ ?: ent.worldZ,
                            distanceToPlayer = target.distanceToPlayer ?: ent.distanceToPlayer,
                            isActiveGameTarget = true
                        )
                        assignedSelectionKey = merged.selectionKey
                        merged
                    } else {
                        ent.copy(isActiveGameTarget = false)
                    }
                }
            } else {
                val created = DiscoveredEntitySample(
                    name = targetName,
                    id = target.matchedEntityId ?: hexStruct,
                    path = targetPath,
                    worldX = target.worldX ?: 0.0,
                    worldY = target.worldY ?: 0.0,
                    worldZ = target.worldZ ?: 0.0,
                    distanceToPlayer = target.distanceToPlayer ?: 0.0,
                    screenX = 0.0,
                    screenY = 0.0,
                    inViewport = true,
                    sampleKeys = listOf("WASM_STRUCT", "struct=$hexStruct", "SELECTED_TARGET"),
                    coordByteAddress = coordAddr,
                    heapIndex = (coordAddr / 4L).toInt(),
                    resolvedName = target.resolvedName,
                    hp = target.hp,
                    maxHp = target.maxHp,
                    aggroRawValue = target.aggroRawValue,
                    isAggro = target.isAggro,
                    isActiveGameTarget = true
                )
                assignedSelectionKey = created.selectionKey
                listOf(created) + current.discoveredEntities.map { it.copy(isActiveGameTarget = false) }
            }

            current.copy(
                discoveredEntities = updatedList,
                lastEntityCount = updatedList.size,
                trackedEntityKey = assignedSelectionKey,
                activeConsoleTab = ConsoleTab.ENTITIES,
                discoveredEntityArrayPath = current.discoveredEntityArrayPath ?: "WASM Selected Target + Nearby Entities"
            )
        }

        if (coordAddr > 0L) {
            _effects.tryEmit(
                DiagnosticsEffect.EvaluateJs(
                    WasmSelectedTargetScripts.buildRegisterTargetInTrackedEntitiesScript(
                        coordByteAddress = coordAddr,
                        structByteAddress = target.targetStructAddress,
                        label = targetName
                    )
                )
            )
        }
        val msg = "Assigned $targetName to Entity List (read-only location tracker active)."
        _effects.tryEmit(DiagnosticsEffect.ShowToast(msg))
        appendLog(LogCategory.ENTITY, msg, "coord=$hexCoord struct=$hexStruct hp=${target.hp} aggro=${target.isAggro}")
    }

    // Read-Only WASM Entity Struct Field Prober (Player & Mob HP / MP / FP / Name / Aggro)
    fun onToggleStructProberExpanded() {
        _uiState.update { state ->
            state.copy(
                structProber = state.structProber.copy(
                    isExpanded = !state.structProber.isExpanded
                )
            )
        }
    }

    fun onSelectStructProbeAnchor(anchor: WasmStructProbeAnchor) {
        _uiState.update { state ->
            state.copy(
                structProber = state.structProber.copy(
                    probeAnchor = anchor,
                    candidates = emptyList(),
                    probeRound = 0,
                    statusMessage = ""
                )
            )
        }
    }

    fun onSelectStructProbeField(field: WasmStructFieldKind) {
        _uiState.update { state ->
            val switchedCategory = state.structProber.activeField.isNameField != field.isNameField ||
                state.structProber.activeField.isAggroField != field.isAggroField
            val nextAnchor = if (field.isAggroField && state.targetScanner.liveTarget?.hasTarget == true) {
                WasmStructProbeAnchor.SELECTED_TARGET
            } else {
                state.structProber.probeAnchor
            }
            state.copy(
                structProber = state.structProber.copy(
                    probeAnchor = nextAnchor,
                    activeField = field,
                    queryInput = if (switchedCategory) "" else state.structProber.queryInput,
                    candidates = if (state.structProber.activeField != field) emptyList() else state.structProber.candidates,
                    probeRound = if (state.structProber.activeField != field) 0 else state.structProber.probeRound,
                    statusMessage = ""
                )
            )
        }
    }

    fun onStructProbeInputChanged(input: String) {
        val field = _uiState.value.structProber.activeField
        val sanitized = if (field.isNameField) {
            input.take(44)
        } else {
            input.filter { it.isDigit() || it == '.' || it == '-' }.take(14)
        }
        _uiState.update { state ->
            state.copy(structProber = state.structProber.copy(queryInput = sanitized))
        }
    }

    private fun resolveActivePlayerCoordByteAddress(state: DiagnosticsUiState): Long {
        val fd = state.wasmFloatDelta
        val hudChain = fd.activeHudPointerId?.let { id ->
            fd.pointerChains.firstOrNull { it.id == id && it.resolvedTargetAddress > 0L }
        }
        if (hudChain != null) return hudChain.resolvedTargetAddress
        val movingOrLive = fd.pointerChains.firstOrNull {
            (it.status == WasmPointerStatus.MOVING || it.status == WasmPointerStatus.LIVE) &&
                it.resolvedTargetAddress > 0L
        }
        if (movingOrLive != null) return movingOrLive.resolvedTargetAddress
        return fd.lockedCoords?.xByteAddress ?: 0L
    }

    private fun resolveActiveStructProbeAnchorAddress(state: DiagnosticsUiState): Pair<Long, Boolean> {
        return if (state.structProber.probeAnchor == WasmStructProbeAnchor.SELECTED_TARGET) {
            val targetAddr = state.targetScanner.liveTarget?.takeIf { it.hasTarget }?.effectiveAnchorAddress ?: 0L
            targetAddr to true
        } else {
            resolveActivePlayerCoordByteAddress(state) to false
        }
    }

    fun runAggroStructProbe(isAggroedNow: Boolean, refineExisting: Boolean = false) {
        val state = _uiState.value
        val prober = state.structProber
        val targetAddr = state.targetScanner.liveTarget?.takeIf { it.hasTarget }?.effectiveAnchorAddress ?: 0L
        val playerAddr = resolveActivePlayerCoordByteAddress(state)
        if (targetAddr <= 0L) {
            val msg = "Lock a Selected Target mob in Target Finder first so Aggro status can be probed."
            _uiState.update { it.copy(structProber = it.structProber.copy(statusMessage = msg)) }
            _effects.tryEmit(DiagnosticsEffect.ShowToast(msg))
            return
        }

        val existingJson = if (refineExisting && prober.activeField == WasmStructFieldKind.AGGRO && prober.candidates.isNotEmpty()) {
            val arr = JSONArray()
            prober.candidates.forEach { c ->
                arr.put(
                    JSONObject()
                        .put("relOffsetBytes", c.relOffsetBytes)
                        .put("valueType", c.valueType.name)
                )
            }
            arr.toString()
        } else {
            null
        }

        val stateLabel = if (isAggroedNow) "AGGROED (attacking)" else "PASSIVE (idle)"
        _uiState.update { current ->
            current.copy(
                structProber = current.structProber.copy(
                    probeAnchor = WasmStructProbeAnchor.SELECTED_TARGET,
                    activeField = WasmStructFieldKind.AGGRO,
                    isProbing = true,
                    statusMessage = if (existingJson != null) {
                        "Refining ${prober.candidates.size} Aggro candidate(s) while mob is $stateLabel…"
                    } else {
                        "Probing Selected Target (${formatHexAddress(targetAddr)}) while mob is $stateLabel…"
                    }
                )
            )
        }

        _effects.tryEmit(
            DiagnosticsEffect.EvaluateJs(
                WasmStructProberScripts.buildProbeAggroOffsetsScript(
                    targetAnchorByteAddress = targetAddr,
                    playerByteAddress = playerAddr,
                    isAggroedNow = isAggroedNow,
                    existingCandidatesJson = existingJson
                )
            )
        )
    }

    fun runStructFieldProbe(refineExisting: Boolean = false) {
        val state = _uiState.value
        val prober = state.structProber
        val field = prober.activeField
        val (anchorAddr, isTargetAnchor) = resolveActiveStructProbeAnchorAddress(state)

        if (field.isAggroField) {
            runAggroStructProbe(isAggroedNow = true, refineExisting = refineExisting)
            return
        }

        if (field.isNameField) {
            runNameStructProbeInternal(
                anchorAddr = anchorAddr,
                anchorIsTarget = isTargetAnchor,
                query = prober.queryInput.trim()
            )
            return
        }

        if (anchorAddr <= 0L) {
            val msg = if (isTargetAnchor) {
                "Lock a Selected Target mob in Target Finder first."
            } else {
                "Lock coordinates or stream a verified live player pointer first."
            }
            _uiState.update { it.copy(structProber = it.structProber.copy(statusMessage = msg)) }
            _effects.tryEmit(DiagnosticsEffect.ShowToast(msg))
            return
        }

        val targetVal = prober.queryInput.trim().toDoubleOrNull()
        if (targetVal == null || !targetVal.isFinite()) {
            val msg = "Enter current ${field.shortLabel} value (e.g. 4338) first."
            _uiState.update { it.copy(structProber = it.structProber.copy(statusMessage = msg)) }
            _effects.tryEmit(DiagnosticsEffect.ShowToast(msg))
            return
        }

        val existingJson = if (refineExisting && prober.candidates.isNotEmpty()) {
            val arr = JSONArray()
            prober.candidates.forEach { c ->
                val obj = JSONObject()
                    .put("relOffsetBytes", c.relOffsetBytes)
                    .put("valueType", c.valueType.name)
                if (c.subOffsetBytes != null) obj.put("subOffsetBytes", c.subOffsetBytes)
                arr.put(obj)
            }
            arr.toString()
        } else {
            null
        }

        val anchorLabel = if (isTargetAnchor) "selected mob" else "player"
        _uiState.update { current ->
            current.copy(
                structProber = current.structProber.copy(
                    isProbing = true,
                    statusMessage = if (existingJson != null) {
                        "Refining ${prober.candidates.size} ${field.shortLabel} candidate(s) for $targetVal…"
                    } else {
                        "Probing $anchorLabel struct around ${formatHexAddress(anchorAddr)} for ${field.shortLabel}=$targetVal…"
                    }
                )
            )
        }

        _effects.tryEmit(
            DiagnosticsEffect.EvaluateJs(
                WasmStructProberScripts.buildProbeStatOffsetsScript(
                    fieldKind = field,
                    playerByteAddress = anchorAddr,
                    targetValue = targetVal,
                    existingCandidatesJson = existingJson,
                    anchorIsTarget = isTargetAnchor
                )
            )
        )
    }

    fun runAutoProbeEntityNames() {
        val state = _uiState.value
        val (anchorAddr, isTargetAnchor) = resolveActiveStructProbeAnchorAddress(state)
        _uiState.update { current ->
            current.copy(
                structProber = current.structProber.copy(
                    activeField = WasmStructFieldKind.NAME,
                    isProbing = true,
                    statusMessage = "Auto-probing inline UTF-8, char*, and std::string fields…"
                )
            )
        }
        runNameStructProbeInternal(anchorAddr = anchorAddr, anchorIsTarget = isTargetAnchor, query = "")
    }

    private fun runNameStructProbeInternal(
        anchorAddr: Long,
        anchorIsTarget: Boolean,
        query: String
    ) {
        if (anchorAddr <= 0L && _uiState.value.discoveredEntities.isEmpty()) {
            val msg = "Activate a live player/target pointer or scan nearby entities first."
            _uiState.update { it.copy(structProber = it.structProber.copy(isProbing = false, statusMessage = msg)) }
            _effects.tryEmit(DiagnosticsEffect.ShowToast(msg))
            return
        }
        val anchorLabel = if (anchorIsTarget) "selected mob & nearby mobs" else "player & nearby mobs"
        _uiState.update { current ->
            current.copy(
                structProber = current.structProber.copy(
                    activeField = WasmStructFieldKind.NAME,
                    isProbing = true,
                    statusMessage = if (query.isNotBlank()) {
                        "Searching $anchorLabel for \"$query\"…"
                    } else {
                        "Auto-probing inline UTF-8, char*, and std::string fields on $anchorLabel…"
                    }
                )
            )
        }
        _effects.tryEmit(
            DiagnosticsEffect.EvaluateJs(
                WasmStructProberScripts.buildProbeNameOffsetsScript(
                    playerByteAddress = anchorAddr,
                    nameQuery = query,
                    anchorIsTarget = anchorIsTarget
                )
            )
        )
    }

    fun onBindStructFieldCandidate(
        candidate: WasmStructProbeCandidate,
        includeAdjacentMax: Boolean = true
    ) {
        val state = _uiState.value
        val field = state.structProber.activeField
        val persistUrl = effectivePersistenceUrl(state)
        val persistHost = SitePointerStore.normalizeHostKey(persistUrl).orEmpty()

        var nextSchema = state.structProber.schema
            .withField(field, candidate.toFieldOffset())
            .copy(hostKey = persistHost)

        // Auto-bind adjacent Max stat when binding HP / MP / FP if none bound yet
        val autoMaxKind = when (field) {
            WasmStructFieldKind.HP -> WasmStructFieldKind.MAX_HP
            WasmStructFieldKind.MP -> WasmStructFieldKind.MAX_MP
            WasmStructFieldKind.FP -> WasmStructFieldKind.MAX_FP
            else -> null
        }
        var autoBoundMaxMsg = ""
        if (includeAdjacentMax &&
            autoMaxKind != null &&
            nextSchema.fieldFor(autoMaxKind) == null &&
            candidate.adjacentMaxOffsetBytes != null &&
            candidate.subOffsetBytes == null
        ) {
            val maxField = WasmStructFieldOffset(
                relOffsetBytes = candidate.adjacentMaxOffsetBytes,
                subOffsetBytes = null,
                valueType = candidate.valueType
            )
            nextSchema = nextSchema.withField(autoMaxKind, maxField)
            autoBoundMaxMsg = " (+ auto ${autoMaxKind.shortLabel} ${maxField.displayExpression})"
        }

        val persistedSchema: WasmEntityStructSchema = (
            if (persistHost.isNotEmpty()) {
                siteStructSchemaStore?.saveSchemaForUrl(persistUrl, nextSchema)
            } else {
                null
            }
        ) ?: nextSchema.copy(isSavedLocally = false)

        val nowTs = formatTimestamp(System.currentTimeMillis())
        val stagingPath = "WASM_STRUCT.${field.shortLabel}: ${candidate.expression}"
        val detail = "Player=${candidate.playerDisplayValue}" +
            if (candidate.mobPreviews.isNotEmpty()) " • Mobs: ${candidate.mobPreviews.joinToString(", ")}" else ""

        _uiState.update { current ->
            val mergedStaging = mergeIntoStagingBuffer(
                existing = current.stagingBuffer,
                incoming = listOf(stagingPath to detail),
                sourceStep = "Struct Schema",
                scanNumber = current.scanCounter.coerceAtLeast(1),
                timestamp = nowTs,
                forcePin = true
            )
            current.copy(
                stagingBuffer = mergedStaging,
                structProber = current.structProber.copy(
                    schema = persistedSchema,
                    statusMessage = "Bound ${field.shortLabel} -> ${candidate.expression}$autoBoundMaxMsg (saved for ${persistHost.ifBlank { "session" }})."
                )
            )
        }
        refreshSavedSiteProfiles()

        val schemaJson = SiteStructSchemaStore.serializeSchema(persistedSchema)
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(WasmStructProberScripts.buildSyncStructSchemaScript(schemaJson)))
        appendLog(
            LogCategory.WASM,
            "Bound ${field.shortLabel} offset -> ${candidate.expression}$autoBoundMaxMsg",
            "$detail | Saved locally for host=$persistHost"
        )
        _effects.tryEmit(
            DiagnosticsEffect.ShowToast("Bound ${field.shortLabel} (${candidate.expression}) — saved to disk!")
        )
    }

    fun onClearBoundStructField(field: WasmStructFieldKind) {
        val state = _uiState.value
        val persistUrl = effectivePersistenceUrl(state)
        val updated = state.structProber.schema.withField(field, null)
        val persisted: WasmEntityStructSchema = (
            if (persistUrl.isNotBlank()) {
                siteStructSchemaStore?.saveSchemaForUrl(persistUrl, updated)
            } else {
                null
            }
        ) ?: updated.copy(isSavedLocally = updated.hasAnyBound)

        _uiState.update { current ->
            val nextVitals = current.playerVitals?.let { pv ->
                val cleared = when (field) {
                    WasmStructFieldKind.HP -> pv.copy(hp = null, observedPeakHp = null)
                    WasmStructFieldKind.MAX_HP -> pv.copy(maxHp = null)
                    WasmStructFieldKind.MP -> pv.copy(mp = null, observedPeakMp = null)
                    WasmStructFieldKind.MAX_MP -> pv.copy(maxMp = null)
                    WasmStructFieldKind.FP -> pv.copy(fp = null, observedPeakFp = null)
                    WasmStructFieldKind.MAX_FP -> pv.copy(maxFp = null)
                    WasmStructFieldKind.NAME -> pv.copy(playerName = null)
                    WasmStructFieldKind.AGGRO -> pv
                }
                if (cleared.hasAnyContent) cleared else null
            }
            current.copy(
                playerVitals = nextVitals,
                structProber = current.structProber.copy(
                    schema = persisted,
                    statusMessage = "Cleared ${field.shortLabel} offset."
                )
            )
        }
        refreshSavedSiteProfiles()

        val schemaJson = SiteStructSchemaStore.serializeSchema(persisted)
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(WasmStructProberScripts.buildSyncStructSchemaScript(schemaJson)))
        appendLog(LogCategory.WASM, "Cleared bound ${field.shortLabel} struct offset", "Schema updated for $persistUrl")
    }

    fun onClearAllBoundStructFields() {
        val persistUrl = effectivePersistenceUrl(_uiState.value)
        if (persistUrl.isNotBlank()) {
            siteStructSchemaStore?.clearSchemaForUrl(persistUrl)
        }
        val hostKey = SitePointerStore.normalizeHostKey(persistUrl).orEmpty()
        _uiState.update { current ->
            current.copy(
                playerVitals = null,
                structProber = current.structProber.copy(
                    schema = WasmEntityStructSchema(hostKey = hostKey, isSavedLocally = false),
                    candidates = emptyList(),
                    probeRound = 0,
                    statusMessage = "Cleared all saved struct offsets for ${hostKey.ifBlank { "site" }}."
                )
            )
        }
        refreshSavedSiteProfiles()
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(WasmStructProberScripts.buildSyncStructSchemaScript("{}")))
    }

    // Targeted WebAssembly Heap Value-Tracker (Read-Only HP / Stat Scanner & ±16B Neighbor Inspector)
    fun onWasmTargetValueChanged(input: String) {
        val sanitized = input.filter { it.isDigit() || it == '.' || it == '-' }.take(18)
        _uiState.update { state ->
            state.copy(wasmHeap = state.wasmHeap.copy(targetValueInput = sanitized))
        }
    }

    fun runWasmHeapInitialScan() {
        val raw = _uiState.value.wasmHeap.targetValueInput.trim()
        val parsed = raw.toDoubleOrNull()
        if (parsed == null || !parsed.isFinite()) {
            val msg = "Enter a valid current HP / stat number (e.g. 4338) first."
            appendLog(LogCategory.WASM, msg, raw)
            _effects.tryEmit(DiagnosticsEffect.ShowToast(msg))
            return
        }
        _uiState.update { state ->
            state.copy(
                scanStatus = AsyncScanStatus.RUNNING,
                scanStatusDetail = "Scanning HEAP32 / HEAPF32 for $raw…",
                wasmHeap = state.wasmHeap.copy(
                    isScanning = true,
                    initialSearchValue = parsed,
                    lastFilterValue = null,
                    filterRound = 0,
                    isolatedHeapType = null,
                    isolatedIndex = null,
                    isolatedByteAddress = null,
                    isolatedCurrentValue = "",
                    isNeighborPolling = false,
                    neighborPollTickCount = 0,
                    neighborFloats = emptyList(),
                    statusMessage = "Scanning HEAP32 / HEAPF32 in non-blocking 1 MB chunks for $raw…"
                )
            )
        }
        appendLog(
            LogCategory.WASM,
            "WASM Heap Scan: Searching HEAP32 & HEAPF32 for $raw…",
            "Target value: $parsed"
        )
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(WasmHeapScripts.buildInitialScanScript(parsed)))
    }

    fun runWasmHeapFilterDelta() {
        val raw = _uiState.value.wasmHeap.targetValueInput.trim()
        val parsed = raw.toDoubleOrNull()
        if (parsed == null || !parsed.isFinite()) {
            val msg = "Enter your updated HP value after taking damage or healing, then tap Filter."
            appendLog(LogCategory.WASM, msg, raw)
            _effects.tryEmit(DiagnosticsEffect.ShowToast(msg))
            return
        }
        if (_uiState.value.wasmHeap.totalMatches <= 0) {
            val msg = "Run '1. Scan HEAP32 / F32' with your initial HP value first."
            appendLog(LogCategory.WASM, msg, msg)
            _effects.tryEmit(DiagnosticsEffect.ShowToast(msg))
            return
        }
        _uiState.update { state ->
            state.copy(
                scanStatus = AsyncScanStatus.RUNNING,
                scanStatusDetail = "Filtering WASM matches for new value $raw…",
                wasmHeap = state.wasmHeap.copy(
                    isScanning = true,
                    lastFilterValue = parsed,
                    statusMessage = "Filtering ${_uiState.value.wasmHeap.totalMatches} stored addresses for new value $raw…"
                )
            )
        }
        appendLog(
            LogCategory.WASM,
            "WASM Value-Delta Filter: Narrowing matches to $raw…",
            "Updated target value: $parsed"
        )
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(WasmHeapScripts.buildFilterDeltaScript(parsed)))
    }

    fun onInspectWasmCandidate(heapType: String, index: Int) {
        val hexAddr = formatHexAddress(index.toLong() * 4L)
        _uiState.update { state ->
            state.copy(
                wasmHeap = state.wasmHeap.copy(
                    isolatedHeapType = heapType,
                    isolatedIndex = index,
                    isolatedByteAddress = index.toLong() * 4L,
                    isNeighborPolling = true,
                    neighborPollTickCount = 0,
                    neighborFloats = emptyList(),
                    statusMessage = "Polling ±16B around $heapType[$index] ($hexAddr) every 200ms…"
                )
            )
        }
        appendLog(
            LogCategory.WASM,
            "Locked WASM Neighbor Inspector on $heapType[$index] ($hexAddr)",
            "Reading ±4 to ±16 byte floats every 200ms"
        )
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(WasmHeapScripts.buildInspectNeighborsScript(heapType, index)))
    }

    fun onStopWasmNeighborPolling() {
        _uiState.update { state ->
            state.copy(
                wasmHeap = state.wasmHeap.copy(
                    isNeighborPolling = false,
                    statusMessage = "Paused neighbor polling (memory map still ready to export)."
                )
            )
        }
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(WasmHeapScripts.buildStopNeighborPollScript()))
    }

    fun onResetWasmHeapTracker() {
        _uiState.update { state ->
            state.copy(wasmHeap = WasmHeapTrackerState())
        }
        appendLog(LogCategory.WASM, "Reset WASM Heap Value-Tracker", "Cleared stored candidate indices and stopped neighbor polling")
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(WasmHeapScripts.buildResetTrackerScript()))
    }

    fun onWasmNeighborsUpdatedFromBridge(jsonPayload: String) {
        val json = try {
            JSONObject(jsonPayload)
        } catch (_: Exception) {
            return
        }
        if (json.optString("stage", "") != "WASM_NEIGHBORS_TICK") return

        val heapType = json.optString("heapType", "HEAP32")
        val isolatedIndex = json.optInt("isolatedIndex", -1)
        if (isolatedIndex < 0) return
        val isolatedByteAddress = json.optLong("isolatedByteAddress", isolatedIndex.toLong() * 4L)
        val currentVal = json.optString("currentValue", "")
        val tick = json.optInt("tick", 1)
        val sourceLabel = json.optString("heapSourceLabel", "")
        val byteLen = json.optLong("heapByteLength", 0L)
        val neighborsArr = json.optJSONArray("neighbors") ?: JSONArray()

        _uiState.update { state ->
            val prevWasm = state.wasmHeap
            val isSameAnchor = prevWasm.isolatedIndex == isolatedIndex && prevWasm.isolatedHeapType == heapType
            val prevByOffset = if (isSameAnchor) {
                prevWasm.neighborFloats.associateBy { it.byteOffset }
            } else {
                emptyMap()
            }

            val updatedNeighbors = buildList {
                for (i in 0 until neighborsArr.length()) {
                    val obj = neighborsArr.optJSONObject(i) ?: continue
                    val offset = obj.optInt("byteOffset", 0)
                    val idx = obj.optInt("heapIndex", isolatedIndex + (offset / 4))
                    val addr = obj.optLong("byteAddress", idx.toLong() * 4L)
                    val fv = obj.optDouble("floatValue", 0.0)
                    val iv = obj.optInt("intValue", 0)
                    val prev = prevByOffset[offset]
                    val changed = prev != null && (
                        kotlin.math.abs(fv - prev.floatValue) > 0.0001 || iv != prev.intValue
                    )
                    val nextCount = when {
                        prev == null -> 0
                        changed -> prev.changeCount + 1
                        else -> prev.changeCount
                    }
                    add(
                        WasmNeighborFloat(
                            byteOffset = offset,
                            heapIndex = idx,
                            byteAddress = addr,
                            floatValue = fv,
                            intValue = iv,
                            changedFromPrev = changed,
                            changeCount = nextCount
                        )
                    )
                }
            }

            state.copy(
                wasmHeap = prevWasm.copy(
                    heapSourceLabel = sourceLabel.ifBlank { prevWasm.heapSourceLabel },
                    heapByteLength = if (byteLen > 0L) byteLen else prevWasm.heapByteLength,
                    isolatedHeapType = heapType,
                    isolatedIndex = isolatedIndex,
                    isolatedByteAddress = isolatedByteAddress,
                    isolatedCurrentValue = currentVal,
                    isNeighborPolling = true,
                    neighborPollTickCount = tick,
                    neighborFloats = updatedNeighbors
                )
            )
        }
    }

    fun exportWasmMemoryMapJson(context: Context) {
        val snapshot = _uiState.value
        if (snapshot.wasmHeap.isExportingMemoryMap) return
        val hasWasmData = snapshot.wasmHeap.isolatedIndex != null ||
            snapshot.wasmHeap.totalMatches > 0 ||
            snapshot.wasmFloatDelta.lockedCoords != null ||
            snapshot.wasmFloatDelta.pointerChains.isNotEmpty() ||
            snapshot.wasmFloatDelta.candidates.isNotEmpty()
        if (!hasWasmData) {
            _effects.tryEmit(DiagnosticsEffect.ShowToast("Scan and isolate a WASM address or lock coordinates first."))
            return
        }
        val appContext = context.applicationContext
        _uiState.update { state ->
            state.copy(wasmHeap = state.wasmHeap.copy(isExportingMemoryMap = true))
        }

        viewModelScope.launch {
            val mapJson = withContext(Dispatchers.Default) {
                buildWasmMemoryMapJson(snapshot)
            }
            val outcome = withContext(Dispatchers.IO) {
                ReportExporter.exportJsonToDownloads(
                    context = appContext,
                    jsonContent = mapJson,
                    filePrefix = "wasm_memory_map"
                )
            }
            _uiState.update { state ->
                state.copy(
                    wasmHeap = state.wasmHeap.copy(
                        isExportingMemoryMap = false,
                        lastMemoryMapExport = outcome
                    )
                )
            }
            if (outcome.success) {
                appendLog(
                    LogCategory.WASM,
                    "Exported WASM Memory Map -> ${outcome.displayPath}",
                    "Saved ${mapJson.length} bytes (${snapshot.wasmHeap.isolatedHeapType ?: "HEAP"}[${snapshot.wasmHeap.isolatedIndex ?: -1}]) to ${outcome.displayPath}"
                )
                _effects.tryEmit(
                    DiagnosticsEffect.ShowToast(
                        appContext.getString(R.string.toast_wasm_map_saved, outcome.fileName)
                    )
                )
            } else {
                val err = outcome.errorMessage ?: "Unknown error"
                appendLog(LogCategory.ERROR, "WASM Memory Map export failed", err)
                _effects.tryEmit(
                    DiagnosticsEffect.ShowToast(
                        appContext.getString(R.string.toast_report_failed, err)
                    )
                )
            }
        }
    }

    fun onShareLastWasmMemoryMap() {
        val export = _uiState.value.wasmHeap.lastMemoryMapExport
        if (export != null && export.success && export.contentUri != null) {
            _effects.tryEmit(DiagnosticsEffect.ShareReport(export.contentUri, export.fileName, export.mimeType))
        }
    }

    // Step 2A / 2B (Float Mode) — WebAssembly Float Heap Delta Scanner targeting window.HEAPF32
    fun onSelectStep2ScanMode(mode: Step2ScanMode) {
        _uiState.update { it.copy(step2ScanMode = mode) }
    }

    fun runWasmFloatBaselineSnapshot() {
        if (_uiState.value.wasmFloatDelta.isSamplingBaseline) return
        _uiState.update { state ->
            state.copy(
                scanStatus = AsyncScanStatus.RUNNING,
                scanStatusDetail = "Step 2A (Float): Sampling window.HEAPF32 baseline…",
                wasmFloatDelta = state.wasmFloatDelta.copy(
                    isSamplingBaseline = true,
                    isScanningDeltas = false,
                    baselineCaptured = false,
                    totalDeltaMatches = 0,
                    scanRound = 0,
                    isLiveWatching = false,
                    liveWatchTickCount = 0,
                    statusMessage = "Sampling window.HEAPF32 in non-blocking 1 MB slices & pruning standing jitter…"
                )
            )
        }
        appendLog(
            LogCategory.WASM,
            "Step 2A (Float Mode): Capturing window.HEAPF32 standing baseline…",
            "Skipping low guard/out-of-bounds slots and pruning ticking timers while standing still"
        )
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(WasmHeapScripts.buildFloatBaselineSnapshotScript()))
    }

    fun runWasmFloatDeltaScan() {
        val fd = _uiState.value.wasmFloatDelta
        if (fd.isSamplingBaseline || fd.isScanningDeltas) return
        if (!fd.baselineCaptured) {
            val msg = "Stand still and tap 2A. Capture Float Baseline first."
            appendLog(LogCategory.WASM, msg, msg)
            _effects.tryEmit(DiagnosticsEffect.ShowToast(msg))
            return
        }
        _uiState.update { state ->
            state.copy(
                scanStatus = AsyncScanStatus.RUNNING,
                scanStatusDetail = "Step 2B (Float): Scanning HEAPF32 movement deltas…",
                wasmFloatDelta = state.wasmFloatDelta.copy(
                    isScanningDeltas = true,
                    statusMessage = "Comparing live window.HEAPF32 against baseline for positional deltas…"
                )
            )
        }
        appendLog(
            LogCategory.WASM,
            "Step 2B (Float Mode): Scanning HEAPF32 movement deltas (round #${fd.scanRound + 1})…",
            "Isolating floats that changed by a positional delta range (0.05..250.0) and clustering X/Y/Z neighbors"
        )
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(WasmHeapScripts.buildFloatDeltaScanScript()))
    }

    fun onTogglePinWasmFloatCandidate(heapIndex: Int) {
        val nowTs = formatTimestamp(System.currentTimeMillis())
        var nowPinned = false
        var updatedPinnedSet: Set<Int> = emptySet()

        _uiState.update { state ->
            val currentPinned = state.wasmFloatDelta.pinnedIndices
            val nextPinned = if (currentPinned.contains(heapIndex)) {
                nowPinned = false
                currentPinned - heapIndex
            } else {
                nowPinned = true
                currentPinned + heapIndex
            }
            updatedPinnedSet = nextPinned

            val candidate = state.wasmFloatDelta.candidates.firstOrNull { it.heapIndex == heapIndex }
            val updatedCandidates = state.wasmFloatDelta.candidates.map { item ->
                if (item.heapIndex == heapIndex) item.copy(isPinned = nowPinned) else item
            }

            val path = "window.HEAPF32[$heapIndex]"
            val hex = formatHexAddress(heapIndex.toLong() * 4L)
            val detail = if (candidate != null) {
                val hint = if (candidate.axisHint.isNotBlank()) "${candidate.axisHint} • " else ""
                "${hint}$hex • val=${String.format(Locale.US, "%.4f", candidate.currentValue)} (Δ ${String.format(Locale.US, "%+.3f", candidate.delta)})"
            } else {
                "$hex • Pinned WASM float"
            }

            val mergedStaging = if (nowPinned) {
                mergeIntoStagingBuffer(
                    existing = state.stagingBuffer,
                    incoming = listOf(path to detail),
                    sourceStep = "Float Delta",
                    scanNumber = state.scanCounter.coerceAtLeast(1),
                    timestamp = nowTs,
                    forcePin = true
                )
            } else {
                state.stagingBuffer.map { item ->
                    if (item.path == path) item.copy(isPinned = false) else item
                }
            }

            state.copy(
                stagingBuffer = sortStagingBuffer(mergedStaging),
                wasmFloatDelta = state.wasmFloatDelta.copy(
                    pinnedIndices = nextPinned,
                    candidates = updatedCandidates
                )
            )
        }

        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(WasmHeapScripts.buildSyncPinnedFloatsScript(updatedPinnedSet)))
        val toastMsg = if (nowPinned) "Pinned HEAPF32[$heapIndex] to Live Watcher & Staging" else "Unpinned HEAPF32[$heapIndex]"
        _effects.tryEmit(DiagnosticsEffect.ShowToast(toastMsg))
    }

    fun onPinTopWasmFloatTriplet() {
        val topThree = _uiState.value.wasmFloatDelta.candidates.take(3)
        if (topThree.isEmpty()) return
        val nowTs = formatTimestamp(System.currentTimeMillis())
        var updatedPinnedSet: Set<Int> = emptySet()

        _uiState.update { state ->
            val topIndices = topThree.map { it.heapIndex }.toSet()
            val nextPinned = state.wasmFloatDelta.pinnedIndices + topIndices
            updatedPinnedSet = nextPinned

            val incoming = topThree.mapIndexed { idx, c ->
                val axis = c.axisHint.ifBlank { listOf("X", "Y", "Z").getOrElse(idx) { "F" } }
                val hex = formatHexAddress(c.byteAddress)
                "window.HEAPF32[${c.heapIndex}]" to "[$axis] $hex • val=${String.format(Locale.US, "%.4f", c.currentValue)}"
            }
            val mergedStaging = mergeIntoStagingBuffer(
                existing = state.stagingBuffer,
                incoming = incoming,
                sourceStep = "Float Delta",
                scanNumber = state.scanCounter.coerceAtLeast(1),
                timestamp = nowTs,
                forcePin = true
            )
            val updatedCandidates = state.wasmFloatDelta.candidates.map { item ->
                if (nextPinned.contains(item.heapIndex)) item.copy(isPinned = true) else item
            }
            state.copy(
                stagingBuffer = mergedStaging,
                wasmFloatDelta = state.wasmFloatDelta.copy(
                    pinnedIndices = nextPinned,
                    candidates = updatedCandidates
                )
            )
        }
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(WasmHeapScripts.buildSyncPinnedFloatsScript(updatedPinnedSet)))
        _effects.tryEmit(DiagnosticsEffect.ShowToast("Pinned top ${topThree.size} X/Y/Z float offsets to Staging"))
    }

    fun onToggleFloatWatcherExpanded() {
        _uiState.update { state ->
            state.copy(
                wasmFloatDelta = state.wasmFloatDelta.copy(
                    isWatcherExpanded = !state.wasmFloatDelta.isWatcherExpanded
                )
            )
        }
    }

    fun onStopWasmFloatWatch() {
        _uiState.update { state ->
            state.copy(
                wasmFloatDelta = state.wasmFloatDelta.copy(
                    isLiveWatching = false,
                    statusMessage = "Paused Live Float Watcher (pinned offsets preserved)."
                )
            )
        }
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(WasmHeapScripts.buildStopFloatWatchScript()))
    }

    fun onResetWasmFloatDelta() {
        val current = _uiState.value
        val preservedSaved = current.wasmFloatDelta.pointerChains.filter { it.isPinned || it.isSavedLocally }
        val activeHudPreserved = current.wasmFloatDelta.activeHudPointerId
            ?.takeIf { id -> preservedSaved.any { it.id == id } }
        val savedCount = sitePointerStore?.countSavedForUrl(current.activeUrl) ?: preservedSaved.count { it.isSavedLocally }

        _uiState.update { state ->
            state.copy(
                wasmFloatDelta = WasmFloatDeltaState(
                    pointerChains = preservedSaved,
                    isWatchingPointers = current.wasmFloatDelta.isWatchingPointers && preservedSaved.isNotEmpty(),
                    pointerWatchTickCount = if (preservedSaved.isNotEmpty()) current.wasmFloatDelta.pointerWatchTickCount else 0,
                    activeHudPointerId = activeHudPreserved,
                    savedPointerCountForHost = savedCount,
                    pointerStatusMessage = if (preservedSaved.isNotEmpty()) {
                        "Preserved ${preservedSaved.size} bookmarked pointer chain(s) across Reset."
                    } else ""
                )
            )
        }
        appendLog(
            LogCategory.WASM,
            "Reset WASM Float Heap Delta Scanner",
            if (preservedSaved.isNotEmpty()) {
                "Released HEAPF32 baseline snapshot while preserving ${preservedSaved.size} bookmarked pointer chain(s)"
            } else {
                "Released HEAPF32 baseline snapshot and stopped Live Float Watcher"
            }
        )
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(WasmHeapScripts.buildResetFloatDeltaScript()))
    }

    fun onInspectWasmFloatNeighbors(heapIndex: Int) {
        _uiState.update {
            it.copy(
                activeConsoleTab = ConsoleTab.PROBER,
                activeProberSubTab = ProberSubTab.FULL_HEAP
            )
        }
        onInspectWasmCandidate("HEAPF32", heapIndex)
    }

    fun onLockWasmFloatCoords(anchorIndex: Int? = null) {
        val state = _uiState.value
        val fd = state.wasmFloatDelta
        val byIndex = fd.candidates.associateBy { it.heapIndex }

        val resolvedTriple: Triple<Int, Int?, Int?>? = when {
            anchorIndex != null && anchorIndex >= 0 -> {
                Triple(anchorIndex, anchorIndex + 1, anchorIndex + 2)
            }
            fd.pinnedIndices.isNotEmpty() -> {
                val orderedPins = fd.pinnedIndices.sorted()
                val x = orderedPins[0]
                val y = orderedPins.getOrNull(1) ?: (x + 1)
                val z = orderedPins.getOrNull(2) ?: (y + 1)
                Triple(x, y, z)
            }
            fd.candidates.isNotEmpty() -> {
                val top = fd.candidates.take(3)
                val x = top[0].heapIndex
                val y = top.getOrNull(1)?.heapIndex ?: (x + 1)
                val z = top.getOrNull(2)?.heapIndex ?: (y + 1)
                Triple(x, y, z)
            }
            else -> null
        }

        if (resolvedTriple == null) {
            val msg = "Run 'Scan Float Heap Deltas' first to find movement coordinates to lock."
            appendLog(LogCategory.WASM, msg, msg)
            _effects.tryEmit(DiagnosticsEffect.ShowToast(msg))
            return
        }

        val (xIdx, yIdx, zIdx) = resolvedTriple
        val xVal = byIndex[xIdx]?.currentValue ?: 0.0
        val yVal = yIdx?.let { byIndex[it]?.currentValue } ?: 0.0
        val zVal = zIdx?.let { byIndex[it]?.currentValue } ?: 0.0
        val nowTs = formatTimestamp(System.currentTimeMillis())

        val locked = LockedWasmCoords(
            xIndex = xIdx,
            yIndex = yIdx,
            zIndex = zIdx,
            xByteAddress = xIdx.toLong() * 4L,
            yByteAddress = yIdx?.let { it.toLong() * 4L },
            zByteAddress = zIdx?.let { it.toLong() * 4L },
            xValue = xVal,
            yValue = yVal,
            zValue = zVal,
            lockedAtTimestamp = nowTs,
            liveTickCount = 0,
            isLivePolling = true
        )

        val indicesText = listOfNotNull(xIdx, yIdx, zIdx).joinToString(",")
        val lockedPath = "window.HEAPF32[$indicesText]"
        val hexAddr = formatHexAddress(locked.xByteAddress)
        val detail = "Locked X/Y/Z @ $hexAddr • (${String.format(Locale.US, "%.3f", xVal)}, ${String.format(Locale.US, "%.3f", yVal)}, ${String.format(Locale.US, "%.3f", zVal)})"

        _uiState.update { current ->
            val newPins = current.wasmFloatDelta.pinnedIndices + listOfNotNull(xIdx, yIdx, zIdx)
            val mergedStaging = mergeIntoStagingBuffer(
                existing = current.stagingBuffer,
                incoming = listOf(lockedPath to detail),
                sourceStep = "Locked WASM Coords",
                scanNumber = current.scanCounter.coerceAtLeast(1),
                timestamp = nowTs,
                forcePin = true
            )
            val updatedCandidates = current.wasmFloatDelta.candidates.map { c ->
                if (newPins.contains(c.heapIndex)) c.copy(isPinned = true) else c
            }
            current.copy(
                discoveredPlayerPath = lockedPath,
                playerPropertyKeys = "HEAPF32[$indicesText]",
                livePlayerCoords = PlayerCoordinates(
                    path = lockedPath,
                    x = xVal,
                    y = yVal,
                    z = zVal
                ),
                livePollActive = true,
                stagingBuffer = mergedStaging,
                scanStatus = AsyncScanStatus.COMPLETED,
                scanStatusDetail = "Locked $lockedPath ($hexAddr)",
                wasmFloatDelta = current.wasmFloatDelta.copy(
                    lockedCoords = locked,
                    pinnedIndices = newPins,
                    candidates = updatedCandidates,
                    isLiveWatching = true,
                    statusMessage = "Locked $lockedPath ($hexAddr). Tap 'Find Pointers' to locate persistent pointer chains."
                )
            )
        }

        appendLog(
            LogCategory.WASM,
            "Locked WASM Coords -> $lockedPath ($hexAddr)",
            detail
        )
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(WasmHeapScripts.buildLockFloatCoordsScript(xIdx, yIdx, zIdx)))
        _effects.tryEmit(DiagnosticsEffect.ShowToast("Locked $lockedPath — ready to Find Pointers"))
    }

    fun onUnlockWasmFloatCoords() {
        _uiState.update { current ->
            current.copy(
                wasmFloatDelta = current.wasmFloatDelta.copy(
                    lockedCoords = null,
                    statusMessage = "Unlocked WASM coordinates (Live Float Watcher preserved)."
                )
            )
        }
        appendLog(LogCategory.WASM, "Unlocked WASM Float Coordinates", "Cleared active coordinate lock")
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(WasmHeapScripts.buildUnlockFloatCoordsScript()))
    }

    fun runWasmPointerScan(overrideByteAddress: Long? = null) {
        val state = _uiState.value
        if (state.wasmFloatDelta.isScanningPointers) return
        val targetAddr = overrideByteAddress
            ?: state.wasmFloatDelta.lockedCoords?.xByteAddress
            ?: state.wasmHeap.isolatedByteAddress

        if (targetAddr == null || targetAddr <= 0L) {
            val msg = "Lock coordinates from the movement scan first before scanning for pointers."
            appendLog(LogCategory.WASM, msg, msg)
            _effects.tryEmit(DiagnosticsEffect.ShowToast(msg))
            return
        }

        val hexTarget = formatHexAddress(targetAddr)
        _uiState.update { current ->
            current.copy(
                scanStatus = AsyncScanStatus.RUNNING,
                scanStatusDetail = "Scanning HEAPU32 pointers to $hexTarget…",
                wasmFloatDelta = current.wasmFloatDelta.copy(
                    isScanningPointers = true,
                    pointerStatusMessage = "Scanning HEAPU32 in 1 MB async slices for Level-1 & Level-2 pointers to $hexTarget…"
                )
            )
        }
        appendLog(
            LogCategory.WASM,
            "Pointer Scan: Searching HEAPU32 for Level-1 & Level-2 chains -> $hexTarget",
            "Max struct offset: 256B (read-only Uint32Array scan)"
        )
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(WasmHeapScripts.buildPointerScanScript(targetAddr)))
    }

    fun runVerifyWasmPointerChains() {
        val chains = _uiState.value.wasmFloatDelta.pointerChains
        if (chains.isEmpty()) {
            _effects.tryEmit(DiagnosticsEffect.ShowToast("Run 'Find Pointers' first to discover pointer chains to verify."))
            return
        }
        val payloadJson = buildPointerChainsPayloadJson(chains)
        _uiState.update { current ->
            current.copy(
                scanStatus = AsyncScanStatus.RUNNING,
                scanStatusDetail = "Verifying ${chains.size} pointer chains live…",
                wasmFloatDelta = current.wasmFloatDelta.copy(
                    isAutoVerifyingOnReload = false,
                    pointerStatusMessage = "Re-dereferencing ${chains.size} pointer chains against live HEAPU32 / HEAPF32…"
                )
            )
        }
        appendLog(
            LogCategory.WASM,
            "Verifying ${chains.size} persistent pointer chains live…",
            "Dereferencing base pointer + offsets against current WASM linear memory (filtering out 0,0,0 false positives)"
        )
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(WasmHeapScripts.buildVerifyPointerChainsScript(payloadJson)))
    }

    fun onToggleWatchWasmPointers() {
        val fd = _uiState.value.wasmFloatDelta
        if (fd.isWatchingPointers) {
            _uiState.update { state ->
                state.copy(
                    wasmFloatDelta = state.wasmFloatDelta.copy(
                        isWatchingPointers = false,
                        pointerStatusMessage = "Paused 200ms Live Pointer Watch. Tap 'Watch Live' to resume."
                    )
                )
            }
            _effects.tryEmit(DiagnosticsEffect.EvaluateJs(WasmPointerAndEntityScripts.buildStopPointerWatchScript()))
            appendLog(LogCategory.WASM, "Paused 200ms Live Pointer Watch", "Stopped continuous pointer dereferencing")
        } else {
            startLivePointerWatchInternal(fd.pointerChains, fd.activeHudPointerId)
        }
    }

    private fun startLivePointerWatchInternal(
        chains: List<WasmPointerChain>,
        hudPointerId: String?
    ) {
        if (chains.isEmpty()) {
            _effects.tryEmit(DiagnosticsEffect.ShowToast("No pointer chains available to watch."))
            return
        }
        val payloadJson = buildPointerChainsPayloadJson(chains)
        _uiState.update { state ->
            state.copy(
                wasmFloatDelta = state.wasmFloatDelta.copy(
                    isWatchingPointers = true,
                    activeHudPointerId = hudPointerId,
                    pointerStatusMessage = "Live 200ms Watch active on ${chains.size} pointer(s) — walk in-game to confirm MOVING ✓."
                )
            )
        }
        _effects.tryEmit(
            DiagnosticsEffect.EvaluateJs(
                WasmPointerAndEntityScripts.buildStartPointerWatchScript(payloadJson, hudPointerId)
            )
        )
    }

    fun onStreamPointerChainToHud(chainId: String) {
        val state = _uiState.value
        val fd = state.wasmFloatDelta
        val target = fd.pointerChains.firstOrNull { it.id == chainId } ?: return
        val turningOff = fd.activeHudPointerId == chainId
        val nextHudId = if (turningOff) null else chainId

        if (!turningOff && (target.status == WasmPointerStatus.STALE || target.status == WasmPointerStatus.ZERO)) {
            _effects.tryEmit(DiagnosticsEffect.ShowToast("Cannot stream ${target.status.label} pointer to HUD — pick a LIVE or MOVING ✓ chain."))
            return
        }

        val nowTs = formatTimestamp(System.currentTimeMillis())
        _uiState.update { current ->
            val updatedChains = current.wasmFloatDelta.pointerChains.map { c ->
                c.copy(isStreamingToHud = (c.id == nextHudId))
            }
            val nextLocked = if (!turningOff && target.resolvedTargetAddress > 0L) {
                val xIdx = (target.resolvedTargetAddress / 4L).toInt()
                LockedWasmCoords(
                    xIndex = xIdx,
                    yIndex = xIdx + 1,
                    zIndex = xIdx + 2,
                    xByteAddress = target.resolvedTargetAddress,
                    yByteAddress = target.resolvedTargetAddress + 4L,
                    zByteAddress = target.resolvedTargetAddress + 8L,
                    xValue = target.resolvedX,
                    yValue = target.resolvedY,
                    zValue = target.resolvedZ,
                    lockedAtTimestamp = nowTs,
                    liveTickCount = current.wasmFloatDelta.pointerWatchTickCount,
                    isLivePolling = true
                )
            } else if (turningOff) {
                current.wasmFloatDelta.lockedCoords
            } else {
                current.wasmFloatDelta.lockedCoords
            }

            current.copy(
                discoveredPlayerPath = if (!turningOff) "WASM_PTR: ${target.expression}" else current.discoveredPlayerPath,
                playerPropertyKeys = if (!turningOff) target.expression else current.playerPropertyKeys,
                livePlayerCoords = if (!turningOff) {
                    PlayerCoordinates(
                        path = "WASM_PTR: ${target.expression}",
                        x = target.resolvedX,
                        y = target.resolvedY,
                        z = target.resolvedZ
                    )
                } else current.livePlayerCoords,
                livePollActive = if (!turningOff) true else current.livePollActive,
                wasmFloatDelta = current.wasmFloatDelta.copy(
                    activeHudPointerId = nextHudId,
                    pointerChains = updatedChains,
                    lockedCoords = nextLocked,
                    isWatchingPointers = if (!turningOff) true else current.wasmFloatDelta.isWatchingPointers,
                    pointerStatusMessage = if (!turningOff) {
                        "Streaming ${target.expression} -> ${formatHexAddress(target.resolvedTargetAddress)} live to HUD (200ms). Step 3 WASM Entity Scan ready!"
                    } else {
                        "Unbound pointer HUD stream."
                    }
                )
            )
        }

        if (!turningOff) {
            if (fd.isWatchingPointers) {
                _effects.tryEmit(DiagnosticsEffect.EvaluateJs(WasmPointerAndEntityScripts.buildSetHudPointerScript(nextHudId)))
            } else {
                startLivePointerWatchInternal(_uiState.value.wasmFloatDelta.pointerChains, nextHudId)
            }
            appendLog(
                LogCategory.WASM,
                "Streaming pointer ${target.expression} live to HUD",
                "Resolved -> ${formatHexAddress(target.resolvedTargetAddress)} (${String.format(Locale.US, "%.2f", target.resolvedX)}, ${String.format(Locale.US, "%.2f", target.resolvedY)}, ${String.format(Locale.US, "%.2f", target.resolvedZ)})"
            )
            _effects.tryEmit(DiagnosticsEffect.ShowToast("Streaming ${target.expression} live to HUD"))
        } else {
            _effects.tryEmit(DiagnosticsEffect.EvaluateJs(WasmPointerAndEntityScripts.buildSetHudPointerScript(null)))
            _effects.tryEmit(DiagnosticsEffect.ShowToast("Stopped HUD pointer stream"))
        }
    }

    fun onPruneZeroAndStalePointers() {
        val current = _uiState.value
        val before = current.wasmFloatDelta.pointerChains
        val kept = before.filter { chain ->
            chain.isPinned || chain.isSavedLocally ||
                (chain.status != WasmPointerStatus.ZERO && chain.status != WasmPointerStatus.STALE)
        }
        val removed = before.size - kept.size
        if (removed <= 0) {
            _effects.tryEmit(DiagnosticsEffect.ShowToast("No unbookmarked ZERO or STALE pointers to prune."))
            return
        }
        val nextHud = current.wasmFloatDelta.activeHudPointerId?.takeIf { id -> kept.any { it.id == id } }
        _uiState.update { state ->
            state.copy(
                wasmFloatDelta = state.wasmFloatDelta.copy(
                    pointerChains = kept,
                    activeHudPointerId = nextHud,
                    pointerStatusMessage = "Pruned $removed ZERO/STALE pointer(s). ${kept.size} remaining."
                )
            )
        }
        if (current.wasmFloatDelta.isWatchingPointers) {
            if (kept.isNotEmpty()) {
                startLivePointerWatchInternal(kept, nextHud)
            } else {
                _effects.tryEmit(DiagnosticsEffect.EvaluateJs(WasmPointerAndEntityScripts.buildStopPointerWatchScript()))
            }
        }
        appendLog(LogCategory.WASM, "Pruned $removed ZERO/STALE pointer chains", "${kept.size} chains remaining")
        _effects.tryEmit(DiagnosticsEffect.ShowToast("Pruned $removed ZERO/STALE pointer(s)"))
    }

    fun onWasmPointersUpdatedFromBridge(jsonPayload: String) {
        val json = try {
            JSONObject(jsonPayload)
        } catch (_: Exception) {
            return
        }
        if (json.optString("stage", "") != "WASM_POINTER_WATCH_TICK") return

        val tick = json.optInt("tick", 1)
        val sourceLabel = json.optString("heapSourceLabel", "")
        val byteLen = json.optLong("heapByteLength", 0L)
        val movingCount = json.optInt("movingCount", 0)
        val liveCount = json.optInt("liveCount", 0)
        val zeroCount = json.optInt("zeroCount", 0)
        val staleCount = json.optInt("staleCount", 0)
        val resultsArr = json.optJSONArray("results") ?: JSONArray()

        val byId = HashMap<String, JSONObject>(resultsArr.length())
        for (i in 0 until resultsArr.length()) {
            val obj = resultsArr.optJSONObject(i) ?: continue
            val id = obj.optString("id", "")
            if (id.isNotBlank()) byId[id] = obj
        }

        _uiState.update { state ->
            val prevFd = state.wasmFloatDelta
            val hudId = prevFd.activeHudPointerId
            var hudChainUpdated: WasmPointerChain? = null

            val updatedChains = prevFd.pointerChains.map { chain ->
                val res = byId[chain.id]
                if (res != null) {
                    val ok = res.optBoolean("isVerifiedLive", false)
                    val parsedStatus = WasmPointerStatus.fromRaw(res.optString("status", ""), ok)
                    val targetAddr = res.optLong("resolvedTargetAddress", if (ok) chain.resolvedTargetAddress else 0L)
                    val rx = res.optDouble("resolvedX", chain.resolvedX)
                    val ry = res.optDouble("resolvedY", chain.resolvedY)
                    val rz = res.optDouble("resolvedZ", chain.resolvedZ)
                    val changed = res.optBoolean("changedFromPrev", false)
                    val changeCount = res.optInt("changeCount", chain.changeCount)
                    val deltaMag = res.optDouble("lastDeltaMagnitude", 0.0)
                    val next = chain.copy(
                        resolvedTargetAddress = targetAddr,
                        resolvedX = rx,
                        resolvedY = ry,
                        resolvedZ = rz,
                        isVerifiedLive = ok,
                        status = parsedStatus,
                        watchBaselineX = chain.watchBaselineX ?: if (ok) rx else null,
                        watchBaselineY = chain.watchBaselineY ?: if (ok) ry else null,
                        watchBaselineZ = chain.watchBaselineZ ?: if (ok) rz else null,
                        changedFromPrev = changed,
                        changeCount = changeCount,
                        lastDeltaMagnitude = deltaMag,
                        isStreamingToHud = (chain.id == hudId)
                    )
                    if (chain.id == hudId && ok) {
                        hudChainUpdated = next
                    }
                    next
                } else {
                    chain
                }
            }

            // Sort MOVING first, then LIVE, then ZERO, then STALE/PENDING (keeping pinned/static priority)
            val sortedChains = updatedChains.sortedWith(
                compareBy<WasmPointerChain>(
                    {
                        when (it.status) {
                            WasmPointerStatus.MOVING -> 0
                            WasmPointerStatus.LIVE -> 1
                            WasmPointerStatus.PENDING -> 2
                            WasmPointerStatus.ZERO -> 3
                            WasmPointerStatus.STALE -> 4
                        }
                    },
                    { !it.isStreamingToHud },
                    { !it.isPinned },
                    { !it.isLikelyStatic }
                )
            )

            val activeHud = hudChainUpdated
            val nextLocked = if (activeHud != null && activeHud.resolvedTargetAddress > 0L) {
                val xIdx = (activeHud.resolvedTargetAddress / 4L).toInt()
                LockedWasmCoords(
                    xIndex = xIdx,
                    yIndex = xIdx + 1,
                    zIndex = xIdx + 2,
                    xByteAddress = activeHud.resolvedTargetAddress,
                    yByteAddress = activeHud.resolvedTargetAddress + 4L,
                    zByteAddress = activeHud.resolvedTargetAddress + 8L,
                    xValue = activeHud.resolvedX,
                    yValue = activeHud.resolvedY,
                    zValue = activeHud.resolvedZ,
                    lockedAtTimestamp = prevFd.lockedCoords?.lockedAtTimestamp ?: formatTimestamp(System.currentTimeMillis()),
                    liveTickCount = tick,
                    isLivePolling = true
                )
            } else {
                prevFd.lockedCoords
            }

            val summaryMsg = buildString {
                append("Live Watch #$tick: ")
                if (movingCount > 0) append("$movingCount MOVING ✓ • ")
                append("$liveCount live")
                if (zeroCount > 0) append(" • $zeroCount zero")
                if (staleCount > 0) append(" • $staleCount stale")
            }

            val vitalsObj = json.optJSONObject("playerVitals")
            val nextPlayerVitals = if (vitalsObj != null) {
                val prevV = state.playerVitals
                val hp = if (vitalsObj.has("hp") && !vitalsObj.isNull("hp")) vitalsObj.optInt("hp") else null
                val maxHp = if (vitalsObj.has("maxHp") && !vitalsObj.isNull("maxHp")) vitalsObj.optInt("maxHp") else null
                val mp = if (vitalsObj.has("mp") && !vitalsObj.isNull("mp")) vitalsObj.optInt("mp") else null
                val maxMp = if (vitalsObj.has("maxMp") && !vitalsObj.isNull("maxMp")) vitalsObj.optInt("maxMp") else null
                val fp = if (vitalsObj.has("fp") && !vitalsObj.isNull("fp")) vitalsObj.optInt("fp") else null
                val maxFp = if (vitalsObj.has("maxFp") && !vitalsObj.isNull("maxFp")) vitalsObj.optInt("maxFp") else null
                val pName = vitalsObj.optString("name", "").takeIf { it.isNotBlank() }
                val peakHp = listOfNotNull(prevV?.observedPeakHp, hp, maxHp).maxOrNull()
                val peakMp = listOfNotNull(prevV?.observedPeakMp, mp, maxMp).maxOrNull()
                val peakFp = listOfNotNull(prevV?.observedPeakFp, fp, maxFp).maxOrNull()
                PlayerVitalsSnapshot(
                    playerName = pName ?: prevV?.playerName,
                    hp = hp,
                    maxHp = maxHp,
                    mp = mp,
                    maxMp = maxMp,
                    fp = fp,
                    maxFp = maxFp,
                    observedPeakHp = peakHp,
                    observedPeakMp = peakMp,
                    observedPeakFp = peakFp
                ).takeIf { it.hasAnyContent }
            } else {
                state.playerVitals
            }

            state.copy(
                discoveredPlayerPath = activeHud?.let { "WASM_PTR: ${it.expression}" } ?: state.discoveredPlayerPath,
                livePlayerCoords = activeHud?.let {
                    PlayerCoordinates(
                        path = "WASM_PTR: ${it.expression}",
                        x = it.resolvedX,
                        y = it.resolvedY,
                        z = it.resolvedZ
                    )
                } ?: state.livePlayerCoords,
                livePollActive = activeHud != null || state.livePollActive,
                playerVitals = nextPlayerVitals,
                wasmFloatDelta = prevFd.copy(
                    heapSourceLabel = sourceLabel.ifBlank { prevFd.heapSourceLabel },
                    heapByteLength = if (byteLen > 0L) byteLen else prevFd.heapByteLength,
                    isWatchingPointers = true,
                    isAutoVerifyingOnReload = false,
                    pointerWatchTickCount = tick,
                    pointerChains = sortedChains,
                    lockedCoords = nextLocked,
                    pointerStatusMessage = summaryMsg
                )
            )
        }
    }

    fun onTogglePinWasmPointerChain(chainId: String) {
        val snapshot = _uiState.value
        val existingChain = snapshot.wasmFloatDelta.pointerChains.firstOrNull { it.id == chainId } ?: return
        val nowTs = formatTimestamp(System.currentTimeMillis())
        val persistUrl = effectivePersistenceUrl(snapshot)
        val hostKey = SitePointerStore.normalizeHostKey(persistUrl)
        val nowPinned = !existingChain.isPinned
        val store = sitePointerStore

        // Perform synchronous disk persistence OUTSIDE _uiState.update to avoid CAS retries or silent drops
        val savedOnDisk: Boolean
        val persistedCount: Int
        val updatedChain = existingChain.copy(
            isPinned = nowPinned,
            isSavedLocally = nowPinned && store != null && hostKey != null
        )
        if (store != null && hostKey != null) {
            val savedList = if (nowPinned) {
                store.savePointerForUrl(persistUrl, updatedChain)
            } else {
                store.removePointerForUrl(persistUrl, chainId)
            }
            persistedCount = savedList.size
            savedOnDisk = nowPinned && savedList.any { it.id == chainId }
        } else {
            savedOnDisk = false
            persistedCount = snapshot.wasmFloatDelta.pointerChains.count {
                if (it.id == chainId) false else it.isSavedLocally
            }
        }

        val finalChain = updatedChain.copy(isSavedLocally = savedOnDisk)

        _uiState.update { state ->
            val updatedChains = state.wasmFloatDelta.pointerChains.map { chain ->
                if (chain.id == chainId) finalChain else chain
            }
            val stagingKey = "WASM_PTR: ${finalChain.expression}"
            val regionTag = if (finalChain.isLikelyStatic) "STATIC" else "HEAP"
            val detail = "[L${finalChain.depth} $regionTag ${finalChain.status.label}] -> ${formatHexAddress(finalChain.resolvedTargetAddress)} • (${String.format(Locale.US, "%.2f", finalChain.resolvedX)}, ${String.format(Locale.US, "%.2f", finalChain.resolvedY)}, ${String.format(Locale.US, "%.2f", finalChain.resolvedZ)})"
            val mergedStaging = if (nowPinned) {
                mergeIntoStagingBuffer(
                    existing = state.stagingBuffer,
                    incoming = listOf(stagingKey to detail),
                    sourceStep = "Pointer Chain",
                    scanNumber = state.scanCounter.coerceAtLeast(1),
                    timestamp = nowTs,
                    forcePin = true
                )
            } else {
                state.stagingBuffer.map { item ->
                    if (item.path == stagingKey) item.copy(isPinned = false) else item
                }
            }

            state.copy(
                stagingBuffer = sortStagingBuffer(mergedStaging),
                wasmFloatDelta = state.wasmFloatDelta.copy(
                    pointerChains = updatedChains,
                    savedPointerCountForHost = persistedCount
                )
            )
        }
        refreshSavedSiteProfiles()

        val toast = if (nowPinned) {
            if (savedOnDisk && hostKey != null) {
                "Saved ${finalChain.expression} to disk for $hostKey (auto-verifies on reload)"
            } else {
                "Bookmarked ${finalChain.expression} (load a site URL to persist across restarts)"
            }
        } else {
            "Removed saved pointer chain ${finalChain.expression}"
        }
        _effects.tryEmit(DiagnosticsEffect.ShowToast(toast))
    }

    private fun buildPointerChainsPayloadJson(chains: List<WasmPointerChain>): String {
        val payloadArr = JSONArray()
        chains.forEach { c ->
            val offArr = JSONArray()
            c.offsets.forEach { offArr.put(it) }
            payloadArr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("basePointerAddress", c.basePointerAddress)
                    .put("offsets", offArr)
                    .put("expression", c.expression)
            )
        }
        return payloadArr.toString()
    }

    fun onWasmFloatCandidatesUpdatedFromBridge(jsonPayload: String) {
        val json = try {
            JSONObject(jsonPayload)
        } catch (_: Exception) {
            return
        }
        if (json.optString("stage", "") != "WASM_FLOAT_WATCH_TICK") return

        val tick = json.optInt("tick", 1)
        val sourceLabel = json.optString("heapSourceLabel", "")
        val byteLen = json.optLong("heapByteLength", 0L)
        val arr = json.optJSONArray("candidates") ?: JSONArray()
        val lockedObj = json.optJSONObject("locked")

        _uiState.update { state ->
            val prevState = state.wasmFloatDelta
            val prevByIdx = prevState.candidates.associateBy { it.heapIndex }
            val pinnedSet = prevState.pinnedIndices

            val parsed = buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val idx = obj.optInt("heapIndex", -1)
                    if (idx < 0) continue
                    val byteAddr = obj.optLong("byteAddress", idx.toLong() * 4L)
                    val baseVal = obj.optDouble("baselineValue", 0.0)
                    val curVal = obj.optDouble("currentValue", 0.0)
                    val deltaVal = obj.optDouble("delta", curVal - baseVal)
                    val cluster = obj.optInt("clusterSize", 1)
                    val axis = obj.optString("axisHint", "")
                    val isPinned = pinnedSet.contains(idx) || obj.optBoolean("isPinned", false)
                    val prev = prevByIdx[idx]
                    val changed = prev != null && kotlin.math.abs(curVal - prev.currentValue) > 0.0005
                    val nextCount = when {
                        prev == null -> 0
                        changed -> prev.changeCount + 1
                        else -> prev.changeCount
                    }
                    add(
                        WasmFloatDeltaCandidate(
                            heapIndex = idx,
                            byteAddress = byteAddr,
                            baselineValue = baseVal,
                            currentValue = curVal,
                            delta = deltaVal,
                            clusterSize = cluster,
                            axisHint = axis,
                            isPinned = isPinned,
                            changedFromPrev = changed,
                            changeCount = nextCount
                        )
                    )
                }
            }

            val updatedLocked = if (lockedObj != null && prevState.lockedCoords != null) {
                prevState.lockedCoords.copy(
                    xValue = lockedObj.optDouble("xValue", prevState.lockedCoords.xValue),
                    yValue = lockedObj.optDouble("yValue", prevState.lockedCoords.yValue),
                    zValue = lockedObj.optDouble("zValue", prevState.lockedCoords.zValue),
                    liveTickCount = tick,
                    isLivePolling = true
                )
            } else {
                prevState.lockedCoords
            }

            state.copy(
                wasmFloatDelta = prevState.copy(
                    heapSourceLabel = sourceLabel.ifBlank { prevState.heapSourceLabel },
                    heapByteLength = if (byteLen > 0L) byteLen else prevState.heapByteLength,
                    isLiveWatching = parsed.isNotEmpty() || updatedLocked != null,
                    liveWatchTickCount = tick,
                    candidates = parsed,
                    lockedCoords = updatedLocked
                )
            )
        }
    }

    // Passive Resource & Asset Request Monitor (called from WebViewClient.shouldInterceptRequest)
    // Never modifies or blocks WebView traffic; asynchronously mirrors .bin/.wasm/.json to filesDir for ByteBuffer audit.
    fun onResourceRequestObserved(context: Context, request: WebResourceRequest) {
        val uri = request.url ?: return
        val fullUrl = uri.toString()
        val pathLower = (uri.path ?: fullUrl).lowercase(Locale.US)
        val matchedExt = matchTrackedAssetExtension(pathLower) ?: return
        val fileName = BinaryAssetInspector.extractFileName(fullUrl, matchedExt)
        val shouldCache = BinaryAssetInspector.shouldCaptureLocally(matchedExt)
        val appContext = context.applicationContext

        val initialRecord = InterceptedAsset(
            url = fullUrl,
            extension = matchedExt,
            method = request.method ?: "GET",
            timestamp = formatTimestamp(System.currentTimeMillis()),
            isForMainFrame = request.isForMainFrame,
            fileName = fileName,
            captureStatus = if (shouldCache) AssetCaptureStatus.CACHING else AssetCaptureStatus.OBSERVED,
            structuralSummary = if (shouldCache) "Mirroring to filesDir for ByteBuffer audit…" else "Observed network request"
        )

        viewModelScope.launch(Dispatchers.Main.immediate) {
            if (!seenAssetUrls.add(fullUrl)) return@launch
            if (seenAssetUrls.size > MAX_TRACKED_ASSETS) {
                val oldest = seenAssetUrls.firstOrNull()
                if (oldest != null) seenAssetUrls.remove(oldest)
            }
            _uiState.update { state ->
                val updated = (state.interceptedAssets + initialRecord).takeLast(MAX_TRACKED_ASSETS)
                state.copy(interceptedAssets = updated)
            }
            appendLog(
                category = LogCategory.ASSET,
                summary = "Intercepted [$matchedExt] $fileName",
                payload = "${initialRecord.method} $fullUrl"
            )

            if (shouldCache) {
                val activeReferer = _uiState.value.activeUrl
                val userAgent = if (_uiState.value.isDesktopMode) DESKTOP_USER_AGENT else "Mozilla/5.0 (Linux; Android)"
                val audited = withContext(Dispatchers.IO) {
                    BinaryAssetInspector.fetchSaveAndInspect(
                        context = appContext,
                        asset = initialRecord,
                        userAgent = userAgent,
                        refererUrl = activeReferer
                    )
                }
                _uiState.update { state ->
                    val updatedList = state.interceptedAssets.map { item ->
                        if (item.url == fullUrl) audited else item
                    }
                    state.copy(interceptedAssets = updatedList)
                }
                if (audited.captureStatus == AssetCaptureStatus.CAPTURED) {
                    appendLog(
                        category = LogCategory.ASSET,
                        summary = "Audited [$matchedExt] $fileName (${BinaryAssetInspector.formatBytes(audited.byteSize)}) • ${audited.magicSignature}",
                        payload = buildString {
                            appendLine("URL: ${audited.url}")
                            appendLine("Local: filesDir/${audited.localRelativePath}")
                            appendLine("SHA-256: ${audited.sha256Prefix}…")
                            appendLine(audited.structuralDetails)
                        }.trimEnd()
                    )
                }
            }
        }
    }

    fun onClearAssetCache(context: Context) {
        val appContext = context.applicationContext
        viewModelScope.launch {
            val removedCount = withContext(Dispatchers.IO) {
                BinaryAssetInspector.clearLocalCache(appContext)
            }
            seenAssetUrls.clear()
            _uiState.update { it.copy(interceptedAssets = emptyList()) }
            appendLog(
                LogCategory.ASSET,
                "Cleared local binary asset cache ($removedCount files removed)",
                "Cleared filesDir/captured_assets"
            )
            _effects.tryEmit(DiagnosticsEffect.ShowToast("Cleared $removedCount cached asset files"))
        }
    }

    // Step 4 / 5: Export Complete Telemetry Handoff JSON Report
    fun requestExportReport(context: Context, armBridge: () -> Unit, disarmBridge: () -> Boolean) {
        if (_uiState.value.isExporting) return
        val appContext = context.applicationContext
        pendingExportContext = appContext
        _uiState.update { it.copy(isExporting = true) }
        armBridge()
        appendLog(
            LogCategory.SYSTEM,
            "Exporting full telemetry report (pinned candidates, live coords, entities, logs)…",
            "Invoking TelemetryBridge.exportReport()"
        )
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(DiagnosticsScripts.buildCollectViewportMetricsScript()))

        pendingExportJob?.cancel()
        pendingExportJob = viewModelScope.launch {
            delay(1_200L)
            if (disarmBridge()) {
                val fallbackViewportJson = JSONObject()
                    .put("url", _uiState.value.activeUrl)
                    .put("note", "Viewport metrics collected natively (WebView JS callback timed out)")
                    .toString()
                finalizeReportExport(appContext, fallbackViewportJson)
            }
        }
    }

    fun onReportPayloadFromBridge(viewportJson: String) {
        pendingExportJob?.cancel()
        val appContext = pendingExportContext ?: return
        finalizeReportExport(appContext, viewportJson)
    }

    private fun finalizeReportExport(appContext: Context, viewportJson: String) {
        val snapshot = _uiState.value
        viewModelScope.launch {
            val fullReportJson = withContext(Dispatchers.Default) {
                buildFullReportJson(snapshot, viewportJson)
            }
            val outcome = withContext(Dispatchers.IO) {
                ReportExporter.exportJsonToDownloads(appContext, fullReportJson)
            }
            _uiState.update {
                it.copy(
                    isExporting = false,
                    lastExport = outcome
                )
            }
            if (outcome.success) {
                appendLog(
                    LogCategory.SYSTEM,
                    "Exported JSON report -> ${outcome.displayPath}",
                    "Saved ${fullReportJson.length} bytes to ${outcome.displayPath}"
                )
                _effects.tryEmit(
                    DiagnosticsEffect.ShowToast(
                        appContext.getString(R.string.toast_report_saved, outcome.fileName)
                    )
                )
            } else {
                val err = outcome.errorMessage ?: "Unknown error"
                appendLog(LogCategory.ERROR, "Report export failed", err)
                _effects.tryEmit(
                    DiagnosticsEffect.ShowToast(
                        appContext.getString(R.string.toast_report_failed, err)
                    )
                )
            }
        }
    }

    fun onShareLastReport() {
        val export = _uiState.value.lastExport
        if (export != null && export.success && export.contentUri != null) {
            _effects.tryEmit(DiagnosticsEffect.ShareReport(export.contentUri, export.fileName, export.mimeType))
        }
    }

    // Complete Log Dump Export (.txt or .json) from Logs tab
    fun exportFullLogDump(context: Context, asJson: Boolean = false) {
        if (_uiState.value.isExportingLogDump) return
        val appContext = context.applicationContext
        val snapshot = _uiState.value
        _uiState.update { it.copy(isExportingLogDump = true) }

        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                if (asJson) {
                    val fallbackViewport = JSONObject()
                        .put("url", snapshot.activeUrl)
                        .put("width", snapshot.entityViewportWidth)
                        .put("height", snapshot.entityViewportHeight)
                        .toString()
                    val jsonContent = buildFullReportJson(snapshot, fallbackViewport)
                    ReportExporter.exportJsonToDownloads(
                        context = appContext,
                        jsonContent = jsonContent,
                        filePrefix = "webview_log_dump"
                    )
                } else {
                    val textDump = buildFullLogDumpText(snapshot)
                    ReportExporter.exportTextLogDumpToDownloads(appContext, textDump)
                }
            }

            _uiState.update {
                it.copy(
                    isExportingLogDump = false,
                    lastLogDumpExport = outcome
                )
            }

            if (outcome.success) {
                appendLog(
                    LogCategory.SYSTEM,
                    "Exported full log dump -> ${outcome.displayPath}",
                    "Saved ${snapshot.logs.size} logs, ${snapshot.stagingBuffer.size} staged variables, ${snapshot.discoveredEntities.size} entities, and ${snapshot.interceptedAssets.size} asset requests to ${outcome.displayPath}"
                )
                _effects.tryEmit(
                    DiagnosticsEffect.ShowToast(
                        appContext.getString(R.string.toast_log_dump_saved, outcome.fileName)
                    )
                )
            } else {
                val err = outcome.errorMessage ?: "Unknown error"
                appendLog(LogCategory.ERROR, "Log dump export failed", err)
                _effects.tryEmit(
                    DiagnosticsEffect.ShowToast(
                        appContext.getString(R.string.toast_report_failed, err)
                    )
                )
            }
        }
    }

    fun onShareLastLogDump() {
        val export = _uiState.value.lastLogDumpExport
        if (export != null && export.success && export.contentUri != null) {
            _effects.tryEmit(DiagnosticsEffect.ShareReport(export.contentUri, export.fileName, export.mimeType))
        }
    }

    // Manual JavaScript Execution Drawer
    fun onToggleJsDrawer(open: Boolean? = null) {
        _uiState.update { it.copy(isJsDrawerOpen = open ?: !it.isJsDrawerOpen) }
    }

    fun onManualJsInputChanged(script: String) {
        _uiState.update { it.copy(manualJsInput = script) }
    }

    fun onExecuteManualScript() {
        val code = _uiState.value.manualJsInput.trim()
        if (code.isEmpty()) return
        onScanStatusChanged(AsyncScanStatus.RUNNING, "Evaluating manual JS…")
        appendLog(LogCategory.JS, "Exec> ${code.take(90)}", code)
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(DiagnosticsScripts.buildManualEvalScript(code)))
    }

    fun onSelectFilter(category: LogCategory) {
        _uiState.update { it.copy(selectedFilter = category) }
    }

    fun onToggleConsoleExpanded() {
        val nextExpanded = !_uiState.value.isConsoleExpanded
        onSetConsoleSplitFraction(if (nextExpanded) 0.88f else 0.48f)
    }

    fun onClearLogs() {
        _uiState.update { it.copy(logs = emptyList()) }
    }

    fun onTelemetryFromBridge(rawCategory: String, payload: String) {
        val category = LogCategory.fromRaw(rawCategory)
        val parsedSummary = parseStructuredTelemetry(category, payload)
        appendLog(category, parsedSummary, prettyFormatIfJson(payload))
    }

    private fun parseStructuredTelemetry(category: LogCategory, payload: String): String {
        val json = try {
            JSONObject(payload)
        } catch (_: Exception) {
            return payload.lineSequence().firstOrNull()?.take(110) ?: category.label
        }

        val nowTs = formatTimestamp(System.currentTimeMillis())
        val stage = json.optString("stage", "")

        return when (stage) {
            "GAME_NAMESPACE_DETECTED" -> {
                val nsArray = json.optJSONArray("detectedNamespaces") ?: JSONArray()
                val detectedNs = extractStringArray(nsArray)
                val entriesArr = json.optJSONArray("detectedEntries") ?: JSONArray()
                val rootKeys = mutableListOf<String>()
                val stagingPairs = mutableListOf<Pair<String, String>>()

                for (i in 0 until entriesArr.length()) {
                    val obj = entriesArr.optJSONObject(i) ?: continue
                    val key = obj.optString("key", "").trim()
                    val path = obj.optString("path", if (key.isNotEmpty()) "window.$key" else "").trim()
                    if (path.isEmpty()) continue
                    if (key.isNotEmpty() && !key.contains(".")) {
                        rootKeys.add(key)
                    }
                    val ctor = obj.optString("constructor", obj.optString("type", "Object"))
                    val keyCount = obj.optInt("keyCount", 0)
                    val previewList = extractStringArray(obj.optJSONArray("previewKeys")).take(4)
                    val previewSuffix = if (previewList.isNotEmpty()) " • ${previewList.joinToString(", ")}" else ""
                    stagingPairs.add(path to "[Auto-Root] $ctor ($keyCount keys$previewSuffix)")
                }

                _uiState.update { state ->
                    val currentScan = state.scanCounter.coerceAtLeast(1)
                    val mergedStaging = mergeIntoStagingBuffer(
                        existing = state.stagingBuffer,
                        incoming = stagingPairs,
                        sourceStep = "Auto-Root",
                        scanNumber = currentScan,
                        timestamp = nowTs
                    )
                    state.copy(
                        customGlobals = (detectedNs.map { it.removePrefix("window.") } + rootKeys + state.customGlobals).distinct(),
                        detectedGameNamespaces = (state.detectedGameNamespaces + detectedNs).distinct(),
                        stagingBuffer = mergedStaging
                    )
                }
                "Auto-detected ${detectedNs.size} game namespace(s): ${detectedNs.joinToString(", ")} • Staged immediately"
            }

            "MANUAL_PATH_QUERY" -> {
                val found = json.optBoolean("found", false)
                val normalizedPath = json.optString("normalizedPath", json.optString("requestedPath", "")).trim()
                if (!found) {
                    val msg = json.optString("message", "Property path not found on window.")
                    _uiState.update {
                        it.copy(
                            scanStatus = AsyncScanStatus.IDLE,
                            scanStatusDetail = "Not found: $normalizedPath",
                            lastManualQuerySummary = "Not found: $msg"
                        )
                    }
                    _effects.tryEmit(DiagnosticsEffect.ShowToast("Not found: $normalizedPath"))
                    "Manual Query failed: $msg"
                } else {
                    val rootKey = json.optString("rootKey", "").trim()
                    val detail = json.optString("detail", json.optString("type", "Object"))
                    val childArr = json.optJSONArray("childEntries") ?: JSONArray()
                    val childPairs = buildList {
                        for (i in 0 until childArr.length()) {
                            val obj = childArr.optJSONObject(i) ?: continue
                            val cp = obj.optString("path", "").trim()
                            val cd = obj.optString("detail", "Object")
                            if (cp.isNotEmpty()) add(cp to cd)
                        }
                    }

                    _uiState.update { state ->
                        val nextScan = state.scanCounter.coerceAtLeast(1)
                        val withMain = mergeIntoStagingBuffer(
                            existing = state.stagingBuffer,
                            incoming = listOf(normalizedPath to detail),
                            sourceStep = "Manual Query",
                            scanNumber = nextScan,
                            timestamp = nowTs,
                            forcePin = true
                        )
                        val withChildren = if (childPairs.isNotEmpty()) {
                            mergeIntoStagingBuffer(
                                existing = withMain,
                                incoming = childPairs,
                                sourceStep = "Manual Query",
                                scanNumber = nextScan,
                                timestamp = nowTs,
                                forcePin = false
                            )
                        } else withMain

                        state.copy(
                            step1Completed = true,
                            customGlobals = if (rootKey.isNotEmpty()) {
                                (listOf(rootKey) + state.customGlobals).distinct()
                            } else state.customGlobals,
                            stagingBuffer = withChildren,
                            lastManualQuerySummary = "Staged & pinned $normalizedPath → $detail",
                            scanStatus = AsyncScanStatus.COMPLETED,
                            scanStatusDetail = "Staged $normalizedPath"
                        )
                    }
                    _effects.tryEmit(DiagnosticsEffect.ShowToast("Staged & pinned: $normalizedPath"))
                    "Manual Query staged $normalizedPath ($detail)" +
                        if (childPairs.isNotEmpty()) " + ${childPairs.size} child hints" else ""
                }
            }

            "GLOBAL_SCOPE_BATCH" -> {
                val batchIndex = json.optInt("batchIndex", 1)
                val totalBatches = json.optInt("totalBatches", 1)
                val itemStart = json.optInt("itemStart", 1)
                val itemEnd = json.optInt("itemEnd", 100)
                val totalItems = json.optInt("totalItems", itemEnd)
                val foundCount = json.optInt("foundCount", 0)
                val batchArray = json.optJSONArray("batchCustomGlobals") ?: JSONArray()
                val batchKeys = mutableListOf<String>()
                val stagingPairs = mutableListOf<Pair<String, String>>()

                for (i in 0 until batchArray.length()) {
                    val obj = batchArray.optJSONObject(i) ?: continue
                    val key = obj.optString("key", "").trim()
                    if (key.isEmpty()) continue
                    batchKeys.add(key)
                    val path = obj.optString("path", "window.$key")
                    val ctor = obj.optString("constructor", obj.optString("type", "Object"))
                    val keyCount = obj.optInt("keyCount", 0)
                    val previewArr = obj.optJSONArray("previewKeys")
                    val previewList = buildList {
                        if (previewArr != null) {
                            for (pi in 0 until minOf(previewArr.length(), 4)) {
                                val pk = previewArr.optString(pi, "").trim()
                                if (pk.isNotEmpty()) add(pk)
                            }
                        }
                    }
                    val previewSuffix = if (previewList.isNotEmpty()) " • ${previewList.joinToString(", ")}" else ""
                    stagingPairs.add(path to "$ctor ($keyCount keys$previewSuffix)")
                }

                _uiState.update { state ->
                    val currentScan = state.scanCounter.coerceAtLeast(1)
                    val mergedStaging = mergeIntoStagingBuffer(
                        existing = state.stagingBuffer,
                        incoming = stagingPairs,
                        sourceStep = "Step 1 B$batchIndex",
                        scanNumber = currentScan,
                        timestamp = nowTs
                    )
                    state.copy(
                        customGlobals = (state.customGlobals + batchKeys).distinct(),
                        stagingBuffer = mergedStaging,
                        scopeBatchProgress = ScopeBatchProgress(
                            isActive = batchIndex < totalBatches,
                            isPaused = false,
                            currentBatch = batchIndex,
                            totalBatches = totalBatches,
                            itemStart = itemStart,
                            itemEnd = itemEnd,
                            totalItems = totalItems,
                            foundCount = foundCount
                        )
                    )
                }
                "Batch $batchIndex/$totalBatches ($itemStart–$itemEnd): +${batchKeys.size} custom globals staged (${batchKeys.take(4).joinToString(", ")})"
            }

            "GLOBAL_SCOPE_INSPECTED" -> {
                val canvases = json.optInt("canvasCount", 0)
                val durationMs = json.optDouble("durationMs", 0.0)
                val batchesExecuted = json.optInt("batchesExecuted", 1)
                val totalFiltered = json.optInt("totalWindowKeysFiltered", 0)
                val customArray = json.optJSONArray("customGlobals") ?: JSONArray()
                val globalKeys = mutableListOf<String>()
                val stagingPairs = mutableListOf<Pair<String, String>>()

                for (i in 0 until customArray.length()) {
                    val obj = customArray.optJSONObject(i) ?: continue
                    val key = obj.optString("key", "").trim()
                    if (key.isEmpty()) continue
                    globalKeys.add(key)
                    val path = obj.optString("path", "window.$key")
                    val ctor = obj.optString("constructor", obj.optString("type", "Object"))
                    val keyCount = obj.optInt("keyCount", 0)
                    val previewArr = obj.optJSONArray("previewKeys")
                    val previewList = buildList {
                        if (previewArr != null) {
                            for (pi in 0 until minOf(previewArr.length(), 4)) {
                                val pk = previewArr.optString(pi, "").trim()
                                if (pk.isNotEmpty()) add(pk)
                            }
                        }
                    }
                    val previewSuffix = if (previewList.isNotEmpty()) " • ${previewList.joinToString(", ")}" else ""
                    stagingPairs.add(path to "$ctor ($keyCount keys$previewSuffix)")
                }

                val detectedNs = extractStringArray(json.optJSONArray("detectedNamespaces"))
                _uiState.update { state ->
                    val nextScan = state.scanCounter + 1
                    val mergedStaging = mergeIntoStagingBuffer(
                        existing = state.stagingBuffer,
                        incoming = stagingPairs,
                        sourceStep = "Step 1",
                        scanNumber = nextScan,
                        timestamp = nowTs
                    )
                    state.copy(
                        scanCounter = nextScan,
                        step1Completed = true,
                        customGlobals = (state.customGlobals + globalKeys).distinct(),
                        detectedGameNamespaces = (state.detectedGameNamespaces + detectedNs).distinct(),
                        step1DurationMs = durationMs,
                        canvasCount = canvases,
                        stagingBuffer = mergedStaging,
                        scopeBatchProgress = ScopeBatchProgress(
                            isActive = false,
                            isPaused = false,
                            currentBatch = batchesExecuted,
                            totalBatches = batchesExecuted,
                            itemStart = 1,
                            itemEnd = totalFiltered,
                            totalItems = totalFiltered,
                            foundCount = globalKeys.size
                        ),
                        scanStatus = AsyncScanStatus.COMPLETED,
                        scanStatusDetail = "Scan #$nextScan: ${globalKeys.size} globals ($batchesExecuted/$batchesExecuted batches)"
                    )
                }
                "Step 1 ($batchesExecuted/$batchesExecuted batches, ${durationMs}ms): Found ${globalKeys.size} custom globals across $totalFiltered keys • Staged"
            }

            "BASELINE_CAPTURED" -> {
                val metrics = json.optInt("metricCount", 0)
                val roots = json.optInt("rootObjectCount", 0)
                val durationMs = json.optDouble("durationMs", -1.0)
                val skippedBinary = json.optInt("skippedBinaryOrWasm", 0)
                val containersArr = json.optJSONArray("candidateContainers") ?: JSONArray()
                val stagingPairs = buildList {
                    for (i in 0 until containersArr.length()) {
                        val obj = containersArr.optJSONObject(i) ?: continue
                        val p = obj.optString("path", "").trim()
                        val d = obj.optString("detail", "Coordinate container")
                        if (p.isNotEmpty()) add(p to d)
                    }
                }
                val timingLabel = if (durationMs >= 0.0) "${String.format(Locale.US, "%.2f", durationMs)}ms" else "fast"

                _uiState.update { state ->
                    val nextScan = state.scanCounter + 1
                    val mergedStaging = mergeIntoStagingBuffer(
                        existing = state.stagingBuffer,
                        incoming = stagingPairs,
                        sourceStep = "Step 2A",
                        scanNumber = nextScan,
                        timestamp = nowTs
                    )
                    state.copy(
                        scanCounter = nextScan,
                        baselineCaptured = true,
                        baselineMetricCount = metrics,
                        stagingBuffer = mergedStaging,
                        scanStatus = AsyncScanStatus.COMPLETED,
                        scanStatusDetail = "2A Captured ($metrics nums in $timingLabel)"
                    )
                }
                "Step 2A ($timingLabel): Captured $metrics primitive numbers across $roots globals (skipped $skippedBinary WASM/buffer objects)"
            }

            "BASELINE_MISSING" -> {
                _uiState.update {
                    it.copy(
                        scanStatus = AsyncScanStatus.IDLE,
                        scanStatusDetail = "Capture 2A while standing still first"
                    )
                }
                json.optString("message", "Stand still and capture 2A baseline first.")
            }

            "DELTA_COMPUTED" -> {
                val deltasArray = json.optJSONArray("deltas") ?: JSONArray()
                val isolatedList = buildList {
                    for (i in 0 until deltasArray.length()) {
                        val obj = deltasArray.optJSONObject(i) ?: continue
                        add(
                            IsolatedCoordinatePath(
                                path = obj.optString("path", ""),
                                baselineValue = obj.optDouble("baseline", 0.0),
                                currentValue = obj.optDouble("current", 0.0),
                                delta = obj.optDouble("delta", 0.0)
                            )
                        )
                    }
                }
                val playerPath = json.optString("discoveredPlayerPath", "").takeIf { it.isNotBlank() && it != "null" }
                val propKeys = json.optString("propertyKeys", "")
                val liveObj = json.optJSONObject("liveSample")
                val liveCoords = if (playerPath != null && liveObj != null) {
                    PlayerCoordinates(
                        path = playerPath,
                        x = liveObj.optDouble("x", 0.0),
                        y = liveObj.optDouble("y", 0.0),
                        z = liveObj.optDouble("z", 0.0)
                    )
                } else null

                val stagingPairs = buildList {
                    if (playerPath != null) {
                        val keySummary = if (propKeys.isNotBlank()) "Live ref [$propKeys]" else "Live player container"
                        add(playerPath to keySummary)
                    }
                    isolatedList.take(15).forEach { item ->
                        if (item.path.isNotBlank()) {
                            val sign = if (item.delta >= 0) "+" else ""
                            add(item.path to "Δ $sign${String.format(Locale.US, "%.2f", item.delta)} (cur: ${String.format(Locale.US, "%.2f", item.currentValue)})")
                        }
                    }
                }

                _uiState.update { state ->
                    val nextScan = state.scanCounter + 1
                    val mergedStaging = mergeIntoStagingBuffer(
                        existing = state.stagingBuffer,
                        incoming = stagingPairs,
                        sourceStep = "Step 2B",
                        scanNumber = nextScan,
                        timestamp = nowTs
                    )
                    state.copy(
                        scanCounter = nextScan,
                        isolatedPaths = isolatedList,
                        discoveredPlayerPath = playerPath ?: state.discoveredPlayerPath,
                        playerPropertyKeys = if (propKeys.isNotBlank()) propKeys else state.playerPropertyKeys,
                        livePlayerCoords = liveCoords ?: state.livePlayerCoords,
                        livePollActive = playerPath != null || state.livePollActive,
                        stagingBuffer = mergedStaging,
                        scanStatus = if (playerPath != null) AsyncScanStatus.COMPLETED else AsyncScanStatus.IDLE,
                        scanStatusDetail = playerPath?.let { "Live 200ms: $it" } ?: "No movement detected"
                    )
                }
                val elapsedMs = json.optInt("elapsedMs", 0)
                if (playerPath == null) {
                    "Step 2B (${elapsedMs}ms): No player movement detected — walk 5–10 steps and retry."
                } else {
                    "Step 2B (${elapsedMs}ms): Live 200ms lock on $playerPath [$propKeys] (${isolatedList.size} deltas staged)"
                }
            }

            "LIVE_REF_LOCKED" -> {
                val playerPath = json.optString("discoveredPlayerPath", "").takeIf { it.isNotBlank() && it != "null" }
                val propKeys = json.optString("propertyKeys", "")
                val liveObj = json.optJSONObject("liveSample")
                val liveCoords = if (playerPath != null && liveObj != null) {
                    PlayerCoordinates(
                        path = playerPath,
                        x = liveObj.optDouble("x", 0.0),
                        y = liveObj.optDouble("y", 0.0),
                        z = liveObj.optDouble("z", 0.0)
                    )
                } else null
                _uiState.update { state ->
                    state.copy(
                        discoveredPlayerPath = playerPath ?: state.discoveredPlayerPath,
                        playerPropertyKeys = propKeys,
                        livePlayerCoords = liveCoords ?: state.livePlayerCoords,
                        livePollActive = playerPath != null,
                        scanStatus = AsyncScanStatus.COMPLETED,
                        scanStatusDetail = playerPath?.let { "Live 200ms: $it" } ?: state.scanStatusDetail
                    )
                }
                "Locked live 200ms reference on ${playerPath ?: "candidate"} [$propKeys]"
            }

            "LIVE_REF_FAILED" -> {
                val msg = json.optString("message", "Could not bind candidate reference.")
                _uiState.update { it.copy(scanStatus = AsyncScanStatus.ERROR, scanStatusDetail = "Live bind failed") }
                msg
            }

            "ANCHOR_MISSING" -> {
                _uiState.update {
                    it.copy(
                        scanStatus = AsyncScanStatus.IDLE,
                        scanStatusDetail = "Complete Step 2B first"
                    )
                }
                json.optString("message", "Complete Step 2B first.")
            }

            "ENTITIES_SCANNED" -> {
                val count = json.optInt("entityCount", 0)
                val arrayPath = json.optString("entityArrayPath", "").takeIf { it.isNotBlank() && it != "null" }
                val mode = json.optString("projectionMode", "pinhole_viewport_fallback")
                val vpObj = json.optJSONObject("viewport")
                val vpW = vpObj?.optInt("width", 0) ?: 0
                val vpH = vpObj?.optInt("height", 0) ?: 0
                val entitiesArr = json.optJSONArray("entities") ?: JSONArray()
                val parsedEntities = buildList {
                    for (i in 0 until entitiesArr.length()) {
                        val obj = entitiesArr.optJSONObject(i) ?: continue
                        val world = obj.optJSONObject("world")
                        add(
                            DiscoveredEntitySample(
                                name = obj.optString("name", "Entity"),
                                id = obj.optString("id", ""),
                                path = obj.optString("path", ""),
                                worldX = world?.optDouble("x", 0.0) ?: 0.0,
                                worldY = world?.optDouble("y", 0.0) ?: 0.0,
                                worldZ = world?.optDouble("z", 0.0) ?: 0.0,
                                distanceToPlayer = obj.optDouble("distanceToPlayer", 0.0),
                                screenX = obj.optDouble("screenX", 0.0),
                                screenY = obj.optDouble("screenY", 0.0),
                                inViewport = obj.optBoolean("inViewport", false),
                                sampleKeys = extractStringArray(obj.optJSONArray("sampleKeys"))
                            )
                        )
                    }
                }
                _uiState.update { state ->
                    val mergedStaging = if (arrayPath != null) {
                        mergeIntoStagingBuffer(
                            existing = state.stagingBuffer,
                            incoming = listOf(arrayPath to "Entity collection ($count detected)"),
                            sourceStep = "Step 3",
                            scanNumber = state.scanCounter + 1,
                            timestamp = nowTs
                        )
                    } else state.stagingBuffer

                    state.copy(
                        scanCounter = state.scanCounter + 1,
                        discoveredEntityArrayPath = arrayPath,
                        entityProjectionMode = mode,
                        entityViewportWidth = vpW,
                        entityViewportHeight = vpH,
                        discoveredEntities = parsedEntities,
                        lastEntityCount = count,
                        stagingBuffer = mergedStaging,
                        scanStatus = if (count > 0) AsyncScanStatus.COMPLETED else AsyncScanStatus.IDLE,
                        scanStatusDetail = if (arrayPath != null) "$count entities in $arrayPath" else "0 sibling entities"
                    )
                }
                if (arrayPath != null) {
                    "Step 3: Found $count entities in $arrayPath (projection: $mode)"
                } else {
                    "Step 3: 0 sibling entity collections found near ${_uiState.value.discoveredPlayerPath}"
                }
            }

            "WASM_ENTITIES_DISCOVERED" -> {
                val errorMsg = json.optString("error", "")
                if (errorMsg.isNotBlank()) {
                    _uiState.update {
                        it.copy(
                            scanStatus = AsyncScanStatus.ERROR,
                            scanStatusDetail = errorMsg.take(60)
                        )
                    }
                    _effects.tryEmit(DiagnosticsEffect.ShowToast(errorMsg))
                    "Step 3 (WASM): $errorMsg"
                } else {
                    val durationMs = json.optDouble("durationMs", 0.0)
                    val arrayPath = json.optString("arrayPath", "window.HEAPF32")
                    val mode = json.optString("projectionMode", "WASM Spatial HEAPF32")
                    val vpW = json.optInt("viewportWidth", 0)
                    val vpH = json.optInt("viewportHeight", 0)
                    val rawCount = json.optInt("rawCandidateCount", 0)
                    val parsedEntities = parseWasmEntitiesArray(json.optJSONArray("entities"))

                    _uiState.update { state ->
                        val nextScan = state.scanCounter + 1
                        val mergedStaging = if (parsedEntities.isNotEmpty()) {
                            mergeIntoStagingBuffer(
                                existing = state.stagingBuffer,
                                incoming = listOf(arrayPath to "WASM nearby entities (${parsedEntities.size} detected)"),
                                sourceStep = "Step 3 WASM",
                                scanNumber = nextScan,
                                timestamp = nowTs
                            )
                        } else state.stagingBuffer

                        state.copy(
                            scanCounter = nextScan,
                            discoveredEntityArrayPath = arrayPath,
                            entityProjectionMode = mode,
                            entityViewportWidth = vpW,
                            entityViewportHeight = vpH,
                            discoveredEntities = parsedEntities,
                            lastEntityCount = parsedEntities.size,
                            isWasmEntitiesLivePolling = false,
                            wasmEntityLiveTickCount = 0,
                            stagingBuffer = mergedStaging,
                            scanStatus = if (parsedEntities.isNotEmpty()) AsyncScanStatus.COMPLETED else AsyncScanStatus.IDLE,
                            scanStatusDetail = "Step 3 WASM: ${parsedEntities.size} nearby (${durationMs}ms)"
                        )
                    }
                    if (parsedEntities.isNotEmpty()) {
                        _effects.tryEmit(
                            DiagnosticsEffect.ShowToast("Found ${parsedEntities.size} nearby WASM entities — open Entities tab")
                        )
                        "Step 3 (WASM, ${durationMs}ms): Found ${parsedEntities.size} nearby entities (from $rawCount raw hits) in $arrayPath"
                    } else {
                        "Step 3 (WASM, ${durationMs}ms): 0 nearby entity coordinates found within 1.5..250u of player"
                    }
                }
            }

            "WASM_TARGET_SCAN_RESULT" -> {
                val errorMsg = json.optString("error", "")
                if (errorMsg.isNotBlank()) {
                    _uiState.update { state ->
                        state.copy(
                            scanStatus = AsyncScanStatus.ERROR,
                            scanStatusDetail = errorMsg.take(60),
                            targetScanner = state.targetScanner.copy(
                                isScanning = false,
                                statusMessage = errorMsg
                            )
                        )
                    }
                    _effects.tryEmit(DiagnosticsEffect.ShowToast(errorMsg))
                    "Target Finder: $errorMsg"
                } else {
                    val action = json.optString("action", "SCAN")
                    val round = json.optInt("round", 1)
                    val durationMs = json.optDouble("durationMs", 0.0)
                    val total = json.optInt("totalCandidates", 0)
                    val parsed = parseSelectedTargetCandidates(json.optJSONArray("candidates"))
                    val msg = when {
                        action == "CAPTURE" ->
                            "Captured $total candidate slots (${durationMs}ms). Now select a different mob, deselect, or stay on same mob and tap a filter button."
                        total == 0 ->
                            "0 slots survived $action (${durationMs}ms). Tap Reset and recapture while targeting Mob A."
                        total <= 6 ->
                            "Narrowed to $total slot(s) (${durationMs}ms)! Tap 'Watch Live' on the top candidate to verify as you switch mobs."
                        else ->
                            "$total slots left after $action (${durationMs}ms) — switch mobs or deselect and filter again."
                    }
                    _uiState.update { state ->
                        state.copy(
                            scanStatus = AsyncScanStatus.COMPLETED,
                            scanStatusDetail = "Target Finder: $total left (${durationMs}ms)",
                            targetScanner = state.targetScanner.copy(
                                isScanning = false,
                                scanRound = round,
                                totalCandidates = total,
                                candidates = parsed,
                                statusMessage = msg
                            )
                        )
                    }
                    "Target Finder ($action, R$round, ${durationMs}ms): $total candidate(s)"
                }
            }

            "WASM_STRUCT_PROBE_RESULTS" -> {
                val fieldKind = WasmStructFieldKind.fromRaw(json.optString("fieldKind", "HP"))
                val isRefine = json.optBoolean("isRefine", false)
                val errorMsg = json.optString("error", "")
                if (errorMsg.isNotBlank()) {
                    _uiState.update { state ->
                        state.copy(
                            structProber = state.structProber.copy(
                                isProbing = false,
                                statusMessage = errorMsg
                            )
                        )
                    }
                    _effects.tryEmit(DiagnosticsEffect.ShowToast(errorMsg))
                    "Struct Probe (${fieldKind.shortLabel}): $errorMsg"
                } else {
                    val durationMs = json.optDouble("durationMs", 0.0)
                    val totalFound = json.optInt("totalFound", 0)
                    val parsed = parseStructProbeCandidates(json.optJSONArray("candidates"))
                    val msg = when {
                        parsed.isEmpty() && fieldKind.isNameField ->
                            "0 name fields matched (${durationMs}ms). Try Auto-Probe without a filter or scan nearby entities first."
                        parsed.isEmpty() && fieldKind.isAggroField ->
                            "0 aggro flag/pointer offsets matched (${durationMs}ms). Ensure target mob is locked and try Passive -> Aggroed refine."
                        parsed.isEmpty() ->
                            "0 ${fieldKind.shortLabel} matches in struct window (${durationMs}ms). Verify exact stat value."
                        parsed.size == 1 ->
                            "Exact ${fieldKind.shortLabel} candidate isolated: ${parsed.first().expression} (${durationMs}ms) — tap Bind!"
                        fieldKind.isAggroField ->
                            "Found $totalFound Aggro offset(s) in ${durationMs}ms — switch mob between Passive & Aggroed and tap Refine, or Bind PLAYER_PTR."
                        fieldKind.isNameField ->
                            "Found $totalFound name offset(s) in ${durationMs}ms — check mob previews & tap Bind."
                        else ->
                            "Found $totalFound ${fieldKind.shortLabel} offset(s) in ${durationMs}ms — change stat & tap Refine, or Bind best mob match."
                    }
                    _uiState.update { state ->
                        state.copy(
                            structProber = state.structProber.copy(
                                activeField = fieldKind,
                                isProbing = false,
                                probeRound = if (isRefine) state.structProber.probeRound + 1 else 1,
                                candidates = parsed,
                                statusMessage = msg
                            )
                        )
                    }
                    "Struct Probe (${fieldKind.shortLabel}, ${durationMs}ms): $totalFound candidate(s)"
                }
            }

            "MANUAL_EVAL" -> {
                val type = json.optString("resultType", "unknown")
                val ms = json.optDouble("durationMs", 0.0)
                val preview = json.optString("result", "").replace('\n', ' ').take(72)
                _uiState.update {
                    it.copy(
                        scanStatus = AsyncScanStatus.COMPLETED,
                        scanStatusDetail = "JS eval (${ms}ms)"
                    )
                }
                "Result ($type, ${ms}ms): $preview"
            }

            "WASM_HEAP_SCAN_COMPLETE" -> {
                val totalMatches = json.optInt("totalMatches", 0)
                val count32 = json.optInt("heap32MatchCount", 0)
                val countF32 = json.optInt("heapF32MatchCount", 0)
                val durationMs = json.optDouble("durationMs", 0.0)
                val sourceLabel = json.optString("heapSourceLabel", "window.HEAP32 / HEAPF32")
                val byteLength = json.optLong("heapByteLength", 0L)
                val targetVal = json.optDouble("targetValue", 0.0)
                val samples = parseWasmCandidates(json.optJSONArray("samples"))
                val autoObj = json.optJSONObject("autoIsolated")
                val autoType = autoObj?.optString("heapType", "")?.takeIf { it.isNotBlank() }
                val autoIdx = autoObj?.optInt("index", -1)?.takeIf { it >= 0 }
                val autoByteAddr = autoObj?.optLong("byteAddress", (autoIdx ?: 0).toLong() * 4L)
                val autoVal = autoObj?.optString("currentValue", "").orEmpty()

                val statusMsg = when {
                    totalMatches == 0 ->
                        "0 matches for $targetVal in $sourceLabel. Double-check your exact stat value."
                    autoIdx != null && autoType != null ->
                        "Exact match isolated: $autoType[$autoIdx] (${formatHexAddress(autoByteAddr ?: 0L)})! Streaming ±16B neighbors."
                    else ->
                        "Found $totalMatches matches (HEAP32: $count32, HEAPF32: $countF32) in ${durationMs}ms. Take damage/heal, enter new HP, and tap Filter."
                }

                _uiState.update { state ->
                    state.copy(
                        scanStatus = if (totalMatches > 0) AsyncScanStatus.COMPLETED else AsyncScanStatus.IDLE,
                        scanStatusDetail = "WASM Scan: $totalMatches matches (${durationMs}ms)",
                        wasmHeap = state.wasmHeap.copy(
                            isScanning = false,
                            heapSourceLabel = sourceLabel,
                            heapByteLength = byteLength,
                            initialSearchValue = targetVal,
                            totalMatches = totalMatches,
                            heap32MatchCount = count32,
                            heapF32MatchCount = countF32,
                            filterRound = 0,
                            survivingCandidates = samples,
                            isolatedHeapType = autoType,
                            isolatedIndex = autoIdx,
                            isolatedByteAddress = if (autoIdx != null) autoByteAddr else null,
                            isolatedCurrentValue = autoVal,
                            isNeighborPolling = autoIdx != null,
                            statusMessage = statusMsg
                        )
                    )
                }
                "WASM Scan (${durationMs}ms): Found $totalMatches addresses for $targetVal (HEAP32: $count32, HEAPF32: $countF32) in $sourceLabel"
            }

            "WASM_HEAP_DELTA_FILTERED" -> {
                val totalMatches = json.optInt("totalMatches", 0)
                val prevCount = json.optInt("previousCount", 0)
                val count32 = json.optInt("heap32MatchCount", 0)
                val countF32 = json.optInt("heapF32MatchCount", 0)
                val filterRound = json.optInt("filterRound", 1)
                val newVal = json.optDouble("newValue", 0.0)
                val durationMs = json.optDouble("durationMs", 0.0)
                val sourceLabel = json.optString("heapSourceLabel", "")
                val byteLength = json.optLong("heapByteLength", 0L)
                val samples = parseWasmCandidates(json.optJSONArray("samples"))
                val autoObj = json.optJSONObject("autoIsolated")
                val autoType = autoObj?.optString("heapType", "")?.takeIf { it.isNotBlank() }
                val autoIdx = autoObj?.optInt("index", -1)?.takeIf { it >= 0 }
                val autoByteAddr = autoObj?.optLong("byteAddress", (autoIdx ?: 0).toLong() * 4L)
                val autoVal = autoObj?.optString("currentValue", "").orEmpty()

                val statusMsg = when {
                    autoIdx != null && autoType != null ->
                        "Isolated $autoType[$autoIdx] (${formatHexAddress(autoByteAddr ?: 0L)})! Live ±16B neighbor polling started."
                    totalMatches == 0 ->
                        "0 matches left after filtering for $newVal (from $prevCount). Tap Reset and run a fresh Step 1 scan."
                    else ->
                        "Filter #$filterRound narrowed $prevCount → $totalMatches matches. Change HP again or tap a candidate below to inspect ±16B."
                }

                _uiState.update { state ->
                    val mergedStaging = if (autoIdx != null && autoType != null) {
                        mergeIntoStagingBuffer(
                            existing = state.stagingBuffer,
                            incoming = listOf("$autoType[$autoIdx]" to "Isolated WASM stat (${formatHexAddress(autoByteAddr ?: 0L)}) = $autoVal"),
                            sourceStep = "WASM Heap",
                            scanNumber = state.scanCounter.coerceAtLeast(1),
                            timestamp = nowTs,
                            forcePin = true
                        )
                    } else state.stagingBuffer

                    state.copy(
                        stagingBuffer = mergedStaging,
                        scanStatus = if (totalMatches > 0) AsyncScanStatus.COMPLETED else AsyncScanStatus.IDLE,
                        scanStatusDetail = if (autoIdx != null && autoType != null) {
                            "Isolated $autoType[$autoIdx] • Polling ±16B"
                        } else {
                            "WASM Filter #$filterRound: $totalMatches remaining"
                        },
                        wasmHeap = state.wasmHeap.copy(
                            isScanning = false,
                            heapSourceLabel = sourceLabel.ifBlank { state.wasmHeap.heapSourceLabel },
                            heapByteLength = if (byteLength > 0L) byteLength else state.wasmHeap.heapByteLength,
                            lastFilterValue = newVal,
                            totalMatches = totalMatches,
                            heap32MatchCount = count32,
                            heapF32MatchCount = countF32,
                            filterRound = filterRound,
                            survivingCandidates = samples,
                            isolatedHeapType = autoType ?: state.wasmHeap.isolatedHeapType,
                            isolatedIndex = autoIdx ?: state.wasmHeap.isolatedIndex,
                            isolatedByteAddress = if (autoIdx != null) autoByteAddr else state.wasmHeap.isolatedByteAddress,
                            isolatedCurrentValue = if (autoIdx != null) autoVal else state.wasmHeap.isolatedCurrentValue,
                            isNeighborPolling = autoIdx != null || state.wasmHeap.isNeighborPolling,
                            statusMessage = statusMsg
                        )
                    )
                }
                "WASM Filter #$filterRound (${durationMs}ms): $prevCount → $totalMatches matches for $newVal" +
                    if (autoIdx != null && autoType != null) " • Isolated $autoType[$autoIdx] (${formatHexAddress(autoByteAddr ?: 0L)})" else ""
            }

            "WASM_HEAP_ERROR" -> {
                val msg = json.optString("message", "WASM heap operation failed.")
                _uiState.update { state ->
                    state.copy(
                        scanStatus = AsyncScanStatus.ERROR,
                        scanStatusDetail = msg.take(60),
                        wasmHeap = state.wasmHeap.copy(
                            isScanning = false,
                            statusMessage = msg
                        )
                    )
                }
                _effects.tryEmit(DiagnosticsEffect.ShowToast(msg))
                "WASM Tracker: $msg"
            }

            "WASM_FLOAT_BASELINE_CAPTURED" -> {
                val sampledCount = json.optInt("sampledCount", 0)
                val prunedJitter = json.optInt("prunedJitterCount", 0)
                val durationMs = json.optDouble("durationMs", 0.0)
                val sourceLabel = json.optString("heapSourceLabel", "window.HEAPF32")
                val byteLen = json.optLong("heapByteLength", 0L)

                val msg = "Captured $sampledCount stable floats in ${durationMs}ms (pruned $prunedJitter ticking timers). Walk 5 steps forward, then tap 'Scan Float Heap Deltas'."
                _uiState.update { state ->
                    state.copy(
                        scanStatus = AsyncScanStatus.COMPLETED,
                        scanStatusDetail = "Float Baseline: $sampledCount stable floats (${durationMs}ms)",
                        wasmFloatDelta = state.wasmFloatDelta.copy(
                            isSamplingBaseline = false,
                            baselineCaptured = sampledCount > 0,
                            baselineSampleCount = sampledCount,
                            baselinePrunedJitterCount = prunedJitter,
                            heapSourceLabel = sourceLabel,
                            heapByteLength = byteLen,
                            statusMessage = msg
                        )
                    )
                }
                "Step 2A (Float Mode, ${durationMs}ms): Sampled $sampledCount stable floats in $sourceLabel (pruned $prunedJitter jitter slots)"
            }

            "WASM_FLOAT_DELTA_SCANNED" -> {
                val totalMatches = json.optInt("totalMatches", 0)
                val scanRound = json.optInt("scanRound", 1)
                val durationMs = json.optDouble("durationMs", 0.0)
                val sourceLabel = json.optString("heapSourceLabel", "window.HEAPF32")
                val byteLen = json.optLong("heapByteLength", 0L)
                val parsedCandidates = parseWasmFloatDeltaCandidates(
                    array = json.optJSONArray("candidates"),
                    pinnedIndices = _uiState.value.wasmFloatDelta.pinnedIndices
                )

                val stagingPairs = parsedCandidates.take(6).map { c ->
                    val axisTag = if (c.axisHint.isNotBlank()) "[${c.axisHint}] " else ""
                    val hex = formatHexAddress(c.byteAddress)
                    "window.HEAPF32[${c.heapIndex}]" to "${axisTag}$hex • cur=${String.format(Locale.US, "%.3f", c.currentValue)} (Δ ${String.format(Locale.US, "%+.3f", c.delta)})"
                }

                val statusMsg = if (totalMatches > 0) {
                    "Round #$scanRound: Found $totalMatches moving floats (${durationMs}ms). Streaming top ${parsedCandidates.size} live below — walk again & re-tap to refine!"
                } else {
                    "Round #$scanRound: 0 floats moved in positional range (0.05..250). Walk further or recapture Baseline."
                }

                _uiState.update { state ->
                    val nextScan = state.scanCounter + 1
                    val mergedStaging = if (stagingPairs.isNotEmpty()) {
                        mergeIntoStagingBuffer(
                            existing = state.stagingBuffer,
                            incoming = stagingPairs,
                            sourceStep = "Float Delta #$scanRound",
                            scanNumber = nextScan,
                            timestamp = nowTs
                        )
                    } else state.stagingBuffer

                    state.copy(
                        scanCounter = nextScan,
                        stagingBuffer = mergedStaging,
                        scanStatus = if (totalMatches > 0) AsyncScanStatus.COMPLETED else AsyncScanStatus.IDLE,
                        scanStatusDetail = if (totalMatches > 0) {
                            "Float Deltas #$scanRound: $totalMatches matches • Live 200ms"
                        } else {
                            "Float Deltas #$scanRound: 0 matches"
                        },
                        wasmFloatDelta = state.wasmFloatDelta.copy(
                            isScanningDeltas = false,
                            heapSourceLabel = sourceLabel.ifBlank { state.wasmFloatDelta.heapSourceLabel },
                            heapByteLength = if (byteLen > 0L) byteLen else state.wasmFloatDelta.heapByteLength,
                            totalDeltaMatches = totalMatches,
                            scanRound = scanRound,
                            isLiveWatching = parsedCandidates.isNotEmpty(),
                            candidates = parsedCandidates,
                            isWatcherExpanded = true,
                            statusMessage = statusMsg
                        )
                    )
                }
                "Step 2B (Float Mode #$scanRound, ${durationMs}ms): Isolated $totalMatches moving floats in $sourceLabel • Watching top ${parsedCandidates.size}"
            }

            "WASM_FLOAT_ERROR" -> {
                val msg = json.optString("message", "WASM Float Heap scan failed.")
                _uiState.update { state ->
                    state.copy(
                        scanStatus = AsyncScanStatus.ERROR,
                        scanStatusDetail = msg.take(60),
                        wasmFloatDelta = state.wasmFloatDelta.copy(
                            isSamplingBaseline = false,
                            isScanningDeltas = false,
                            isScanningPointers = false,
                            statusMessage = msg
                        )
                    )
                }
                _effects.tryEmit(DiagnosticsEffect.ShowToast(msg))
                "WASM Float Delta: $msg"
            }

            "WASM_POINTER_SCAN_COMPLETE" -> {
                val totalL1 = json.optInt("totalL1", 0)
                val totalL2 = json.optInt("totalL2", 0)
                val durationMs = json.optDouble("durationMs", 0.0)
                val targetAddr = json.optLong("targetByteAddress", 0L)
                val pinnedExisting = _uiState.value.wasmFloatDelta.pointerChains.filter { it.isPinned || it.isSavedLocally }
                val pinnedIds = pinnedExisting.map { it.id }.toSet()
                val savedIds = pinnedExisting.filter { it.isSavedLocally }.map { it.id }.toSet()
                val parsedChains = parseWasmPointerChains(json.optJSONArray("chains"), pinnedIds, savedIds)

                // Merge newly discovered chains while preserving any previously pinned/saved chains
                val mergedChains = buildList {
                    val seen = LinkedHashSet<String>()
                    parsedChains.forEach { c ->
                        if (seen.add(c.id)) add(c)
                    }
                    pinnedExisting.forEach { c ->
                        if (seen.add(c.id)) add(c)
                    }
                }

                val staticCount = parsedChains.count { it.isLikelyStatic }
                val pointerMsg = if (parsedChains.isNotEmpty()) {
                    "Found L1=$totalL1, L2=$totalL2 pointers ($staticCount static) in ${durationMs}ms. Live 200ms Watch started — walk to confirm MOVING ✓!"
                } else {
                    "0 direct L1/L2 pointers found within 256B of ${formatHexAddress(targetAddr)} (${durationMs}ms). Try locking an adjacent Vec3 slot or checking ±16B neighbors."
                }

                val stagingPairs = parsedChains.filter { it.isLikelyStatic }.ifEmpty { parsedChains }.take(3).map { c ->
                    val tag = if (c.isLikelyStatic) "STATIC" else "HEAP"
                    "WASM_PTR: ${c.expression}" to "[L${c.depth} $tag] -> ${formatHexAddress(c.resolvedTargetAddress)}"
                }

                _uiState.update { state ->
                    val nextScan = state.scanCounter + 1
                    val mergedStaging = if (stagingPairs.isNotEmpty()) {
                        mergeIntoStagingBuffer(
                            existing = state.stagingBuffer,
                            incoming = stagingPairs,
                            sourceStep = "Pointer Scan",
                            scanNumber = nextScan,
                            timestamp = nowTs
                        )
                    } else state.stagingBuffer

                    state.copy(
                        scanCounter = nextScan,
                        stagingBuffer = mergedStaging,
                        scanStatus = if (parsedChains.isNotEmpty()) AsyncScanStatus.COMPLETED else AsyncScanStatus.IDLE,
                        scanStatusDetail = "Pointers: L1=$totalL1, L2=$totalL2 (${durationMs}ms)",
                        wasmFloatDelta = state.wasmFloatDelta.copy(
                            isScanningPointers = false,
                            pointerScanTotalL1 = totalL1,
                            pointerScanTotalL2 = totalL2,
                            pointerChains = mergedChains,
                            isWatchingPointers = mergedChains.isNotEmpty(),
                            pointerStatusMessage = pointerMsg
                        )
                    )
                }
                if (mergedChains.isNotEmpty()) {
                    val payloadJson = buildPointerChainsPayloadJson(mergedChains)
                    _effects.tryEmit(
                        DiagnosticsEffect.EvaluateJs(
                            WasmPointerAndEntityScripts.buildStartPointerWatchScript(
                                chainsJsonArray = payloadJson,
                                hudPointerId = _uiState.value.wasmFloatDelta.activeHudPointerId
                            )
                        )
                    )
                }
                "Pointer Scan (${durationMs}ms): L1=$totalL1, L2=$totalL2 chains to ${formatHexAddress(targetAddr)} ($staticCount static)"
            }

            "WASM_POINTER_CHAINS_VERIFIED" -> {
                val validCount = json.optInt("validCount", 0)
                val zeroCount = json.optInt("zeroCount", 0)
                val totalCount = json.optInt("totalCount", 0)
                val resultsArr = json.optJSONArray("results") ?: JSONArray()
                val byId = HashMap<String, JSONObject>(resultsArr.length())
                for (i in 0 until resultsArr.length()) {
                    val obj = resultsArr.optJSONObject(i) ?: continue
                    val id = obj.optString("id", "")
                    if (id.isNotBlank()) byId[id] = obj
                }

                val verifyMsg = "Verified $validCount/$totalCount live" +
                    (if (zeroCount > 0) " ($zeroCount zeroed)" else "") +
                    " — streaming 200ms Watch (walk to confirm MOVING ✓)."
                var chainsToWatch: List<WasmPointerChain> = emptyList()
                var hudId: String? = null
                _uiState.update { state ->
                    val updatedChains = state.wasmFloatDelta.pointerChains.map { chain ->
                        val res = byId[chain.id]
                        if (res != null) {
                            val ok = res.optBoolean("isVerifiedLive", false)
                            val st = WasmPointerStatus.fromRaw(res.optString("status", ""), ok)
                            chain.copy(
                                resolvedTargetAddress = res.optLong("resolvedTargetAddress", if (ok) chain.resolvedTargetAddress else 0L),
                                resolvedX = res.optDouble("resolvedX", 0.0),
                                resolvedY = res.optDouble("resolvedY", 0.0),
                                resolvedZ = res.optDouble("resolvedZ", 0.0),
                                isVerifiedLive = ok,
                                status = st
                            )
                        } else {
                            chain
                        }
                    }
                    chainsToWatch = updatedChains
                    hudId = state.wasmFloatDelta.activeHudPointerId
                        ?: updatedChains.firstOrNull { it.status == WasmPointerStatus.LIVE || it.status == WasmPointerStatus.MOVING }?.id
                    val finalChains = updatedChains.map { it.copy(isStreamingToHud = (it.id == hudId)) }
                    state.copy(
                        scanStatus = AsyncScanStatus.COMPLETED,
                        scanStatusDetail = "Verified $validCount/$totalCount live ($zeroCount zeroed)",
                        wasmFloatDelta = state.wasmFloatDelta.copy(
                            pointerChains = finalChains,
                            activeHudPointerId = hudId,
                            isWatchingPointers = finalChains.isNotEmpty(),
                            isAutoVerifyingOnReload = false,
                            pointerStatusMessage = verifyMsg
                        )
                    )
                }
                if (chainsToWatch.isNotEmpty()) {
                    _effects.tryEmit(
                        DiagnosticsEffect.EvaluateJs(
                            WasmPointerAndEntityScripts.buildStartPointerWatchScript(
                                chainsJsonArray = buildPointerChainsPayloadJson(chainsToWatch),
                                hudPointerId = hudId
                            )
                        )
                    )
                }
                _effects.tryEmit(DiagnosticsEffect.ShowToast(verifyMsg))
                "Pointer Verify: $validCount/$totalCount live ($zeroCount zeroed) • Live 200ms Watch started"
            }

            "WASM_POINTER_AUTO_VERIFY_DONE" -> {
                val heapFound = json.optBoolean("heapFound", false)
                val nonZeroCount = json.optInt("nonZeroCount", 0)
                val zeroCount = json.optInt("zeroCount", 0)
                val totalCount = json.optInt("totalCount", 0)
                val autoHudId = json.optString("autoHudPointerId", "").takeIf { it.isNotBlank() && it != "null" }
                val sourceLabel = json.optString("heapSourceLabel", "")
                val byteLen = json.optLong("heapByteLength", 0L)
                val resultsArr = json.optJSONArray("results") ?: JSONArray()
                val byId = HashMap<String, JSONObject>(resultsArr.length())
                for (i in 0 until resultsArr.length()) {
                    val obj = resultsArr.optJSONObject(i) ?: continue
                    val id = obj.optString("id", "")
                    if (id.isNotBlank()) byId[id] = obj
                }

                val msg = if (!heapFound) {
                    "WASM heap not detected yet after reload. Tap 'Verify' once in-game."
                } else {
                    "Auto-verified $nonZeroCount/$totalCount saved pointer(s) live" +
                        (if (zeroCount > 0) " ($zeroCount zeroed)" else "") +
                        " • 200ms Watch active!"
                }

                _uiState.update { state ->
                    val updatedChains = state.wasmFloatDelta.pointerChains.map { chain ->
                        val res = byId[chain.id]
                        if (res != null) {
                            val ok = res.optBoolean("isVerifiedLive", false)
                            val st = WasmPointerStatus.fromRaw(res.optString("status", ""), ok)
                            chain.copy(
                                resolvedTargetAddress = res.optLong("resolvedTargetAddress", 0L),
                                resolvedX = res.optDouble("resolvedX", 0.0),
                                resolvedY = res.optDouble("resolvedY", 0.0),
                                resolvedZ = res.optDouble("resolvedZ", 0.0),
                                isVerifiedLive = ok,
                                status = st,
                                isStreamingToHud = (chain.id == autoHudId)
                            )
                        } else chain
                    }
                    val activeHudChain = autoHudId?.let { id -> updatedChains.firstOrNull { it.id == id && it.isVerifiedLive } }
                    state.copy(
                        discoveredPlayerPath = activeHudChain?.let { "WASM_PTR: ${it.expression}" } ?: state.discoveredPlayerPath,
                        livePlayerCoords = activeHudChain?.let {
                            PlayerCoordinates(
                                path = "WASM_PTR: ${it.expression}",
                                x = it.resolvedX,
                                y = it.resolvedY,
                                z = it.resolvedZ
                            )
                        } ?: state.livePlayerCoords,
                        livePollActive = activeHudChain != null || state.livePollActive,
                        scanStatus = if (nonZeroCount > 0) AsyncScanStatus.COMPLETED else AsyncScanStatus.IDLE,
                        scanStatusDetail = if (nonZeroCount > 0) "Auto-verified $nonZeroCount/$totalCount pointers" else "Waiting for game load",
                        wasmFloatDelta = state.wasmFloatDelta.copy(
                            heapSourceLabel = sourceLabel.ifBlank { state.wasmFloatDelta.heapSourceLabel },
                            heapByteLength = if (byteLen > 0L) byteLen else state.wasmFloatDelta.heapByteLength,
                            pointerChains = updatedChains,
                            activeHudPointerId = autoHudId,
                            isWatchingPointers = heapFound && updatedChains.isNotEmpty(),
                            isAutoVerifyingOnReload = false,
                            pointerStatusMessage = msg
                        )
                    )
                }
                if (nonZeroCount > 0) {
                    _effects.tryEmit(DiagnosticsEffect.ShowToast(msg))
                }
                msg
            }

            "WASM_POINTER_ERROR" -> {
                val msg = json.optString("message", "WASM pointer scan failed.")
                _uiState.update { state ->
                    state.copy(
                        scanStatus = AsyncScanStatus.ERROR,
                        scanStatusDetail = msg.take(60),
                        wasmFloatDelta = state.wasmFloatDelta.copy(
                            isScanningPointers = false,
                            isAutoVerifyingOnReload = false,
                            pointerStatusMessage = msg
                        )
                    )
                }
                _effects.tryEmit(DiagnosticsEffect.ShowToast(msg))
                "Pointer Scan Error: $msg"
            }

            else -> payload.take(110)
        }
    }

    private fun parseWasmEntitiesArray(array: JSONArray?): List<DiscoveredEntitySample> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                fun optNullableInt(key: String): Int? =
                    if (obj.has(key) && !obj.isNull(key)) obj.optInt(key) else null
                val resolvedName = if (obj.has("resolvedName") && !obj.isNull("resolvedName")) {
                    obj.optString("resolvedName", "").takeIf { it.isNotBlank() }
                } else null
                val isAggro = if (obj.has("isAggro") && !obj.isNull("isAggro")) obj.optBoolean("isAggro") else null
                add(
                    DiscoveredEntitySample(
                        name = obj.optString("name", "WASM Entity #${i + 1}"),
                        id = obj.optString("id", ""),
                        path = obj.optString("path", ""),
                        worldX = obj.optDouble("worldX", 0.0),
                        worldY = obj.optDouble("worldY", 0.0),
                        worldZ = obj.optDouble("worldZ", 0.0),
                        distanceToPlayer = obj.optDouble("distanceToPlayer", 0.0),
                        screenX = obj.optDouble("screenX", 0.0),
                        screenY = obj.optDouble("screenY", 0.0),
                        inViewport = obj.optBoolean("inViewport", false),
                        sampleKeys = extractStringArray(obj.optJSONArray("sampleKeys")),
                        coordByteAddress = obj.optLong("coordByteAddress", 0L),
                        heapIndex = obj.optInt("heapIndex", -1),
                        resolvedName = resolvedName,
                        hp = optNullableInt("hp"),
                        maxHp = optNullableInt("maxHp"),
                        mp = optNullableInt("mp"),
                        maxMp = optNullableInt("maxMp"),
                        fp = optNullableInt("fp"),
                        maxFp = optNullableInt("maxFp"),
                        aggroRawValue = optNullableInt("aggroRawValue"),
                        isAggro = isAggro,
                        isActiveGameTarget = obj.optBoolean("isActiveGameTarget", false)
                    )
                )
            }
        }
    }

    private fun parseSelectedTargetCandidates(array: JSONArray?): List<SelectedTargetCandidate> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val slotAddr = obj.optLong("slotByteAddress", 0L)
                if (slotAddr <= 0L) continue
                fun optNullableInt(key: String): Int? =
                    if (obj.has(key) && !obj.isNull(key)) obj.optInt(key) else null
                fun optNullableDouble(key: String): Double? =
                    if (obj.has(key) && !obj.isNull(key)) obj.optDouble(key).takeIf { it.isFinite() } else null
                fun optNullableString(key: String): String? =
                    if (obj.has(key) && !obj.isNull(key)) obj.optString(key, "").takeIf { it.isNotBlank() } else null

                add(
                    SelectedTargetCandidate(
                        slotByteAddress = slotAddr,
                        slotHex = obj.optString("slotHex", formatHexAddress(slotAddr)),
                        currentValue = obj.optLong("currentValue", 0L),
                        currentHex = obj.optString("currentHex", formatHexAddress(obj.optLong("currentValue", 0L))),
                        isPointerLike = obj.optBoolean("isPointerLike", false),
                        nearPlayerOffsetBytes = optNullableInt("nearPlayerOffsetBytes"),
                        matchedEntityId = optNullableString("matchedEntityId"),
                        matchedEntityName = optNullableString("matchedEntityName"),
                        resolvedCoordAddress = obj.optLong("resolvedCoordAddress", 0L),
                        resolvedX = optNullableDouble("resolvedX"),
                        resolvedY = optNullableDouble("resolvedY"),
                        resolvedZ = optNullableDouble("resolvedZ"),
                        previewName = optNullableString("previewName"),
                        previewHp = optNullableInt("previewHp"),
                        previewMaxHp = optNullableInt("previewMaxHp"),
                        previewAggroRaw = optNullableInt("previewAggroRaw")
                    )
                )
            }
        }
    }

    private fun parseStructProbeCandidates(array: JSONArray?): List<WasmStructProbeCandidate> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("id", "").trim()
                if (id.isEmpty()) continue
                val sub = if (obj.has("subOffsetBytes") && !obj.isNull("subOffsetBytes")) {
                    obj.optInt("subOffsetBytes")
                } else null
                val numVal = if (obj.has("playerNumericValue") && !obj.isNull("playerNumericValue")) {
                    obj.optDouble("playerNumericValue")
                } else null
                val adjMaxOff = if (obj.has("adjacentMaxOffsetBytes") && !obj.isNull("adjacentMaxOffsetBytes")) {
                    obj.optInt("adjacentMaxOffsetBytes")
                } else null
                val adjMaxVal = if (obj.has("adjacentMaxValue") && !obj.isNull("adjacentMaxValue")) {
                    obj.optInt("adjacentMaxValue")
                } else null
                add(
                    WasmStructProbeCandidate(
                        id = id,
                        relOffsetBytes = obj.optInt("relOffsetBytes", 0),
                        subOffsetBytes = sub,
                        valueType = WasmStructValueType.fromRaw(obj.optString("valueType", "INT32")),
                        expression = obj.optString("expression", ""),
                        playerDisplayValue = obj.optString("playerDisplayValue", ""),
                        playerNumericValue = numVal,
                        adjacentMaxOffsetBytes = adjMaxOff,
                        adjacentMaxValue = adjMaxVal,
                        mobPreviews = extractStringArray(obj.optJSONArray("mobPreviews")),
                        nonZeroMobCount = obj.optInt("nonZeroMobCount", 0)
                    )
                )
            }
        }
    }

    private fun parseWasmPointerChains(
        array: JSONArray?,
        pinnedIds: Set<String>,
        savedIds: Set<String> = emptySet()
    ): List<WasmPointerChain> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("id", "").trim()
                if (id.isEmpty()) continue
                val depth = obj.optInt("depth", 1)
                val baseAddr = obj.optLong("basePointerAddress", 0L)
                val offArr = obj.optJSONArray("offsets")
                val offsets = buildList {
                    if (offArr != null) {
                        for (j in 0 until offArr.length()) {
                            add(offArr.optInt(j, 0))
                        }
                    }
                }
                val resolvedTarget = obj.optLong("resolvedTargetAddress", 0L)
                val rx = obj.optDouble("resolvedX", 0.0)
                val ry = obj.optDouble("resolvedY", 0.0)
                val rz = obj.optDouble("resolvedZ", 0.0)
                val isStatic = obj.optBoolean("isLikelyStatic", false)
                val isZero = kotlin.math.abs(rx) < 0.00001 && kotlin.math.abs(ry) < 0.00001 && kotlin.math.abs(rz) < 0.00001
                val isVerified = obj.optBoolean("isVerifiedLive", true) && !isZero
                val status = when {
                    isZero -> WasmPointerStatus.ZERO
                    isVerified -> WasmPointerStatus.LIVE
                    else -> WasmPointerStatus.STALE
                }
                val expr = obj.optString("expression", "")
                add(
                    WasmPointerChain(
                        id = id,
                        depth = depth,
                        basePointerAddress = baseAddr,
                        offsets = offsets,
                        resolvedTargetAddress = resolvedTarget,
                        resolvedX = rx,
                        resolvedY = ry,
                        resolvedZ = rz,
                        isLikelyStatic = isStatic,
                        isVerifiedLive = isVerified,
                        expression = expr,
                        isPinned = pinnedIds.contains(id),
                        status = status,
                        isSavedLocally = savedIds.contains(id)
                    )
                )
            }
        }
    }

    private fun parseWasmFloatDeltaCandidates(
        array: JSONArray?,
        pinnedIndices: Set<Int>
    ): List<WasmFloatDeltaCandidate> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val idx = obj.optInt("heapIndex", -1)
                if (idx < 0) continue
                val byteAddr = obj.optLong("byteAddress", idx.toLong() * 4L)
                val baseVal = obj.optDouble("baselineValue", 0.0)
                val curVal = obj.optDouble("currentValue", 0.0)
                val deltaVal = obj.optDouble("delta", curVal - baseVal)
                val cluster = obj.optInt("clusterSize", 1)
                val axis = obj.optString("axisHint", "")
                add(
                    WasmFloatDeltaCandidate(
                        heapIndex = idx,
                        byteAddress = byteAddr,
                        baselineValue = baseVal,
                        currentValue = curVal,
                        delta = deltaVal,
                        clusterSize = cluster,
                        axisHint = axis,
                        isPinned = pinnedIndices.contains(idx)
                    )
                )
            }
        }
    }

    private fun parseWasmCandidates(array: JSONArray?): List<WasmCandidateAddress> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val heapType = obj.optString("heapType", "HEAP32")
                val index = obj.optInt("index", -1)
                if (index < 0) continue
                val byteAddr = obj.optLong("byteAddress", index.toLong() * 4L)
                val curVal = obj.optString("currentValue", "")
                add(
                    WasmCandidateAddress(
                        heapType = heapType,
                        index = index,
                        byteAddress = byteAddr,
                        currentValue = curVal
                    )
                )
            }
        }
    }

    private fun extractStringArray(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optString(i, "").trim()
                if (item.isNotEmpty()) add(item)
            }
        }
    }

    private fun appendLog(category: LogCategory, summary: String, payload: String) {
        val now = System.currentTimeMillis()
        val entry = TelemetryLogEntry(
            id = logIdGenerator.getAndIncrement(),
            timestamp = formatTimestamp(now),
            epochMs = now,
            category = category,
            summary = summary,
            payload = payload
        )
        _uiState.update { state ->
            val updated = (state.logs + entry).takeLast(MAX_LOG_ENTRIES)
            state.copy(logs = updated)
        }
    }

    private fun buildFullReportJson(
        state: DiagnosticsUiState,
        viewportMetricsJson: String
    ): String {
        val root = JSONObject()
        root.put("application", "WebViewDiagnosticsApp")
        root.put("generatedAtIso", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date()))
        root.put("activeUrl", state.activeUrl)
        root.put("pageTitle", state.pageTitle)
        root.put("desktopUserAgentEnabled", state.isDesktopMode)
        root.put("remoteWebContentsDebuggingEnabled", false)
        root.put("totalScansExecuted", state.scanCounter)

        val bridgeObj = try {
            JSONObject(viewportMetricsJson)
        } catch (_: Exception) {
            JSONObject().put("raw", viewportMetricsJson)
        }

        // 1. Viewport dimensions
        val viewportObj = bridgeObj.optJSONObject("viewport") ?: JSONObject()
        root.put(
            "viewport",
            JSONObject()
                .put("width", viewportObj.optInt("width", viewportObj.optInt("innerWidth", 0)))
                .put("height", viewportObj.optInt("height", viewportObj.optInt("innerHeight", 0)))
                .put("devicePixelRatio", viewportObj.optDouble("devicePixelRatio", 1.0))
                .put("canvases", bridgeObj.optJSONArray("canvases") ?: JSONArray())
        )

        // 2. Pinned Candidate Staging List & Full Accumulated Buffer
        val pinnedArr = JSONArray()
        val fullStagingArr = JSONArray()
        state.stagingBuffer.forEach { item ->
            val itemJson = JSONObject()
                .put("path", item.path)
                .put("sourceStep", item.sourceStep)
                .put("detail", item.detail)
                .put("scanNumber", item.scanNumber)
                .put("seenCount", item.seenCount)
                .put("isPinned", item.isPinned)
                .put("lastSeenTimestamp", item.lastSeenTimestamp)
            fullStagingArr.put(itemJson)
            if (item.isPinned) pinnedArr.put(itemJson)
        }
        root.put("pinnedCandidates", pinnedArr)
        root.put("candidateStagingBuffer", fullStagingArr)

        // 3. Confirmed Live Player Coordinate Reference Path + Live Sample
        val confirmedPlayerPath = state.discoveredPlayerPath
            ?: bridgeObj.optString("discoveredPlayerPath", "").takeIf { it.isNotBlank() && it != "null" }
        val liveSampleJson = JSONObject().apply {
            val live = state.livePlayerCoords
            val bridgeLive = bridgeObj.optJSONObject("livePlayerSample")
            if (bridgeLive != null) {
                put("x", bridgeLive.optDouble("x", live?.x ?: 0.0))
                put("y", bridgeLive.optDouble("y", live?.y ?: 0.0))
                put("z", bridgeLive.optDouble("z", live?.z ?: 0.0))
            } else if (live != null) {
                put("x", live.x)
                put("y", live.y)
                put("z", live.z)
            }
        }
        val deltasArr = JSONArray()
        state.isolatedPaths.forEach { item ->
            deltasArr.put(
                JSONObject()
                    .put("path", item.path)
                    .put("baseline", item.baselineValue)
                    .put("current", item.currentValue)
                    .put("delta", item.delta)
            )
        }
        root.put(
            "playerCoordinates",
            JSONObject()
                .put("confirmedJsPath", confirmedPlayerPath ?: JSONObject.NULL)
                .put("propertyKeys", bridgeObj.optJSONObject("liveRefPropertyKeys") ?: state.playerPropertyKeys)
                .put("livePollingIntervalMs", 200)
                .put("liveSample", if (liveSampleJson.length() > 0) liveSampleJson else JSONObject.NULL)
                .put("isolatedDeltas", deltasArr)
        )

        // 4. Confirmed Entity Array + Screen Projections
        val confirmedEntityPath = state.discoveredEntityArrayPath
            ?: bridgeObj.optString("discoveredEntityArrayPath", "").takeIf { it.isNotBlank() && it != "null" }
        val entitiesArr = JSONArray()
        state.discoveredEntities.forEach { ent ->
            val keysJson = JSONArray()
            ent.sampleKeys.forEach { keysJson.put(it) }
            entitiesArr.put(
                JSONObject()
                    .put("name", ent.name)
                    .put("id", ent.id)
                    .put("path", ent.path)
                    .put(
                        "worldCoords",
                        JSONObject()
                            .put("x", ent.worldX)
                            .put("y", ent.worldY)
                            .put("z", ent.worldZ)
                    )
                    .put("distanceToPlayer", ent.distanceToPlayer)
                    .put(
                        "projectedScreenCoords",
                        JSONObject()
                            .put("screenX", ent.screenX)
                            .put("screenY", ent.screenY)
                            .put("inViewport", ent.inViewport)
                    )
                    .put("sampleKeys", keysJson)
            )
        }
        root.put(
            "entityCollection",
            JSONObject()
                .put("confirmedJsPath", confirmedEntityPath ?: JSONObject.NULL)
                .put("projectionMode", state.entityProjectionMode)
                .put("totalDetectedEntities", state.discoveredEntities.size)
                .put("entities", entitiesArr)
        )

        // 5. Intercepted asset catalog with local ByteBuffer metadata
        val assetsArray = JSONArray()
        state.interceptedAssets.forEach { asset ->
            assetsArray.put(
                JSONObject()
                    .put("url", asset.url)
                    .put("fileName", asset.fileName)
                    .put("extension", asset.extension)
                    .put("method", asset.method)
                    .put("timestamp", asset.timestamp)
                    .put("isForMainFrame", asset.isForMainFrame)
                    .put("captureStatus", asset.captureStatus.name)
                    .put("localRelativePath", asset.localRelativePath ?: JSONObject.NULL)
                    .put("httpStatus", asset.httpStatus ?: JSONObject.NULL)
                    .put("contentType", asset.contentType)
                    .put("byteSize", asset.byteSize)
                    .put("isTruncated", asset.isTruncated)
                    .put("sha256Prefix", asset.sha256Prefix)
                    .put("magicSignature", asset.magicSignature)
                    .put("entropyBits", asset.entropyBits)
                    .put("hexHeaderPreview", asset.hexHeaderPreview)
                    .put("structuralSummary", asset.structuralSummary)
                    .put("structuralDetails", asset.structuralDetails)
            )
        }
        root.put("interceptedAssetUrls", assetsArray)
        root.put(
            "interceptedAssetCatalog",
            JSONObject()
                .put("totalObserved", state.interceptedAssets.size)
                .put("totalCachedLocally", state.cachedAssetCount)
                .put("totalCachedBytes", state.totalCachedAssetBytes)
                .put("assets", assetsArray)
        )

        // 6. WASM Heap Value-Tracker & Float Delta Memory Map (if active/isolated)
        if (state.wasmHeap.isolatedIndex != null ||
            state.wasmHeap.totalMatches > 0 ||
            state.wasmFloatDelta.candidates.isNotEmpty() ||
            state.wasmFloatDelta.lockedCoords != null ||
            state.wasmFloatDelta.pointerChains.isNotEmpty()
        ) {
            root.put("wasmHeapMemoryMap", JSONObject(buildWasmMemoryMapJson(state)))
        }

        // 7. Session logs
        val logsArray = JSONArray()
        state.logs.forEach { entry ->
            logsArray.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("timestamp", entry.timestamp)
                    .put("epochMs", entry.epochMs)
                    .put("category", entry.category.tag)
                    .put("summary", entry.summary)
                    .put("payload", entry.payload)
            )
        }
        root.put("sessionLogs", logsArray)

        return root.toString(2)
    }

    private fun prettyFormatIfJson(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return raw
        return try {
            if (trimmed.startsWith("{")) JSONObject(trimmed).toString(2)
            else JSONArray(trimmed).toString(2)
        } catch (_: Exception) {
            raw
        }
    }

    private fun formatTimestamp(epochMs: Long): String {
        return SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(epochMs))
    }

    companion object {
        private const val MAX_LOG_ENTRIES = 500
        private const val MAX_TRACKED_ASSETS = 500
        private const val MAX_STAGING_ITEMS = 250

        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

        private val TRACKED_EXTENSIONS = listOf(
            ".wasm", ".json", ".bin", ".glb", ".gltf", ".data", ".pck", ".pak", ".ktx2", ".dds"
        )

        fun mergeIntoStagingBuffer(
            existing: List<CandidateStagingItem>,
            incoming: List<Pair<String, String>>,
            sourceStep: String,
            scanNumber: Int,
            timestamp: String,
            forcePin: Boolean = false
        ): List<CandidateStagingItem> {
            val map = LinkedHashMap<String, CandidateStagingItem>(existing.size + incoming.size)
            existing.forEach { map[it.path] = it }

            incoming.forEach { (rawPath, detail) ->
                val path = rawPath.trim()
                if (path.isEmpty()) return@forEach
                val prev = map[path]
                if (prev != null) {
                    map[path] = prev.copy(
                        sourceStep = sourceStep,
                        detail = detail.ifBlank { prev.detail },
                        scanNumber = scanNumber,
                        seenCount = prev.seenCount + 1,
                        isPinned = prev.isPinned || forcePin,
                        lastSeenTimestamp = timestamp
                    )
                } else {
                    map[path] = CandidateStagingItem(
                        path = path,
                        sourceStep = sourceStep,
                        detail = detail,
                        scanNumber = scanNumber,
                        seenCount = 1,
                        isPinned = forcePin,
                        lastSeenTimestamp = timestamp
                    )
                }
            }
            return sortStagingBuffer(map.values.toList()).take(MAX_STAGING_ITEMS)
        }

        fun filterStagingBuffer(
            items: List<CandidateStagingItem>,
            showPinnedOnly: Boolean,
            rawQuery: String
        ): List<CandidateStagingItem> {
            val base = if (showPinnedOnly) items.filter { it.isPinned } else items
            val tokens = rawQuery.trim()
                .lowercase(Locale.US)
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
            if (tokens.isEmpty()) return base
            return base.filter { item ->
                val haystack = "${item.path} ${item.detail} ${item.sourceStep}".lowercase(Locale.US)
                tokens.all { token -> haystack.contains(token) }
            }
        }

        fun buildFullLogDumpText(state: DiagnosticsUiState): String {
            val isoNow = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())
            return buildString {
                appendLine("========================================================================")
                appendLine("WebViewDiagnosticsApp — Complete Diagnostic Log Dump")
                appendLine("========================================================================")
                appendLine("Generated At : $isoNow")
                appendLine("Active URL   : ${state.activeUrl}")
                appendLine("Page Title   : ${state.pageTitle.ifBlank { "(untitled)" }}")
                appendLine("User-Agent   : ${if (state.isDesktopMode) "Desktop" else "Mobile"}")
                appendLine("Remote Debug : false (disabled)")
                appendLine("Scans Run    : ${state.scanCounter}")
                appendLine("Canvases     : ${state.canvasCount}")
                if (state.entityViewportWidth > 0 && state.entityViewportHeight > 0) {
                    appendLine("Viewport     : ${state.entityViewportWidth}x${state.entityViewportHeight}")
                }
                appendLine()

                appendLine("--- [1] LIVE PLAYER COORDINATE LOCK ---")
                appendLine("Confirmed Path : ${state.discoveredPlayerPath ?: "(not isolated)"}")
                appendLine("Property Keys  : ${state.playerPropertyKeys.ifBlank { "(none)" }}")
                state.livePlayerCoords?.let { coords ->
                    appendLine(
                        "Live Coords    : X=${String.format(Locale.US, "%.4f", coords.x)}, " +
                            "Y=${String.format(Locale.US, "%.4f", coords.y)}, " +
                            "Z=${String.format(Locale.US, "%.4f", coords.z)} (ticks=${state.livePollTickCount})"
                    )
                }
                if (state.isolatedPaths.isNotEmpty()) {
                    appendLine("Isolated Deltas (${state.isolatedPaths.size}):")
                    state.isolatedPaths.forEach { d ->
                        appendLine("  • ${d.path} | base=${d.baselineValue} -> cur=${d.currentValue} (Δ ${d.delta})")
                    }
                }
                appendLine()

                appendLine("--- [2] STAGED VARIABLES (${state.stagingBuffer.size} total, ${state.pinnedCandidateCount} pinned) ---")
                if (state.stagingBuffer.isEmpty()) {
                    appendLine("  (none)")
                } else {
                    state.stagingBuffer.forEach { item ->
                        val pinTag = if (item.isPinned) "[PINNED] " else ""
                        appendLine("  $pinTag${item.path} | ${item.detail} | ${item.sourceStep} #${item.scanNumber} (×${item.seenCount}, ${item.lastSeenTimestamp})")
                    }
                }
                appendLine()

                appendLine("--- [3] ENTITY SCAN RESULTS (${state.discoveredEntities.size} detected) ---")
                appendLine("Entity Array   : ${state.discoveredEntityArrayPath ?: "(not scanned)"}")
                if (state.entityProjectionMode.isNotBlank()) {
                    appendLine("Projection     : ${state.entityProjectionMode}")
                }
                if (state.discoveredEntities.isEmpty()) {
                    appendLine("  (none)")
                } else {
                    state.discoveredEntities.forEachIndexed { idx, e ->
                        val vis = if (e.inViewport) "IN VIEW" else "OFFSCREEN"
                        val vitalsSummary = buildList {
                            e.hp?.let { add("HP=$it${e.maxHp?.let { m -> "/$m" } ?: ""}") }
                            e.mp?.let { add("MP=$it${e.maxMp?.let { m -> "/$m" } ?: ""}") }
                            e.fp?.let { add("FP=$it${e.maxFp?.let { m -> "/$m" } ?: ""}") }
                        }.joinToString(" ")
                        val vitalsPart = if (vitalsSummary.isNotBlank()) " | $vitalsSummary" else ""
                        appendLine(
                            "  [${idx + 1}] ${e.displayName}${if (e.id.isNotBlank()) " #${e.id}" else ""} ($vis)$vitalsPart | " +
                                "world=(${String.format(Locale.US, "%.2f", e.worldX)}, ${String.format(Locale.US, "%.2f", e.worldY)}, ${String.format(Locale.US, "%.2f", e.worldZ)}) | " +
                                "dist=${String.format(Locale.US, "%.1f", e.distanceToPlayer)} | " +
                                "screen=(${String.format(Locale.US, "%.1f", e.screenX)}px, ${String.format(Locale.US, "%.1f", e.screenY)}px) | " +
                                "path=${e.path}"
                        )
                    }
                }
                if (state.structProber.schema.hasAnyBound || state.playerVitals != null) {
                    appendLine()
                    appendLine("--- [3B] BOUND ENTITY STRUCT SCHEMA & LIVE PLAYER VITALS ---")
                    state.playerVitals?.let { pv ->
                        appendLine(
                            "Player Vitals  : name=${pv.playerName ?: "(unbound)"} | " +
                                "HP=${pv.hp ?: "-"}/${pv.effectiveMaxHp ?: "-"} | " +
                                "MP=${pv.mp ?: "-"}/${pv.effectiveMaxMp ?: "-"} | " +
                                "FP=${pv.fp ?: "-"}/${pv.effectiveMaxFp ?: "-"}"
                        )
                    }
                    WasmStructFieldKind.entries.forEach { kind ->
                        state.structProber.schema.fieldFor(kind)?.let { f ->
                            appendLine("  ${kind.shortLabel.padEnd(6)} : ${f.displayExpression}")
                        }
                    }
                }
                appendLine()

                appendLine("--- [4] INTERCEPTED ASSET CATALOG (${state.interceptedAssets.size} observed, ${state.cachedAssetCount} cached locally) ---")
                if (state.interceptedAssets.isEmpty()) {
                    appendLine("  (none)")
                } else {
                    state.interceptedAssets.forEach { asset ->
                        val sizeStr = if (asset.byteSize > 0L) " | ${BinaryAssetInspector.formatBytes(asset.byteSize)}" else ""
                        val magicStr = if (asset.magicSignature.isNotBlank()) " | ${asset.magicSignature}" else ""
                        appendLine("  [${asset.timestamp}] ${asset.method} (${asset.extension}) ${asset.fileName.ifBlank { asset.url }}$sizeStr$magicStr")
                        appendLine("    URL: ${asset.url}")
                        if (!asset.localRelativePath.isNullOrBlank()) {
                            appendLine("    Local: filesDir/${asset.localRelativePath} | SHA-256: ${asset.sha256Prefix}")
                        }
                        if (asset.structuralSummary.isNotBlank()) {
                            appendLine("    Summary: ${asset.structuralSummary}")
                        }
                    }
                }
                appendLine()

                if (state.wasmHeap.isolatedIndex != null || state.wasmHeap.totalMatches > 0) {
                    appendLine("--- [5] WASM HEAP VALUE-TRACKER MEMORY MAP ---")
                    appendLine("Heap Source    : ${state.wasmHeap.heapSourceLabel.ifBlank { "(unknown)" }}")
                    appendLine("Matches        : ${state.wasmHeap.totalMatches} (HEAP32=${state.wasmHeap.heap32MatchCount}, HEAPF32=${state.wasmHeap.heapF32MatchCount})")
                    state.wasmHeap.isolatedIndex?.let { idx ->
                        val addr = state.wasmHeap.isolatedByteAddress ?: (idx.toLong() * 4L)
                        appendLine("Isolated Slot  : ${state.wasmHeap.isolatedHeapType ?: "HEAP32"}[$idx] (${formatHexAddress(addr)}) = ${state.wasmHeap.isolatedCurrentValue}")
                    }
                    state.wasmHeap.neighborFloats.forEach { nb ->
                        val sign = if (nb.byteOffset >= 0) "+" else ""
                        val dynTag = if (nb.changeCount > 0) " [DYNAMIC ×${nb.changeCount}]" else ""
                        appendLine("  ${sign}${nb.byteOffset}B (${formatHexAddress(nb.byteAddress)}) | F32=${String.format(Locale.US, "%.4f", nb.floatValue)} | I32=${nb.intValue}$dynTag")
                    }
                    appendLine()
                }

                if (state.wasmFloatDelta.candidates.isNotEmpty() ||
                    state.wasmFloatDelta.lockedCoords != null ||
                    state.wasmFloatDelta.pointerChains.isNotEmpty()
                ) {
                    appendLine("--- [5B] WASM FLOAT HEAP DELTA & PERSISTENT POINTERS (window.HEAPF32) ---")
                    appendLine("Heap Source    : ${state.wasmFloatDelta.heapSourceLabel.ifBlank { "window.HEAPF32" }}")
                    appendLine("Baseline / Hit : ${state.wasmFloatDelta.baselineSampleCount} stable -> ${state.wasmFloatDelta.totalDeltaMatches} moving (round #${state.wasmFloatDelta.scanRound})")
                    state.wasmFloatDelta.lockedCoords?.let { locked ->
                        val idxs = listOfNotNull(locked.xIndex, locked.yIndex, locked.zIndex).joinToString(",")
                        appendLine(
                            "Locked Coords  : window.HEAPF32[$idxs] (${formatHexAddress(locked.xByteAddress)}) | " +
                                "X=${String.format(Locale.US, "%.4f", locked.xValue)}, " +
                                "Y=${String.format(Locale.US, "%.4f", locked.yValue)}, " +
                                "Z=${String.format(Locale.US, "%.4f", locked.zValue)} (ticks=${locked.liveTickCount})"
                        )
                    }
                    state.wasmFloatDelta.candidates.forEach { c ->
                        val pinTag = if (c.isPinned) "[PINNED] " else ""
                        val axisTag = if (c.axisHint.isNotBlank()) "[${c.axisHint}] " else ""
                        appendLine(
                            "  $pinTag${axisTag}HEAPF32[${c.heapIndex}] (${formatHexAddress(c.byteAddress)}) | " +
                                "cur=${String.format(Locale.US, "%.4f", c.currentValue)} | " +
                                "base=${String.format(Locale.US, "%.4f", c.baselineValue)} | " +
                                "Δ=${String.format(Locale.US, "%+.4f", c.delta)} | ticksChanged=${c.changeCount}"
                        )
                    }
                    if (state.wasmFloatDelta.pointerChains.isNotEmpty()) {
                        appendLine("Persistent Pointer Chains (${state.wasmFloatDelta.pointerChains.size}, L1=${state.wasmFloatDelta.pointerScanTotalL1}, L2=${state.wasmFloatDelta.pointerScanTotalL2}, watchTicks=${state.wasmFloatDelta.pointerWatchTickCount}):")
                        state.wasmFloatDelta.pointerChains.forEach { p ->
                            val regTag = if (p.isLikelyStatic) "STATIC" else "HEAP"
                            val pinTag = if (p.isSavedLocally) "[SAVED] " else if (p.isPinned) "[PINNED] " else ""
                            val hudTag = if (p.isStreamingToHud) "[HUD] " else ""
                            appendLine(
                                "  $pinTag$hudTag[L${p.depth} $regTag ${p.status.name} Δx${p.changeCount}] ${p.expression} -> ${formatHexAddress(p.resolvedTargetAddress)} " +
                                    "(${String.format(Locale.US, "%.3f", p.resolvedX)}, ${String.format(Locale.US, "%.3f", p.resolvedY)}, ${String.format(Locale.US, "%.3f", p.resolvedZ)})"
                            )
                        }
                    }
                    appendLine()
                }

                appendLine("--- [6] COMPLETE ROLLING LOG HISTORY (${state.logs.size} entries) ---")
                if (state.logs.isEmpty()) {
                    appendLine("  (no log entries)")
                } else {
                    state.logs.forEach { entry ->
                        appendLine("[${entry.timestamp}] [${entry.category.tag}] ${entry.summary}")
                        if (entry.payload.isNotBlank() && entry.payload != entry.summary) {
                            entry.payload.lineSequence().forEach { line ->
                                appendLine("    $line")
                            }
                        }
                    }
                }
                appendLine("========================================================================")
            }
        }

        fun formatHexAddress(byteAddress: Long): String {
            return String.format(Locale.US, "0x%08X", byteAddress.coerceAtLeast(0L))
        }

        fun buildWasmMemoryMapJson(state: DiagnosticsUiState): String {
            val wasm = state.wasmHeap
            val root = JSONObject()
            root.put("application", "WebViewDiagnosticsApp")
            root.put("reportType", "wasm_heap_memory_map")
            root.put("generatedAtIso", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date()))
            root.put("activeUrl", state.activeUrl)
            root.put("remoteWebContentsDebuggingEnabled", false)
            root.put("heapSource", wasm.heapSourceLabel.ifBlank { "window.HEAP32 / window.HEAPF32" })
            root.put("heapByteLength", wasm.heapByteLength)
            root.put("initialSearchValue", wasm.initialSearchValue ?: JSONObject.NULL)
            root.put("lastFilterValue", wasm.lastFilterValue ?: JSONObject.NULL)
            root.put("filterRounds", wasm.filterRound)
            root.put("remainingMatches", wasm.totalMatches)
            root.put("heap32MatchCount", wasm.heap32MatchCount)
            root.put("heapF32MatchCount", wasm.heapF32MatchCount)

            val isolatedObj = JSONObject()
            if (wasm.isolatedIndex != null) {
                val byteAddr = wasm.isolatedByteAddress ?: (wasm.isolatedIndex.toLong() * 4L)
                isolatedObj.put("heapType", wasm.isolatedHeapType ?: "HEAP32")
                isolatedObj.put("elementIndex", wasm.isolatedIndex)
                isolatedObj.put("byteAddress", byteAddr)
                isolatedObj.put("hexByteAddress", formatHexAddress(byteAddr))
                isolatedObj.put("currentValue", wasm.isolatedCurrentValue)
                isolatedObj.put("pollTicksObserved", wasm.neighborPollTickCount)
                root.put("isolatedTarget", isolatedObj)
            } else {
                root.put("isolatedTarget", JSONObject.NULL)
            }

            val neighborsArr = JSONArray()
            val dynamicCoordsArr = JSONArray()
            wasm.neighborFloats.forEach { nb ->
                val isDynamic = nb.byteOffset != 0 && nb.changeCount > 0
                val nbJson = JSONObject()
                    .put("byteOffset", nb.byteOffset)
                    .put("offsetLabel", if (nb.byteOffset >= 0) "+${nb.byteOffset}B" else "${nb.byteOffset}B")
                    .put("heapIndex", nb.heapIndex)
                    .put("byteAddress", nb.byteAddress)
                    .put("hexByteAddress", formatHexAddress(nb.byteAddress))
                    .put("float32Value", nb.floatValue)
                    .put("int32Value", nb.intValue)
                    .put("changeCount", nb.changeCount)
                    .put("isTargetSlot", nb.byteOffset == 0)
                    .put("isDynamicCoordinateCandidate", isDynamic)
                neighborsArr.put(nbJson)
                if (isDynamic) {
                    dynamicCoordsArr.put(nbJson)
                }
            }
            root.put("neighboringMemoryMap", neighborsArr)
            root.put("dynamicCoordinateCandidates", dynamicCoordsArr)

            val candidatesArr = JSONArray()
            wasm.survivingCandidates.forEach { c ->
                candidatesArr.put(
                    JSONObject()
                        .put("heapType", c.heapType)
                        .put("index", c.index)
                        .put("byteAddress", c.byteAddress)
                        .put("hexByteAddress", formatHexAddress(c.byteAddress))
                        .put("currentValue", c.currentValue)
                )
            }
            root.put("survivingCandidates", candidatesArr)

            val floatDelta = state.wasmFloatDelta
            val floatDeltaArr = JSONArray()
            floatDelta.candidates.forEach { c ->
                floatDeltaArr.put(
                    JSONObject()
                        .put("heapIndex", c.heapIndex)
                        .put("byteAddress", c.byteAddress)
                        .put("hexByteAddress", formatHexAddress(c.byteAddress))
                        .put("axisHint", c.axisHint)
                        .put("baselineValue", c.baselineValue)
                        .put("currentValue", c.currentValue)
                        .put("delta", c.delta)
                        .put("clusterSize", c.clusterSize)
                        .put("isPinned", c.isPinned)
                        .put("changeCount", c.changeCount)
                )
            }
            val lockedJson = floatDelta.lockedCoords?.let { locked ->
                JSONObject()
                    .put("xIndex", locked.xIndex)
                    .put("yIndex", locked.yIndex ?: JSONObject.NULL)
                    .put("zIndex", locked.zIndex ?: JSONObject.NULL)
                    .put("xByteAddress", locked.xByteAddress)
                    .put("xHexByteAddress", formatHexAddress(locked.xByteAddress))
                    .put("yByteAddress", locked.yByteAddress ?: JSONObject.NULL)
                    .put("zByteAddress", locked.zByteAddress ?: JSONObject.NULL)
                    .put("xValue", locked.xValue)
                    .put("yValue", locked.yValue)
                    .put("zValue", locked.zValue)
                    .put("lockedAtTimestamp", locked.lockedAtTimestamp)
                    .put("liveTickCount", locked.liveTickCount)
            } ?: JSONObject.NULL

            val pointerChainsArr = JSONArray()
            floatDelta.pointerChains.forEach { chain ->
                val offs = JSONArray()
                chain.offsets.forEach { offs.put(it) }
                pointerChainsArr.put(
                    JSONObject()
                        .put("id", chain.id)
                        .put("depth", chain.depth)
                        .put("basePointerAddress", chain.basePointerAddress)
                        .put("basePointerHex", formatHexAddress(chain.basePointerAddress))
                        .put("offsets", offs)
                        .put("resolvedTargetAddress", chain.resolvedTargetAddress)
                        .put("resolvedTargetHex", formatHexAddress(chain.resolvedTargetAddress))
                        .put("resolvedX", chain.resolvedX)
                        .put("resolvedY", chain.resolvedY)
                        .put("resolvedZ", chain.resolvedZ)
                        .put("isLikelyStatic", chain.isLikelyStatic)
                        .put("isVerifiedLive", chain.isVerifiedLive)
                        .put("status", chain.status.name)
                        .put("changeCount", chain.changeCount)
                        .put("lastDeltaMagnitude", chain.lastDeltaMagnitude)
                        .put("isPinned", chain.isPinned)
                        .put("isSavedLocally", chain.isSavedLocally)
                        .put("isStreamingToHud", chain.isStreamingToHud)
                        .put("expression", chain.expression)
                )
            }

            root.put(
                "wasmFloatDeltaScanner",
                JSONObject()
                    .put("heapSource", floatDelta.heapSourceLabel.ifBlank { "window.HEAPF32" })
                    .put("baselineSampleCount", floatDelta.baselineSampleCount)
                    .put("scanRound", floatDelta.scanRound)
                    .put("totalDeltaMatches", floatDelta.totalDeltaMatches)
                    .put("lockedCoords", lockedJson)
                    .put("pointerScanTotalL1", floatDelta.pointerScanTotalL1)
                    .put("pointerScanTotalL2", floatDelta.pointerScanTotalL2)
                    .put("isWatchingPointers", floatDelta.isWatchingPointers)
                    .put("pointerWatchTickCount", floatDelta.pointerWatchTickCount)
                    .put("activeHudPointerId", floatDelta.activeHudPointerId ?: JSONObject.NULL)
                    .put("savedPointerCountForHost", floatDelta.savedPointerCountForHost)
                    .put("persistentPointerChains", pointerChainsArr)
                    .put("candidates", floatDeltaArr)
            )

            val schemaJsonObj = try {
                JSONObject(SiteStructSchemaStore.serializeSchema(state.structProber.schema))
            } catch (_: Exception) {
                JSONObject()
            }
            root.put("entityStructSchema", schemaJsonObj)
            state.playerVitals?.let { pv ->
                root.put(
                    "livePlayerVitals",
                    JSONObject()
                        .put("playerName", pv.playerName ?: JSONObject.NULL)
                        .put("hp", pv.hp ?: JSONObject.NULL)
                        .put("maxHp", pv.effectiveMaxHp ?: JSONObject.NULL)
                        .put("mp", pv.mp ?: JSONObject.NULL)
                        .put("maxMp", pv.effectiveMaxMp ?: JSONObject.NULL)
                        .put("fp", pv.fp ?: JSONObject.NULL)
                        .put("maxFp", pv.effectiveMaxFp ?: JSONObject.NULL)
                )
            } ?: root.put("livePlayerVitals", JSONObject.NULL)

            return root.toString(2)
        }

        private fun sortStagingBuffer(items: List<CandidateStagingItem>): List<CandidateStagingItem> {
            return items.sortedWith(
                compareByDescending<CandidateStagingItem> { it.isPinned }
                    .thenByDescending { it.scanNumber }
                    .thenByDescending { it.seenCount }
            )
        }

        fun normalizeAndValidateUrl(rawInput: String): UrlValidationResult {
            val trimmed = rawInput.trim()
            if (trimmed.isEmpty() || trimmed.equals("about:blank", ignoreCase = true)) {
                return UrlValidationResult.Valid("about:blank")
            }

            val lower = trimmed.lowercase(Locale.US)
            val disallowedSchemes = listOf("javascript:", "file:", "content:", "data:", "intent:", "vbscript:")
            if (disallowedSchemes.any { lower.startsWith(it) }) {
                return UrlValidationResult.Invalid("Scheme not allowed in diagnostic WebView: ${trimmed.substringBefore(':')}:")
            }

            val withScheme = if (lower.startsWith("https://") || lower.startsWith("http://")) {
                trimmed
            } else if (trimmed.contains("://")) {
                return UrlValidationResult.Invalid("Only http://, https://, and about:blank URLs are supported.")
            } else {
                "https://$trimmed"
            }

            val parsed = Uri.parse(withScheme)
            val host = parsed.host
            if (host.isNullOrBlank()) {
                return UrlValidationResult.Invalid("Please enter a valid domain or URL.")
            }
            return UrlValidationResult.Valid(withScheme)
        }

        fun isAllowedNavigationUri(uri: Uri?): Boolean {
            if (uri == null) return false
            val full = uri.toString()
            if (full.equals("about:blank", ignoreCase = true)) return true
            val scheme = uri.scheme?.lowercase(Locale.US)
            return scheme == "https" || scheme == "http"
        }

        fun matchTrackedAssetExtension(pathLower: String): String? {
            for (ext in TRACKED_EXTENSIONS) {
                if (pathLower.endsWith(ext) || pathLower.contains("$ext?")) {
                    return ext
                }
            }
            if (pathLower.contains("chunk") && (pathLower.endsWith(".js") || pathLower.endsWith(".mjs") || pathLower.endsWith(".bin"))) {
                return "chunk"
            }
            return null
        }
    }
}
