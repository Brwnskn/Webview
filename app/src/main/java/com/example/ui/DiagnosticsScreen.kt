package com.example.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.DiagnosticsEffect
import com.example.DiagnosticsUiState
import com.example.DiagnosticsViewModel
import com.example.LogCategory
import com.example.R
import com.example.TelemetryBridge
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceElevated
import com.example.ui.theme.SignalEmerald

@Composable
fun DiagnosticsScreen(
    viewModel: DiagnosticsViewModel = viewModel()
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    // Synchronously attach ApplicationContext BEFORE collecting state or mounting WebView
    // so synchronous SharedPreferences pointer & schema stores load saved sites on frame 1.
    remember(viewModel, context) {
        viewModel.attachContext(context)
        true
    }

    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val defaultMobileUa = remember(context) {
        runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault("")
    }

    val telemetryBridge = remember(viewModel) {
        TelemetryBridge(
            onTelemetryReceived = { category, payload ->
                viewModel.onTelemetryFromBridge(category, payload)
            },
            onReportPayloadReceived = { jsonContent ->
                viewModel.onReportPayloadFromBridge(jsonContent)
            },
            onScanStatusChanged = { status, detail ->
                viewModel.onScanStatusChanged(status, detail)
            },
            onPlayerCoordsUpdated = { path, x, y, z ->
                viewModel.onPlayerCoordsUpdated(path, x, y, z)
            },
            onBatchProgressChanged = { progress ->
                viewModel.onBatchProgressChanged(progress)
            },
            onWasmNeighborsUpdated = { jsonPayload ->
                viewModel.onWasmNeighborsUpdatedFromBridge(jsonPayload)
            },
            onWasmFloatCandidatesUpdated = { jsonPayload ->
                viewModel.onWasmFloatCandidatesUpdatedFromBridge(jsonPayload)
            },
            onWasmPointersUpdated = { jsonPayload ->
                viewModel.onWasmPointersUpdatedFromBridge(jsonPayload)
            },
            onWasmEntitiesUpdated = { jsonPayload ->
                viewModel.onWasmEntitiesUpdatedFromBridge(jsonPayload)
            },
            onWasmTargetUpdated = { jsonPayload ->
                viewModel.onWasmTargetUpdatedFromBridge(jsonPayload)
            }
        )
    }

    // Collect one-shot WebView and system effects
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is DiagnosticsEffect.LoadUrl -> {
                    webViewRef?.loadUrl(effect.url)
                }
                is DiagnosticsEffect.ReloadWebView -> {
                    webViewRef?.reload()
                }
                is DiagnosticsEffect.ApplyUserAgent -> {
                    webViewRef?.let { wv ->
                        wv.settings.userAgentString =
                            if (effect.desktopMode) DiagnosticsViewModel.DESKTOP_USER_AGENT
                            else defaultMobileUa
                        if (effect.reloadAfter) {
                            wv.reload()
                        }
                    }
                }
                is DiagnosticsEffect.EvaluateJs -> {
                    val wv = webViewRef
                    if (wv == null) {
                        viewModel.onTelemetryFromBridge(
                            LogCategory.ERROR.tag,
                            "WebView is not initialized yet."
                        )
                    } else {
                        wv.evaluateJavascript(effect.script, null)
                    }
                }
                is DiagnosticsEffect.ShowToast -> {
                    Toast.makeText(context, effect.message, Toast.LENGTH_LONG).show()
                }
                is DiagnosticsEffect.ShareReport -> {
                    shareExportedFile(context, effect.uri, effect.fileName, effect.mimeType)
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                stopLoading()
                removeJavascriptInterface(TelemetryBridge.BRIDGE_NAME)
                destroy()
            }
            webViewRef = null
        }
    }

    Scaffold(
        containerColor = ObsidianBg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            CompactUrlNavigationBar(
                urlInput = state.urlInput,
                isDesktopMode = state.isDesktopMode,
                isLoading = state.isLoading,
                loadProgress = state.loadProgress,
                onUrlChanged = viewModel::onUrlInputChanged,
                onGo = {
                    focusManager.clearFocus()
                    viewModel.onLoadUrlRequested()
                },
                onReload = {
                    focusManager.clearFocus()
                    viewModel.onReloadRequested()
                },
                onToggleDesktopMode = viewModel::onToggleDesktopMode
            )
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
                .imePadding()
        ) {
            val isWideScreen = maxWidth >= 840.dp
            val density = LocalDensity.current
            val totalHeightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
            val totalWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
            val currentSplit by rememberUpdatedState(state.consoleSplitFraction)

            if (isWideScreen) {
                // Expanded / Tablet / Landscape Draggable Side-by-Side Split
                val consoleWeight = state.consoleSplitFraction.coerceIn(0.24f, 0.78f)
                val webViewWeight = (1f - consoleWeight).coerceAtLeast(0.22f)

                Row(modifier = Modifier.fillMaxSize()) {
                    DiagnosticWebViewContainer(
                        activeUrl = state.activeUrl,
                        isDesktopMode = state.isDesktopMode,
                        savedSiteCount = state.savedSiteProfiles.size,
                        defaultMobileUa = defaultMobileUa,
                        telemetryBridge = telemetryBridge,
                        onWebViewCreated = { webViewRef = it },
                        onPageStarted = viewModel::onPageStarted,
                        onPageFinished = viewModel::onPageFinished,
                        onProgressChanged = viewModel::onProgressChanged,
                        onResourceObserved = { req -> viewModel.onResourceRequestObserved(context, req) },
                        onWebViewError = viewModel::onWebViewError,
                        onBlockedScheme = viewModel::onBlockedSchemeAttempt,
                        onConsoleError = { msg ->
                            viewModel.onTelemetryFromBridge(LogCategory.ERROR.tag, msg)
                        },
                        modifier = Modifier
                            .weight(webViewWeight)
                            .fillMaxHeight()
                    )

                    LandscapeDraggableSplitHandle(
                        onDragDeltaPx = { dragX ->
                            // Dragging left widens the right-side console
                            val next = (currentSplit - (dragX / totalWidthPx)).coerceIn(0.24f, 0.78f)
                            viewModel.onSetConsoleSplitFraction(next)
                        }
                    )

                    WiredTelemetryConsolePanel(
                        state = state,
                        viewModel = viewModel,
                        context = context,
                        telemetryBridge = telemetryBridge,
                        onClearFocus = { focusManager.clearFocus() },
                        modifier = Modifier
                            .weight(consoleWeight)
                            .fillMaxHeight()
                    )
                }
            } else {
                // Mobile Portrait Draggable Split View + Full-Height Expandable Tools Sheet
                val consoleWeight = state.consoleSplitFraction.coerceIn(0.14f, 0.88f)
                val webViewWeight = (1f - consoleWeight).coerceAtLeast(0.12f)

                Column(modifier = Modifier.fillMaxSize()) {
                    DiagnosticWebViewContainer(
                        activeUrl = state.activeUrl,
                        isDesktopMode = state.isDesktopMode,
                        savedSiteCount = state.savedSiteProfiles.size,
                        defaultMobileUa = defaultMobileUa,
                        telemetryBridge = telemetryBridge,
                        onWebViewCreated = { webViewRef = it },
                        onPageStarted = viewModel::onPageStarted,
                        onPageFinished = viewModel::onPageFinished,
                        onProgressChanged = viewModel::onProgressChanged,
                        onResourceObserved = { req -> viewModel.onResourceRequestObserved(context, req) },
                        onWebViewError = viewModel::onWebViewError,
                        onBlockedScheme = viewModel::onBlockedSchemeAttempt,
                        onConsoleError = { msg ->
                            viewModel.onTelemetryFromBridge(LogCategory.ERROR.tag, msg)
                        },
                        modifier = Modifier
                            .weight(webViewWeight)
                            .fillMaxWidth()
                    )

                    PortraitDraggableSplitHandle(
                        splitFraction = consoleWeight,
                        onDragDeltaPx = { dragY ->
                            // Dragging upward increases console height fraction
                            val next = (currentSplit - (dragY / totalHeightPx)).coerceIn(0.14f, 0.88f)
                            viewModel.onSetConsoleSplitFraction(next)
                        },
                        onSnapMini = viewModel::onSnapConsoleMini,
                        onSnapSplit = viewModel::onSnapConsoleSplit,
                        onSnapFull = viewModel::onSnapConsoleFull
                    )

                    WiredTelemetryConsolePanel(
                        state = state,
                        viewModel = viewModel,
                        context = context,
                        telemetryBridge = telemetryBridge,
                        onClearFocus = { focusManager.clearFocus() },
                        modifier = Modifier
                            .weight(consoleWeight)
                            .fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun PortraitDraggableSplitHandle(
    splitFraction: Float,
    onDragDeltaPx: (Float) -> Unit,
    onSnapMini: () -> Unit,
    onSnapSplit: () -> Unit,
    onSnapFull: () -> Unit
) {
    val dragCd = stringResource(R.string.cd_drag_split_handle)
    val isMini = splitFraction <= 0.19f
    val isFull = splitFraction >= 0.78f
    val isSplit = !isMini && !isFull

    Surface(
        color = ObsidianSurfaceElevated,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = dragCd }
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, dragAmount ->
                    change.consume()
                    onDragDeltaPx(dragAmount)
                }
            }
            .testTag("portrait_split_drag_handle")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(color = ElectricCyan.copy(alpha = 0.45f), thickness = 1.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SplitSnapChip(
                    label = stringResource(R.string.split_snap_mini),
                    selected = isMini,
                    testTag = "split_snap_mini_button",
                    onClick = onSnapMini
                )

                // Center tactile drag grip pill
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(26.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(46.dp)
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(ElectricCyan.copy(alpha = 0.65f))
                    )
                }

                SplitSnapChip(
                    label = stringResource(R.string.split_snap_half),
                    selected = isSplit,
                    testTag = "split_snap_half_button",
                    onClick = onSnapSplit
                )
                SplitSnapChip(
                    label = stringResource(R.string.split_snap_full),
                    selected = isFull,
                    testTag = "split_snap_full_button",
                    onClick = onSnapFull
                )
            }
            HorizontalDivider(color = ObsidianBorder, thickness = 1.dp)
        }
    }
}

@Composable
private fun SplitSnapChip(
    label: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = if (selected) ElectricCyan.copy(alpha = 0.24f) else ObsidianBg,
        border = BorderStroke(
            1.dp,
            if (selected) ElectricCyan else ObsidianBorder
        ),
        modifier = Modifier
            .heightIn(min = 28.dp)
            .testTag(testTag)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) ElectricCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun LandscapeDraggableSplitHandle(
    onDragDeltaPx: (Float) -> Unit
) {
    val dragCd = stringResource(R.string.cd_drag_split_handle)
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(16.dp)
            .background(ObsidianSurfaceElevated)
            .semantics { contentDescription = dragCd }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, dragAmount ->
                    change.consume()
                    onDragDeltaPx(dragAmount)
                }
            }
            .testTag("landscape_split_drag_handle"),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(48.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(ElectricCyan.copy(alpha = 0.65f))
        )
    }
}

@Composable
private fun WiredTelemetryConsolePanel(
    state: DiagnosticsUiState,
    viewModel: DiagnosticsViewModel,
    context: Context,
    telemetryBridge: TelemetryBridge,
    onClearFocus: () -> Unit,
    modifier: Modifier = Modifier
) {
    TelemetryConsolePanel(
        state = state,
        onRunStep1 = viewModel::runStep1InspectScope,
        onPauseStep1 = viewModel::pauseStep1BatchScan,
        onResumeStep1 = viewModel::resumeStep1BatchScan,
        onManualPathInputChanged = viewModel::onManualPathInputChanged,
        onQueryManualPath = { override ->
            onClearFocus()
            viewModel.onQueryManualPropertyPath(override)
        },
        onRunStep2A = viewModel::runStep2ACaptureBaseline,
        onRunStep2B = viewModel::runStep2BComputeDelta,
        onRunStep3 = viewModel::runStep3ScanEntities,
        onExportReport = {
            viewModel.requestExportReport(
                context = context,
                armBridge = telemetryBridge::armExport,
                disarmBridge = telemetryBridge::disarmExport
            )
        },
        onShareReport = viewModel::onShareLastReport,
        onExportLogDumpTxt = { viewModel.exportFullLogDump(context, asJson = false) },
        onExportLogDumpJson = { viewModel.exportFullLogDump(context, asJson = true) },
        onShareLogDump = viewModel::onShareLastLogDump,
        onSelectConsoleTab = viewModel::onSelectConsoleTab,
        onSelectProberSubTab = viewModel::onSelectProberSubTab,
        onSelectLogsAssetsSubTab = viewModel::onSelectLogsAssetsSubTab,
        onOpenSavedSiteProfile = { profile ->
            onClearFocus()
            viewModel.onOpenSavedSiteProfile(profile)
        },
        onDeleteSavedSiteProfile = viewModel::onDeleteSavedSiteProfile,
        onSelectFilter = viewModel::onSelectFilter,
        onTogglePinCandidate = viewModel::onTogglePinCandidate,
        onTogglePinnedOnly = viewModel::onTogglePinnedOnlyFilter,
        onStagingSearchQueryChanged = viewModel::onStagingSearchQueryChanged,
        onClearUnpinnedCandidates = viewModel::onClearUnpinnedCandidates,
        onLockCandidateForLivePolling = viewModel::onLockCandidateForLivePolling,
        onPinExternalPath = viewModel::onPinExternalPath,
        onToggleJsDrawer = { viewModel.onToggleJsDrawer() },
        onManualJsChanged = viewModel::onManualJsInputChanged,
        onExecuteManualJs = viewModel::onExecuteManualScript,
        onClearAssetCache = { viewModel.onClearAssetCache(context) },
        onClearLogs = viewModel::onClearLogs,
        onExpandFromMini = viewModel::onSnapConsoleSplit,
        onCopyPayload = { text -> copyToClipboard(context, text) },
        onWasmValueChanged = viewModel::onWasmTargetValueChanged,
        onRunWasmScan = {
            onClearFocus()
            viewModel.runWasmHeapInitialScan()
        },
        onRunWasmFilter = {
            onClearFocus()
            viewModel.runWasmHeapFilterDelta()
        },
        onInspectWasmCandidate = viewModel::onInspectWasmCandidate,
        onStopWasmPoll = viewModel::onStopWasmNeighborPolling,
        onResetWasmTracker = viewModel::onResetWasmHeapTracker,
        onExportWasmMemoryMap = { viewModel.exportWasmMemoryMapJson(context) },
        onShareWasmMemoryMap = viewModel::onShareLastWasmMemoryMap,
        onSelectStep2ScanMode = viewModel::onSelectStep2ScanMode,
        onRunFloatBaseline = viewModel::runWasmFloatBaselineSnapshot,
        onRunFloatDeltaScan = viewModel::runWasmFloatDeltaScan,
        onResetFloatDelta = viewModel::onResetWasmFloatDelta,
        onToggleFloatWatcherExpanded = viewModel::onToggleFloatWatcherExpanded,
        onTogglePinFloatCandidate = viewModel::onTogglePinWasmFloatCandidate,
        onPinTopFloatTriplet = viewModel::onPinTopWasmFloatTriplet,
        onInspectFloatNeighbors = viewModel::onInspectWasmFloatNeighbors,
        onStopFloatWatch = viewModel::onStopWasmFloatWatch,
        onLockFloatCoords = viewModel::onLockWasmFloatCoords,
        onUnlockFloatCoords = viewModel::onUnlockWasmFloatCoords,
        onFindWasmPointers = { viewModel.runWasmPointerScan() },
        onVerifyWasmPointers = viewModel::runVerifyWasmPointerChains,
        onToggleWatchWasmPointers = viewModel::onToggleWatchWasmPointers,
        onStreamPointerToHud = viewModel::onStreamPointerChainToHud,
        onPruneWasmPointers = viewModel::onPruneZeroAndStalePointers,
        onTogglePinPointerChain = viewModel::onTogglePinWasmPointerChain,
        onToggleWasmEntitiesLive = viewModel::onToggleWasmEntitiesLiveRefresh,
        onToggleTrackEntity = viewModel::onToggleTrackEntityForLocationVerify,
        onClearTrackedEntity = viewModel::onClearTrackedEntity,
        onCaptureSelectedTarget = viewModel::onCaptureSelectedTarget,
        onFilterSelectedTarget = viewModel::onFilterSelectedTarget,
        onLockSelectedTargetSlot = viewModel::onLockSelectedTargetSlot,
        onStopSelectedTargetWatch = viewModel::onStopSelectedTargetWatch,
        onResetSelectedTargetScan = viewModel::onResetSelectedTargetScan,
        onProbeSelectedTargetField = viewModel::onProbeSelectedTargetInStructProber,
        onAssignSelectedTargetToEntities = viewModel::onAssignSelectedTargetToEntityList,
        onSelectStructProbeAnchor = viewModel::onSelectStructProbeAnchor,
        onSelectStructProbeField = viewModel::onSelectStructProbeField,
        onStructProbeInputChanged = viewModel::onStructProbeInputChanged,
        onRunStructProbe = {
            onClearFocus()
            viewModel.runStructFieldProbe(refineExisting = false)
        },
        onRunStructRefine = {
            onClearFocus()
            viewModel.runStructFieldProbe(refineExisting = true)
        },
        onRunAutoProbeNames = {
            onClearFocus()
            viewModel.runAutoProbeEntityNames()
        },
        onRunAggroStructProbe = { isAggroedNow, refineExisting ->
            onClearFocus()
            viewModel.runAggroStructProbe(isAggroedNow = isAggroedNow, refineExisting = refineExisting)
        },
        onBindStructCandidate = { cand -> viewModel.onBindStructFieldCandidate(cand) },
        onClearBoundStructField = viewModel::onClearBoundStructField,
        onClearAllBoundStructFields = viewModel::onClearAllBoundStructFields,
        modifier = modifier
    )
}

@Composable
private fun CompactUrlNavigationBar(
    urlInput: String,
    isDesktopMode: Boolean,
    isLoading: Boolean,
    loadProgress: Int,
    onUrlChanged: (String) -> Unit,
    onGo: () -> Unit,
    onReload: () -> Unit,
    onToggleDesktopMode: () -> Unit
) {
    Surface(
        color = ObsidianSurface,
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Compact URL Input Field
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = ObsidianBg,
                    border = BorderStroke(1.dp, ObsidianBorder),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = null,
                            tint = ElectricCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (urlInput.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.url_input_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            BasicTextField(
                                value = urlInput,
                                onValueChange = onUrlChanged,
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                cursorBrush = SolidColor(ElectricCyan),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Uri,
                                    imeAction = ImeAction.Go
                                ),
                                keyboardActions = KeyboardActions(onGo = { onGo() }),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("url_input_field")
                            )
                        }
                    }
                }

                // Go / Load Button
                Button(
                    onClick = onGo,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricCyan,
                        contentColor = ObsidianBg
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("url_go_button")
                ) {
                    Text(
                        text = stringResource(R.string.action_load),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(3.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Reload Button
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = ObsidianSurfaceElevated,
                    border = BorderStroke(1.dp, ObsidianBorder),
                    modifier = Modifier.size(48.dp)
                ) {
                    IconButton(
                        onClick = onReload,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("url_reload_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.cd_reload_page),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Desktop UA Toggle Button
                Surface(
                    onClick = onToggleDesktopMode,
                    shape = RoundedCornerShape(10.dp),
                    color = if (isDesktopMode) SignalEmerald.copy(alpha = 0.16f) else ObsidianSurfaceElevated,
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (isDesktopMode) SignalEmerald else ObsidianBorder
                    ),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("desktop_ua_toggle")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isDesktopMode) Icons.Default.DesktopWindows else Icons.Default.PhoneAndroid,
                            contentDescription = stringResource(R.string.cd_toggle_desktop_ua),
                            tint = if (isDesktopMode) SignalEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            if (isLoading) {
                LinearProgressIndicator(
                    progress = { loadProgress / 100f },
                    color = ElectricCyan,
                    trackColor = ObsidianSurfaceElevated,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                )
            } else {
                HorizontalDivider(color = ObsidianBorder, thickness = 1.dp)
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun DiagnosticWebViewContainer(
    activeUrl: String,
    isDesktopMode: Boolean,
    savedSiteCount: Int,
    defaultMobileUa: String,
    telemetryBridge: TelemetryBridge,
    onWebViewCreated: (WebView) -> Unit,
    onPageStarted: (String) -> Unit,
    onPageFinished: (String, String?) -> Unit,
    onProgressChanged: (Int) -> Unit,
    onResourceObserved: (WebResourceRequest) -> Unit,
    onWebViewError: (String, String?) -> Unit,
    onBlockedScheme: (String) -> Unit,
    onConsoleError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(ObsidianBg)
            .testTag("diagnostic_webview_container")
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                // Explicitly enforce remote debugging remains disabled as required.
                WebView.setWebContentsDebuggingEnabled(false)

                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        builtInZoomControls = true
                        displayZoomControls = false
                        mediaPlaybackRequiresUserGesture = false
                        // Security hardening: restrict local file/content access and mixed content
                        allowFileAccess = false
                        allowContentAccess = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            safeBrowsingEnabled = true
                        }
                        userAgentString = if (isDesktopMode) {
                            DiagnosticsViewModel.DESKTOP_USER_AGENT
                        } else {
                            defaultMobileUa
                        }
                    }

                    addJavascriptInterface(telemetryBridge, TelemetryBridge.BRIDGE_NAME)

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val uri = request?.url
                            if (!DiagnosticsViewModel.isAllowedNavigationUri(uri)) {
                                onBlockedScheme(uri?.toString().orEmpty())
                                return true
                            }
                            return false
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            if (url != null) onPageStarted(url)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (url != null) onPageFinished(url, view?.title)
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            if (request != null) {
                                onResourceObserved(request)
                            }
                            // Passive telemetry only: never alter or block network traffic.
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            super.onReceivedError(view, request, error)
                            if (request?.isForMainFrame == true) {
                                val desc = error?.description?.toString() ?: "Network error"
                                onWebViewError(desc, request.url?.toString())
                            }
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            onProgressChanged(newProgress)
                        }

                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                            if (consoleMessage?.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                                onConsoleError(
                                    "Console Error (${consoleMessage.lineNumber()}): ${consoleMessage.message()}"
                                )
                            }
                            return super.onConsoleMessage(consoleMessage)
                        }
                    }

                    loadUrl(activeUrl)
                    onWebViewCreated(this)
                }
            }
        )

        // Subtle viewport badge when standing by on about:blank
        if (activeUrl == "about:blank") {
            Surface(
                color = ObsidianSurface.copy(alpha = 0.92f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, ObsidianBorder),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(20.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = "WebView Container Ready",
                        style = MaterialTheme.typography.titleMedium,
                        color = ElectricCyan
                    )
                    Text(
                        text = if (isDesktopMode) "Desktop UA • Hardware Accelerated • Remote Debug OFF"
                        else "Mobile UA • Hardware Accelerated • Remote Debug OFF",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (savedSiteCount > 0) {
                            "Enter a URL above or tap Open & Verify on your saved site below."
                        } else {
                            "Enter any https:// URL in the bar above and tap Go."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Telemetry Payload", text))
    Toast.makeText(context, context.getString(R.string.toast_log_copied), Toast.LENGTH_SHORT).show()
}

private fun shareExportedFile(context: Context, uri: Uri, fileName: String, mimeType: String) {
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType.ifBlank { "text/plain" }
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "WebView Diagnostics Export: $fileName")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(
        shareIntent,
        context.getString(R.string.share_report_chooser_title)
    )
    runCatching { context.startActivity(chooser) }
}
