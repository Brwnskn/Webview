package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.AsyncScanStatus
import com.example.ConsoleTab
import com.example.DiagnosticsUiState
import com.example.LogCategory
import com.example.LogsAssetsSubTab
import com.example.PlayerCoordinates
import com.example.ProberSubTab
import com.example.R
import com.example.SavedSiteProfile
import com.example.Step2ScanMode
import com.example.TargetScanFilterMode
import com.example.WasmStructFieldKind
import com.example.WasmStructProbeAnchor
import com.example.WasmStructProbeCandidate
import com.example.WizardStepBadge
import com.example.ui.theme.AlertCoral
import com.example.ui.theme.AssetAmber
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceElevated
import com.example.ui.theme.ObsidianSurfaceHighest
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.TelemetryViolet
import java.util.Locale

@Composable
fun TelemetryConsolePanel(
    state: DiagnosticsUiState,
    onRunStep1: () -> Unit,
    onPauseStep1: () -> Unit,
    onResumeStep1: () -> Unit,
    onManualPathInputChanged: (String) -> Unit,
    onQueryManualPath: (String?) -> Unit,
    onRunStep2A: () -> Unit,
    onRunStep2B: () -> Unit,
    onRunStep3: () -> Unit,
    onExportReport: () -> Unit,
    onShareReport: () -> Unit,
    onExportLogDumpTxt: () -> Unit,
    onExportLogDumpJson: () -> Unit,
    onShareLogDump: () -> Unit,
    onSelectConsoleTab: (ConsoleTab) -> Unit,
    onSelectProberSubTab: (ProberSubTab) -> Unit,
    onSelectLogsAssetsSubTab: (LogsAssetsSubTab) -> Unit,
    onOpenSavedSiteProfile: (SavedSiteProfile) -> Unit,
    onDeleteSavedSiteProfile: (String) -> Unit,
    onSelectFilter: (LogCategory) -> Unit,
    onTogglePinCandidate: (String) -> Unit,
    onTogglePinnedOnly: () -> Unit,
    onStagingSearchQueryChanged: (String) -> Unit,
    onClearUnpinnedCandidates: () -> Unit,
    onLockCandidateForLivePolling: (String) -> Unit,
    onPinExternalPath: (path: String, sourceStep: String, detail: String) -> Unit,
    onToggleJsDrawer: () -> Unit,
    onManualJsChanged: (String) -> Unit,
    onExecuteManualJs: () -> Unit,
    onClearAssetCache: () -> Unit,
    onClearLogs: () -> Unit,
    onExpandFromMini: () -> Unit,
    onCopyPayload: (String) -> Unit,
    onWasmValueChanged: (String) -> Unit,
    onRunWasmScan: () -> Unit,
    onRunWasmFilter: () -> Unit,
    onInspectWasmCandidate: (heapType: String, index: Int) -> Unit,
    onStopWasmPoll: () -> Unit,
    onResetWasmTracker: () -> Unit,
    onExportWasmMemoryMap: () -> Unit,
    onShareWasmMemoryMap: () -> Unit,
    onSelectStep2ScanMode: (Step2ScanMode) -> Unit,
    onRunFloatBaseline: () -> Unit,
    onRunFloatDeltaScan: () -> Unit,
    onResetFloatDelta: () -> Unit,
    onToggleFloatWatcherExpanded: () -> Unit,
    onTogglePinFloatCandidate: (Int) -> Unit,
    onPinTopFloatTriplet: () -> Unit,
    onInspectFloatNeighbors: (Int) -> Unit,
    onStopFloatWatch: () -> Unit,
    onLockFloatCoords: (anchorIndex: Int?) -> Unit,
    onUnlockFloatCoords: () -> Unit,
    onFindWasmPointers: () -> Unit,
    onVerifyWasmPointers: () -> Unit,
    onToggleWatchWasmPointers: () -> Unit,
    onStreamPointerToHud: (String) -> Unit,
    onPruneWasmPointers: () -> Unit,
    onTogglePinPointerChain: (String) -> Unit,
    onToggleWasmEntitiesLive: () -> Unit,
    onToggleTrackEntity: (String) -> Unit,
    onClearTrackedEntity: () -> Unit,
    onCaptureSelectedTarget: () -> Unit,
    onFilterSelectedTarget: (TargetScanFilterMode) -> Unit,
    onLockSelectedTargetSlot: (Long) -> Unit,
    onStopSelectedTargetWatch: () -> Unit,
    onResetSelectedTargetScan: () -> Unit,
    onProbeSelectedTargetField: (WasmStructFieldKind) -> Unit,
    onAssignSelectedTargetToEntities: () -> Unit,
    onSelectStructProbeAnchor: (WasmStructProbeAnchor) -> Unit,
    onSelectStructProbeField: (WasmStructFieldKind) -> Unit,
    onStructProbeInputChanged: (String) -> Unit,
    onRunStructProbe: () -> Unit,
    onRunStructRefine: () -> Unit,
    onRunAutoProbeNames: () -> Unit,
    onRunAggroStructProbe: (isAggroedNow: Boolean, refineExisting: Boolean) -> Unit,
    onBindStructCandidate: (WasmStructProbeCandidate) -> Unit,
    onClearBoundStructField: (WasmStructFieldKind) -> Unit,
    onClearAllBoundStructFields: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (state.isJsDrawerOpen) {
        BackHandler { onToggleJsDrawer() }
    }

    val isMiniHud = state.consoleSplitFraction <= 0.19f

    Surface(
        modifier = modifier.fillMaxSize(),
        color = ObsidianSurface
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Persistent Live Coords + Bound Player HP/MP/FP HUD strip (always visible, even in Mini HUD)
            LiveHudTelemetryStrip(
                state = state,
                isMiniHud = isMiniHud,
                onTapExpand = onExpandFromMini,
                onCopyPayload = onCopyPayload
            )

            if (!isMiniHud) {
                // 4-Tab Equal-Width Primary Bar + Slim Utility Status Strip
                FourTabHeaderBar(
                    state = state,
                    onSelectTab = onSelectConsoleTab,
                    onToggleJsDrawer = onToggleJsDrawer,
                    onClearLogs = onClearLogs
                )

                HorizontalDivider(color = ObsidianBorder, thickness = 1.dp)

                AnimatedVisibility(
                    visible = state.isJsDrawerOpen,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    ManualJsExecutionDrawer(
                        script = state.manualJsInput,
                        onScriptChanged = onManualJsChanged,
                        onExecute = onExecuteManualJs,
                        onClose = onToggleJsDrawer
                    )
                }

                when (state.activeConsoleTab) {
                    ConsoleTab.COORDS -> {
                        CoordsSingleScrollTab(
                            state = state,
                            onOpenSavedSiteProfile = onOpenSavedSiteProfile,
                            onDeleteSavedSiteProfile = onDeleteSavedSiteProfile,
                            onRunStep1 = onRunStep1,
                            onPauseStep1 = onPauseStep1,
                            onResumeStep1 = onResumeStep1,
                            onManualPathInputChanged = onManualPathInputChanged,
                            onQueryManualPath = onQueryManualPath,
                            onRunStep2A = onRunStep2A,
                            onRunStep2B = onRunStep2B,
                            onSelectStep2ScanMode = onSelectStep2ScanMode,
                            onRunFloatBaseline = onRunFloatBaseline,
                            onRunFloatDeltaScan = onRunFloatDeltaScan,
                            onResetFloatDelta = onResetFloatDelta,
                            onToggleFloatWatcherExpanded = onToggleFloatWatcherExpanded,
                            onTogglePinFloatCandidate = onTogglePinFloatCandidate,
                            onPinTopFloatTriplet = onPinTopFloatTriplet,
                            onInspectFloatNeighbors = onInspectFloatNeighbors,
                            onStopFloatWatch = onStopFloatWatch,
                            onLockFloatCoords = onLockFloatCoords,
                            onUnlockFloatCoords = onUnlockFloatCoords,
                            onFindWasmPointers = onFindWasmPointers,
                            onVerifyWasmPointers = onVerifyWasmPointers,
                            onToggleWatchWasmPointers = onToggleWatchWasmPointers,
                            onStreamPointerToHud = onStreamPointerToHud,
                            onPruneWasmPointers = onPruneWasmPointers,
                            onTogglePinPointerChain = onTogglePinPointerChain,
                            onExportWasmMemoryMap = onExportWasmMemoryMap,
                            onCopyPayload = onCopyPayload,
                            onRunStep3 = onRunStep3,
                            onExportReport = onExportReport,
                            onShareReport = onShareReport,
                            onOpenStaging = {
                                onSelectLogsAssetsSubTab(LogsAssetsSubTab.STAGING)
                                onSelectConsoleTab(ConsoleTab.LOGS_ASSETS)
                            },
                            onOpenEntities = { onSelectConsoleTab(ConsoleTab.ENTITIES) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        )
                    }

                    ConsoleTab.ENTITIES -> {
                        EntityInspectionConsoleView(
                            state = state,
                            onRescanEntities = onRunStep3,
                            onToggleLiveEntities = onToggleWasmEntitiesLive,
                            onOpenTargetFinder = {
                                onSelectProberSubTab(ProberSubTab.TARGET_FINDER)
                                onSelectConsoleTab(ConsoleTab.PROBER)
                            },
                            onOpenProber = {
                                onSelectProberSubTab(ProberSubTab.STRUCT_PROBER)
                                onSelectConsoleTab(ConsoleTab.PROBER)
                            },
                            onToggleTrackEntity = onToggleTrackEntity,
                            onClearTrackedEntity = onClearTrackedEntity,
                            onPinPath = { path, detail -> onPinExternalPath(path, "Step 3", detail) },
                            onCopyPayload = onCopyPayload,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        )
                    }

                    ConsoleTab.PROBER -> {
                        ProberTabContainer(
                            state = state,
                            onSelectSubTab = onSelectProberSubTab,
                            onCaptureSelectedTarget = onCaptureSelectedTarget,
                            onFilterSelectedTarget = onFilterSelectedTarget,
                            onLockSelectedTargetSlot = onLockSelectedTargetSlot,
                            onStopSelectedTargetWatch = onStopSelectedTargetWatch,
                            onResetSelectedTargetScan = onResetSelectedTargetScan,
                            onProbeSelectedTargetField = onProbeSelectedTargetField,
                            onAssignSelectedTargetToEntities = onAssignSelectedTargetToEntities,
                            onSelectStructProbeAnchor = onSelectStructProbeAnchor,
                            onSelectStructProbeField = onSelectStructProbeField,
                            onStructProbeInputChanged = onStructProbeInputChanged,
                            onRunStructProbe = onRunStructProbe,
                            onRunStructRefine = onRunStructRefine,
                            onRunAutoProbeNames = onRunAutoProbeNames,
                            onRunAggroStructProbe = onRunAggroStructProbe,
                            onBindStructCandidate = onBindStructCandidate,
                            onClearBoundStructField = onClearBoundStructField,
                            onClearAllBoundStructFields = onClearAllBoundStructFields,
                            onGoToCoords = { onSelectConsoleTab(ConsoleTab.COORDS) },
                            onWasmValueChanged = onWasmValueChanged,
                            onRunWasmScan = onRunWasmScan,
                            onRunWasmFilter = onRunWasmFilter,
                            onInspectWasmCandidate = onInspectWasmCandidate,
                            onStopWasmPoll = onStopWasmPoll,
                            onResetWasmTracker = onResetWasmTracker,
                            onExportWasmMemoryMap = onExportWasmMemoryMap,
                            onShareWasmMemoryMap = onShareWasmMemoryMap,
                            onCopyPayload = onCopyPayload,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        )
                    }

                    ConsoleTab.LOGS_ASSETS -> {
                        LogsAssetsTabContainer(
                            state = state,
                            onSelectSubTab = onSelectLogsAssetsSubTab,
                            onSelectFilter = onSelectFilter,
                            onExportLogDumpTxt = onExportLogDumpTxt,
                            onExportLogDumpJson = onExportLogDumpJson,
                            onShareLogDump = onShareLogDump,
                            onClearAssetCache = onClearAssetCache,
                            onTogglePinCandidate = onTogglePinCandidate,
                            onTogglePinnedOnly = onTogglePinnedOnly,
                            onStagingSearchQueryChanged = onStagingSearchQueryChanged,
                            onClearUnpinnedCandidates = onClearUnpinnedCandidates,
                            onLockCandidateForLivePolling = onLockCandidateForLivePolling,
                            onPauseBatchScan = onPauseStep1,
                            onResumeBatchScan = onResumeStep1,
                            onCopyPayload = onCopyPayload,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveHudTelemetryStrip(
    state: DiagnosticsUiState,
    isMiniHud: Boolean,
    onTapExpand: () -> Unit,
    onCopyPayload: (String) -> Unit
) {
    val hudCoords = state.livePlayerCoords
    val hudVitals = state.playerVitals?.takeIf { it.hasAnyContent }

    if (hudCoords == null && hudVitals == null && !isMiniHud) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (hudCoords != null || hudVitals != null) SignalEmerald.copy(alpha = 0.09f)
                else ObsidianSurfaceElevated
            )
            .clickable {
                if (isMiniHud) {
                    onTapExpand()
                } else {
                    val summary = buildString {
                        hudVitals?.playerName?.let { append("$it | ") }
                        hudCoords?.let {
                            append("X=${fmtCoord(it.x)}, Y=${fmtCoord(it.y)}, Z=${fmtCoord(it.z)}")
                        }
                        hudVitals?.let { v ->
                            v.hp?.let { append(" | HP=$it${v.effectiveMaxHp?.let { m -> "/$m" } ?: ""}") }
                            v.mp?.let { append(" MP=$it${v.effectiveMaxMp?.let { m -> "/$m" } ?: ""}") }
                            v.fp?.let { append(" FP=$it${v.effectiveMaxFp?.let { m -> "/$m" } ?: ""}") }
                        }
                    }
                    if (summary.isNotBlank()) onCopyPayload(summary)
                }
            }
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("live_coords_hud_strip"),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (hudCoords != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = null,
                    tint = SignalEmerald,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "X:${fmtCoord(hudCoords.x)}  Y:${fmtCoord(hudCoords.y)}  Z:${fmtCoord(hudCoords.z)}",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    color = SignalEmerald,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = hudVitals?.playerName?.takeIf { it.isNotBlank() } ?: hudCoords.path,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = if (!hudVitals?.playerName.isNullOrBlank()) FontWeight.Bold else FontWeight.Normal,
                    color = if (!hudVitals?.playerName.isNullOrBlank()) TelemetryViolet else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        } else if (isMiniHud) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (state.savedSiteProfiles.isNotEmpty()) {
                        "Mini HUD • ${state.savedSiteProfiles.sumOf { it.pointerCount }} saved pointer(s) ready"
                    } else {
                        "Mini HUD • Tap Split or Full to open tools"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = ElectricCyan,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        AnimatedVisibility(
            visible = hudVitals != null && (hudVitals.hp != null || hudVitals.mp != null || hudVitals.fp != null),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            hudVitals?.let { v ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("live_player_vitals_hud_row"),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    v.hp?.let { hpVal ->
                        VitalGaugePill(
                            label = "HP",
                            current = hpVal,
                            max = v.effectiveMaxHp,
                            accent = AlertCoral,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    v.mp?.let { mpVal ->
                        VitalGaugePill(
                            label = "MP",
                            current = mpVal,
                            max = v.effectiveMaxMp,
                            accent = ElectricCyan,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    v.fp?.let { fpVal ->
                        VitalGaugePill(
                            label = "FP",
                            current = fpVal,
                            max = v.effectiveMaxFp,
                            accent = AssetAmber,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
    HorizontalDivider(color = ObsidianBorder, thickness = 1.dp)
}

@Composable
private fun FourTabHeaderBar(
    state: DiagnosticsUiState,
    onSelectTab: (ConsoleTab) -> Unit,
    onToggleJsDrawer: () -> Unit,
    onClearLogs: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ObsidianSurfaceElevated)
    ) {
        // 4 Clean Equal-Width Tabs (minimum 48dp touch height, zero cramped scrolling)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PrimaryConsoleTabButton(
                label = stringResource(R.string.tab_coords),
                badgeCount = state.wasmFloatDelta.pointerChains.size.takeIf { it > 0 },
                icon = Icons.Default.MyLocation,
                selected = state.activeConsoleTab == ConsoleTab.COORDS,
                accent = SignalEmerald,
                testTag = "tab_coords_button",
                onClick = { onSelectTab(ConsoleTab.COORDS) },
                modifier = Modifier.weight(1f)
            )
            PrimaryConsoleTabButton(
                label = stringResource(R.string.tab_entities),
                badgeCount = state.discoveredEntities.size.takeIf { it > 0 },
                icon = Icons.Default.ViewInAr,
                selected = state.activeConsoleTab == ConsoleTab.ENTITIES,
                accent = TelemetryViolet,
                testTag = "tab_entities_button",
                onClick = { onSelectTab(ConsoleTab.ENTITIES) },
                modifier = Modifier.weight(1f)
            )
            PrimaryConsoleTabButton(
                label = stringResource(R.string.tab_prober),
                badgeCount = state.structProber.schema.boundCount.takeIf { it > 0 },
                icon = Icons.Default.Memory,
                selected = state.activeConsoleTab == ConsoleTab.PROBER,
                accent = ElectricCyan,
                testTag = "tab_prober_button",
                onClick = { onSelectTab(ConsoleTab.PROBER) },
                modifier = Modifier.weight(1f)
            )
            PrimaryConsoleTabButton(
                label = stringResource(R.string.tab_logs_assets),
                badgeCount = state.logs.size.takeIf { it > 0 },
                icon = Icons.Default.Terminal,
                selected = state.activeConsoleTab == ConsoleTab.LOGS_ASSETS,
                accent = AssetAmber,
                testTag = "tab_logs_assets_button",
                onClick = { onSelectTab(ConsoleTab.LOGS_ASSETS) },
                modifier = Modifier.weight(1f)
            )
        }

        // Slim Telemetry Status + Quick JS Drawer Utility Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ObsidianBg.copy(alpha = 0.65f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AsyncScanStatusPill(
                status = state.scanStatus,
                detail = state.scanStatusDetail,
                modifier = Modifier.weight(1f)
            )

            if (state.wasmFloatDelta.isWatchingPointers) {
                StatusMicroBadge(text = "PTR 200ms", accent = TelemetryViolet)
            }
            if (state.targetScanner.isWatchingTarget) {
                StatusMicroBadge(text = "TGT 200ms", accent = AssetAmber)
            }
            if (state.wasmFloatDelta.isLiveWatching) {
                StatusMicroBadge(text = "FLOAT 200ms", accent = SignalEmerald)
            }
            if (state.wasmFloatDelta.savedPointerCountForHost > 0) {
                StatusMicroBadge(text = "SAVED:${state.wasmFloatDelta.savedPointerCountForHost}", accent = AssetAmber)
            }

            // Manual JS Drawer Toggle
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (state.isJsDrawerOpen) ElectricCyan.copy(alpha = 0.22f) else ObsidianSurfaceHighest,
                border = BorderStroke(
                    1.dp,
                    if (state.isJsDrawerOpen) ElectricCyan else ObsidianBorder
                ),
                modifier = Modifier
                    .heightIn(min = 32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onToggleJsDrawer)
                    .testTag("toggle_js_drawer_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Code,
                        contentDescription = stringResource(R.string.cd_toggle_js_drawer),
                        tint = if (state.isJsDrawerOpen) ElectricCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "JS",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (state.isJsDrawerOpen) ElectricCyan else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            if (state.activeConsoleTab == ConsoleTab.LOGS_ASSETS && state.activeLogsAssetsSubTab == LogsAssetsSubTab.LOGS) {
                IconButton(
                    onClick = onClearLogs,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("clear_logs_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = stringResource(R.string.cd_clear_logs),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusMicroBadge(text: String, accent: Color) {
    Surface(
        color = accent.copy(alpha = 0.16f),
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.55f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            color = accent,
            fontSize = 9.sp,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun PrimaryConsoleTabButton(
    label: String,
    badgeCount: Int?,
    icon: ImageVector,
    selected: Boolean,
    accent: Color,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(9.dp),
        color = if (selected) accent.copy(alpha = 0.22f) else ObsidianBg,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) accent else ObsidianBorder
        ),
        modifier = modifier
            .heightIn(min = 48.dp)
            .testTag(testTag)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp)
                )
                if (badgeCount != null) {
                    Text(
                        text = if (badgeCount > 99) "99+" else "$badgeCount",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                }
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * TAB 1 — Coords (Single-Scroll):
 * 1. Startup Saved Sites & Pointers launcher (always visible when saved sites exist)
 * 2. Player Movement Coords Scanner + Locked Coords + Persistent Pointer Chains
 * 3. Inline Live Float Candidates (no bottom overlay stealing height!)
 * 4. Collapsible Advanced Scope / Manual Path / Full Report Export
 */
@Composable
private fun CoordsSingleScrollTab(
    state: DiagnosticsUiState,
    onOpenSavedSiteProfile: (SavedSiteProfile) -> Unit,
    onDeleteSavedSiteProfile: (String) -> Unit,
    onRunStep1: () -> Unit,
    onPauseStep1: () -> Unit,
    onResumeStep1: () -> Unit,
    onManualPathInputChanged: (String) -> Unit,
    onQueryManualPath: (String?) -> Unit,
    onRunStep2A: () -> Unit,
    onRunStep2B: () -> Unit,
    onSelectStep2ScanMode: (Step2ScanMode) -> Unit,
    onRunFloatBaseline: () -> Unit,
    onRunFloatDeltaScan: () -> Unit,
    onResetFloatDelta: () -> Unit,
    onToggleFloatWatcherExpanded: () -> Unit,
    onTogglePinFloatCandidate: (Int) -> Unit,
    onPinTopFloatTriplet: () -> Unit,
    onInspectFloatNeighbors: (Int) -> Unit,
    onStopFloatWatch: () -> Unit,
    onLockFloatCoords: (Int?) -> Unit,
    onUnlockFloatCoords: () -> Unit,
    onFindWasmPointers: () -> Unit,
    onVerifyWasmPointers: () -> Unit,
    onToggleWatchWasmPointers: () -> Unit,
    onStreamPointerToHud: (String) -> Unit,
    onPruneWasmPointers: () -> Unit,
    onTogglePinPointerChain: (String) -> Unit,
    onExportWasmMemoryMap: () -> Unit,
    onCopyPayload: (String) -> Unit,
    onRunStep3: () -> Unit,
    onExportReport: () -> Unit,
    onShareReport: () -> Unit,
    onOpenStaging: () -> Unit,
    onOpenEntities: () -> Unit,
    modifier: Modifier = Modifier
) {
    var advancedExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .background(ObsidianBg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Saved Sites & Bookmarked Pointers (immediately visible on cold startup!)
        if (state.savedSiteProfiles.isNotEmpty()) {
            SavedSitesStartupCard(
                profiles = state.savedSiteProfiles,
                activeUrl = state.activeUrl,
                onOpenProfile = onOpenSavedSiteProfile,
                onDeleteProfile = onDeleteSavedSiteProfile
            )
        }

        // 2. Player Coords & Persistent Pointers Card
        WizardStepCard(
            title = stringResource(R.string.wizard_step_2_title),
            badge = state.step2Badge,
            accent = SignalEmerald
        ) {
            Step2ModeSelectorRow(
                selectedMode = state.step2ScanMode,
                onSelectMode = onSelectStep2ScanMode
            )

            if (state.step2ScanMode == Step2ScanMode.WASM_FLOAT_HEAP) {
                Step2FloatModeControls(
                    floatState = state.wasmFloatDelta,
                    onCaptureFloatBaseline = onRunFloatBaseline,
                    onScanFloatDeltas = onRunFloatDeltaScan,
                    onResetFloatDelta = onResetFloatDelta,
                    onLockCoords = { onLockFloatCoords(null) },
                    onUnlockCoords = onUnlockFloatCoords,
                    onFindPointers = onFindWasmPointers,
                    onVerifyPointers = onVerifyWasmPointers,
                    onToggleWatchPointers = onToggleWatchWasmPointers,
                    onStreamPointerToHud = onStreamPointerToHud,
                    onPrunePointers = onPruneWasmPointers,
                    onTogglePinPointerChain = onTogglePinPointerChain,
                    onExportMemoryMap = onExportWasmMemoryMap,
                    onCopyText = onCopyPayload
                )
            } else {
                StepActionButton(
                    label = stringResource(R.string.step_2a_capture_baseline),
                    icon = Icons.Default.TrackChanges,
                    accent = SignalEmerald,
                    enabled = true,
                    filled = state.baselineCaptured,
                    testTag = "step_2a_baseline_button",
                    onClick = onRunStep2A
                )
                Text(
                    text = stringResource(R.string.wizard_step_2a_instruction),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(2.dp))

                StepActionButton(
                    label = stringResource(R.string.step_2b_compute_delta),
                    icon = Icons.Default.Timeline,
                    accent = SignalEmerald,
                    enabled = true,
                    filled = state.livePlayerCoords != null,
                    testTag = "step_2b_delta_button",
                    onClick = onRunStep2B
                )
                Text(
                    text = stringResource(R.string.wizard_step_2b_instruction),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                state.livePlayerCoords?.let { coords ->
                    LivePlayerCoordsReadout(
                        coords = coords,
                        propertyKeys = state.playerPropertyKeys,
                        tickCount = state.livePollTickCount,
                        isLive = state.livePollActive
                    )
                }
            }
        }

        // 3. Inline Live Float Candidates (flows naturally inside single-scroll Coords tab)
        if (state.wasmFloatDelta.candidates.isNotEmpty()) {
            InlineLiveFloatCandidatesCard(
                floatState = state.wasmFloatDelta,
                onToggleExpanded = onToggleFloatWatcherExpanded,
                onTogglePinCandidate = onTogglePinFloatCandidate,
                onPinTopTriplet = onPinTopFloatTriplet,
                onLockCoords = onLockFloatCoords,
                onFindPointers = onFindWasmPointers,
                onInspectNeighbors = onInspectFloatNeighbors,
                onStopWatch = onStopFloatWatch,
                onCopyText = onCopyPayload
            )
        }

        // 4. Collapsible Advanced Scope, Manual Path Inspector, Step 3 Shortcut & Report Export
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = ObsidianSurface,
            border = BorderStroke(1.dp, ObsidianBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { advancedExpanded = !advancedExpanded }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .testTag("toggle_advanced_tools_button"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.advanced_legacy_tools_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = if (advancedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                AnimatedVisibility(
                    visible = advancedExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        HorizontalDivider(color = ObsidianBorder, thickness = 1.dp)

                        // Step 1 — JS Scope & Manual Property Inspector
                        StepActionButton(
                            label = stringResource(R.string.step_1_inspect_scope),
                            icon = Icons.Default.Radar,
                            accent = ElectricCyan,
                            enabled = true,
                            isBusy = state.scopeBatchProgress.isActive && !state.scopeBatchProgress.isPaused,
                            testTag = "step_1_inspect_scope_button",
                            onClick = onRunStep1
                        )
                        if (state.scopeBatchProgress.isActive || state.scopeBatchProgress.isPaused) {
                            BatchScanProgressBanner(
                                progress = state.scopeBatchProgress,
                                onPause = onPauseStep1,
                                onResume = onResumeStep1,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (state.detectedGameNamespaces.isNotEmpty()) {
                            CapturedSnippetBox(
                                accent = SignalEmerald,
                                text = "Auto-detected roots: ${state.detectedGameNamespaces.joinToString(", ")}",
                                actionLabel = "View in Staging (${state.stagingBuffer.size}) ›",
                                onActionClick = onOpenStaging
                            )
                        }
                        Step1ManualPropertyInspectorBox(
                            pathInput = state.manualPropertyPathInput,
                            lastResultSummary = state.lastManualQuerySummary,
                            onPathInputChanged = onManualPathInputChanged,
                            onQueryPath = onQueryManualPath,
                            onOpenStaging = onOpenStaging
                        )

                        // Step 3 — Quick Entity Scan button
                        val canRunStep3 = !state.discoveredPlayerPath.isNullOrBlank() || state.hasLiveWasmPlayerAnchor
                        StepActionButton(
                            label = stringResource(R.string.step_3_scan_entities),
                            icon = Icons.Default.ViewInAr,
                            accent = TelemetryViolet,
                            enabled = canRunStep3,
                            filled = state.discoveredEntities.isNotEmpty(),
                            testTag = "step_3_entities_button",
                            onClick = {
                                onRunStep3()
                                onOpenEntities()
                            }
                        )

                        // Step 4 — Export Handoff Report (.json)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StepActionButton(
                                label = stringResource(R.string.step_4_export_report),
                                icon = Icons.Default.Download,
                                accent = AssetAmber,
                                enabled = !state.isExporting,
                                isBusy = state.isExporting,
                                filled = state.step4Badge == WizardStepBadge.READY || state.step4Badge == WizardStepBadge.CAPTURED,
                                testTag = "step_5_export_report_button",
                                onClick = onExportReport,
                                modifier = Modifier.weight(1f)
                            )
                            if (state.lastExport?.success == true && state.lastExport.contentUri != null) {
                                OutlinedButton(
                                    onClick = onShareReport,
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, SignalEmerald),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("share_report_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = stringResource(R.string.cd_share_report),
                                        tint = SignalEmerald,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = stringResource(R.string.action_share_report),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = SignalEmerald
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}

/**
 * TAB 3 — Prober (Dedicated Full-Height Workspace):
 * 3-mode segmented toggle between Selected Target Finder, Entity Struct Prober (HP/MP/FP/Name/Aggro),
 * and Full-Heap Value Tracker.
 */
@Composable
private fun ProberTabContainer(
    state: DiagnosticsUiState,
    onSelectSubTab: (ProberSubTab) -> Unit,
    onCaptureSelectedTarget: () -> Unit,
    onFilterSelectedTarget: (TargetScanFilterMode) -> Unit,
    onLockSelectedTargetSlot: (Long) -> Unit,
    onStopSelectedTargetWatch: () -> Unit,
    onResetSelectedTargetScan: () -> Unit,
    onProbeSelectedTargetField: (WasmStructFieldKind) -> Unit,
    onAssignSelectedTargetToEntities: () -> Unit,
    onSelectStructProbeAnchor: (WasmStructProbeAnchor) -> Unit,
    onSelectStructProbeField: (WasmStructFieldKind) -> Unit,
    onStructProbeInputChanged: (String) -> Unit,
    onRunStructProbe: () -> Unit,
    onRunStructRefine: () -> Unit,
    onRunAutoProbeNames: () -> Unit,
    onRunAggroStructProbe: (isAggroedNow: Boolean, refineExisting: Boolean) -> Unit,
    onBindStructCandidate: (WasmStructProbeCandidate) -> Unit,
    onClearBoundStructField: (WasmStructFieldKind) -> Unit,
    onClearAllBoundStructFields: () -> Unit,
    onGoToCoords: () -> Unit,
    onWasmValueChanged: (String) -> Unit,
    onRunWasmScan: () -> Unit,
    onRunWasmFilter: () -> Unit,
    onInspectWasmCandidate: (heapType: String, index: Int) -> Unit,
    onStopWasmPoll: () -> Unit,
    onResetWasmTracker: () -> Unit,
    onExportWasmMemoryMap: () -> Unit,
    onShareWasmMemoryMap: () -> Unit,
    onCopyPayload: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.background(ObsidianBg)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ObsidianSurface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val targetLabel = if (state.targetScanner.liveTarget?.hasTarget == true) {
                "${stringResource(R.string.subtab_target_finder)} •"
            } else if (state.targetScanner.totalCandidates > 0) {
                "${stringResource(R.string.subtab_target_finder)} (${state.targetScanner.totalCandidates})"
            } else {
                stringResource(R.string.subtab_target_finder)
            }
            ConsoleSubTabSegmentPill(
                label = targetLabel,
                selected = state.activeProberSubTab == ProberSubTab.TARGET_FINDER,
                accent = AssetAmber,
                testTag = "prober_subtab_target",
                onClick = { onSelectSubTab(ProberSubTab.TARGET_FINDER) },
                modifier = Modifier.weight(1f)
            )
            ConsoleSubTabSegmentPill(
                label = stringResource(R.string.subtab_struct_prober),
                selected = state.activeProberSubTab == ProberSubTab.STRUCT_PROBER,
                accent = TelemetryViolet,
                testTag = "prober_subtab_struct",
                onClick = { onSelectSubTab(ProberSubTab.STRUCT_PROBER) },
                modifier = Modifier.weight(1f)
            )
            val heapLabel = if (state.wasmHeap.totalMatches > 0) {
                "${stringResource(R.string.subtab_full_heap)} (${state.wasmHeap.totalMatches})"
            } else {
                stringResource(R.string.subtab_full_heap)
            }
            ConsoleSubTabSegmentPill(
                label = heapLabel,
                selected = state.activeProberSubTab == ProberSubTab.FULL_HEAP,
                accent = SignalEmerald,
                testTag = "prober_subtab_full_heap",
                onClick = { onSelectSubTab(ProberSubTab.FULL_HEAP) },
                modifier = Modifier.weight(1f)
            )
        }
        HorizontalDivider(color = ObsidianBorder, thickness = 1.dp)

        when (state.activeProberSubTab) {
            ProberSubTab.TARGET_FINDER -> {
                TargetScannerWorkspace(
                    targetState = state.targetScanner,
                    onCaptureMobA = onCaptureSelectedTarget,
                    onFilterTarget = onFilterSelectedTarget,
                    onLockTargetSlot = onLockSelectedTargetSlot,
                    onStopTargetWatch = onStopSelectedTargetWatch,
                    onResetTargetScan = onResetSelectedTargetScan,
                    onProbeTargetField = onProbeSelectedTargetField,
                    onAssignTargetToEntities = onAssignSelectedTargetToEntities,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }

            ProberSubTab.STRUCT_PROBER -> {
                val liveTarget = state.targetScanner.liveTarget
                val hasSelectedTargetAnchor =
                    liveTarget != null && liveTarget.hasTarget && liveTarget.effectiveAnchorAddress > 0L
                val selectedTargetLabel = liveTarget?.takeIf { it.hasTarget }?.let { t ->
                    t.resolvedName?.takeIf { it.isNotBlank() }
                        ?: t.matchedEntityId?.takeIf { it.isNotBlank() }
                        ?: "0x${t.effectiveAnchorAddress.toString(16).uppercase().padStart(8, '0')}"
                }
                StructProberWorkspace(
                    prober = state.structProber,
                    hasPlayerAnchor = state.hasLiveWasmPlayerAnchor,
                    hasSelectedTargetAnchor = hasSelectedTargetAnchor,
                    selectedTargetLabel = selectedTargetLabel,
                    trackedMobCount = state.discoveredEntities.size,
                    onSelectAnchor = onSelectStructProbeAnchor,
                    onSelectField = onSelectStructProbeField,
                    onInputChanged = onStructProbeInputChanged,
                    onRunProbe = onRunStructProbe,
                    onRunRefine = onRunStructRefine,
                    onRunAutoProbeNames = onRunAutoProbeNames,
                    onRunAggroProbe = onRunAggroStructProbe,
                    onBindCandidate = onBindStructCandidate,
                    onClearField = onClearBoundStructField,
                    onClearAllFields = onClearAllBoundStructFields,
                    onGoToCoords = onGoToCoords,
                    onGoToTargetFinder = { onSelectSubTab(ProberSubTab.TARGET_FINDER) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }

            ProberSubTab.FULL_HEAP -> {
                WasmHeapTrackerView(
                    state = state,
                    onValueInputChanged = onWasmValueChanged,
                    onRunInitialScan = onRunWasmScan,
                    onRunFilterDelta = onRunWasmFilter,
                    onInspectCandidate = onInspectWasmCandidate,
                    onStopNeighborPoll = onStopWasmPoll,
                    onResetTracker = onResetWasmTracker,
                    onExportMemoryMap = onExportWasmMemoryMap,
                    onShareMemoryMap = onShareWasmMemoryMap,
                    onCopyText = onCopyPayload,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }
        }
    }
}

/**
 * TAB 4 — Logs / Assets / Staging:
 * 3-segment switcher consolidating Logs, Binary Assets cache, and Candidate Staging Buffer.
 */
@Composable
private fun LogsAssetsTabContainer(
    state: DiagnosticsUiState,
    onSelectSubTab: (LogsAssetsSubTab) -> Unit,
    onSelectFilter: (LogCategory) -> Unit,
    onExportLogDumpTxt: () -> Unit,
    onExportLogDumpJson: () -> Unit,
    onShareLogDump: () -> Unit,
    onClearAssetCache: () -> Unit,
    onTogglePinCandidate: (String) -> Unit,
    onTogglePinnedOnly: () -> Unit,
    onStagingSearchQueryChanged: (String) -> Unit,
    onClearUnpinnedCandidates: () -> Unit,
    onLockCandidateForLivePolling: (String) -> Unit,
    onPauseBatchScan: () -> Unit,
    onResumeBatchScan: () -> Unit,
    onCopyPayload: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.background(ObsidianBg)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ObsidianSurface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ConsoleSubTabSegmentPill(
                label = "${stringResource(R.string.tab_logs)} (${state.logs.size})",
                selected = state.activeLogsAssetsSubTab == LogsAssetsSubTab.LOGS,
                accent = SignalEmerald,
                testTag = "subtab_logs_button",
                onClick = { onSelectSubTab(LogsAssetsSubTab.LOGS) },
                modifier = Modifier.weight(1f)
            )
            ConsoleSubTabSegmentPill(
                label = "${stringResource(R.string.tab_assets)} (${state.interceptedAssets.size})",
                selected = state.activeLogsAssetsSubTab == LogsAssetsSubTab.ASSETS,
                accent = ElectricCyan,
                testTag = "subtab_assets_button",
                onClick = { onSelectSubTab(LogsAssetsSubTab.ASSETS) },
                modifier = Modifier.weight(1f)
            )
            ConsoleSubTabSegmentPill(
                label = "${stringResource(R.string.tab_staging)} (${state.stagingBuffer.size})",
                selected = state.activeLogsAssetsSubTab == LogsAssetsSubTab.STAGING,
                accent = AssetAmber,
                testTag = "subtab_staging_button",
                onClick = { onSelectSubTab(LogsAssetsSubTab.STAGING) },
                modifier = Modifier.weight(1f)
            )
        }
        HorizontalDivider(color = ObsidianBorder, thickness = 1.dp)

        when (state.activeLogsAssetsSubTab) {
            LogsAssetsSubTab.LOGS -> {
                LiveLogConsoleView(
                    state = state,
                    onSelectFilter = onSelectFilter,
                    onExportLogDumpTxt = onExportLogDumpTxt,
                    onExportLogDumpJson = onExportLogDumpJson,
                    onShareLogDump = onShareLogDump,
                    onCopyPayload = onCopyPayload,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }

            LogsAssetsSubTab.ASSETS -> {
                AssetMetadataInspectorView(
                    state = state,
                    onClearAssetCache = onClearAssetCache,
                    onExportAssetDumpJson = onExportLogDumpJson,
                    onCopyMetadata = onCopyPayload,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }

            LogsAssetsSubTab.STAGING -> {
                StagingBufferView(
                    state = state,
                    onTogglePin = onTogglePinCandidate,
                    onTogglePinnedOnly = onTogglePinnedOnly,
                    onSearchQueryChanged = onStagingSearchQueryChanged,
                    onClearUnpinned = onClearUnpinnedCandidates,
                    onTrackLive = onLockCandidateForLivePolling,
                    onPauseBatchScan = onPauseBatchScan,
                    onResumeBatchScan = onResumeBatchScan,
                    onCopyPath = onCopyPayload,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun WizardStepCard(
    title: String,
    badge: WizardStepBadge,
    accent: Color,
    content: @Composable () -> Unit
) {
    val borderColor = when (badge) {
        WizardStepBadge.CAPTURED -> SignalEmerald.copy(alpha = 0.55f)
        WizardStepBadge.READY -> accent.copy(alpha = 0.45f)
        WizardStepBadge.PENDING -> ObsidianBorder
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = ObsidianSurface,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                StepStatusBadgeChip(badge = badge)
            }
            content()
        }
    }
}

@Composable
private fun StepStatusBadgeChip(badge: WizardStepBadge) {
    val (bg, fg) = when (badge) {
        WizardStepBadge.CAPTURED -> SignalEmerald.copy(alpha = 0.18f) to SignalEmerald
        WizardStepBadge.READY -> ElectricCyan.copy(alpha = 0.18f) to ElectricCyan
        WizardStepBadge.PENDING -> ObsidianSurfaceElevated to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = bg,
        shape = RoundedCornerShape(5.dp),
        border = BorderStroke(1.dp, fg.copy(alpha = 0.45f)),
        modifier = Modifier.testTag("badge_${badge.name.lowercase()}")
    ) {
        Text(
            text = badge.displayText,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = fg,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun StepActionButton(
    label: String,
    icon: ImageVector,
    accent: Color,
    enabled: Boolean,
    testTag: String,
    filled: Boolean = false,
    isBusy: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (filled) accent else accent.copy(alpha = 0.18f),
            contentColor = if (filled) ObsidianBg else MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = ObsidianSurfaceElevated,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        ),
        border = BorderStroke(
            1.dp,
            if (enabled) accent.copy(alpha = if (filled) 1f else 0.65f) else ObsidianBorder
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag(testTag)
    ) {
        if (isBusy) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = if (filled) ObsidianBg else accent
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                    filled -> ObsidianBg
                    else -> accent
                },
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LivePlayerCoordsReadout(
    coords: PlayerCoordinates,
    propertyKeys: String,
    tickCount: Int,
    isLive: Boolean
) {
    Surface(
        color = SignalEmerald.copy(alpha = 0.12f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, SignalEmerald.copy(alpha = 0.55f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("live_player_coords_readout")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = null,
                    tint = SignalEmerald,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = if (isLive) "LIVE 200ms (#$tickCount)" else "PLAYER LOCK",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = SignalEmerald
                )
                Text(
                    text = coords.path + if (propertyKeys.isNotBlank()) " [$propertyKeys]" else "",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CoordAxisTile(axis = "X", value = coords.x, modifier = Modifier.weight(1f))
                CoordAxisTile(axis = "Y", value = coords.y, modifier = Modifier.weight(1f))
                CoordAxisTile(axis = "Z", value = coords.z, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CoordAxisTile(
    axis: String,
    value: Double,
    modifier: Modifier = Modifier
) {
    Surface(
        color = ObsidianBg,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, SignalEmerald.copy(alpha = 0.35f)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = axis,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = SignalEmerald
            )
            Text(
                text = fmtCoord(value),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun CapturedSnippetBox(
    accent: Color,
    text: String,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(ObsidianBg)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            color = accent,
            lineHeight = 15.sp
        )
        if (actionLabel != null && onActionClick != null) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = onActionClick)
                    .padding(vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun Step1ManualPropertyInspectorBox(
    pathInput: String,
    lastResultSummary: String,
    onPathInputChanged: (String) -> Unit,
    onQueryPath: (String?) -> Unit,
    onOpenStaging: () -> Unit
) {
    val quickRoots = remember {
        listOf("game.player", "window.game", "window.app", "window.Module", "window.PIXI")
    }

    Surface(
        color = ObsidianBg,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("step_1_manual_property_inspector")
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = ElectricCyan,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = stringResource(R.string.step_1_manual_query_title),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = ElectricCyan
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = pathInput,
                    onValueChange = onPathInputChanged,
                    singleLine = true,
                    placeholder = {
                        Text(
                            text = stringResource(R.string.step_1_manual_query_hint),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onQueryPath(null) }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ElectricCyan,
                        unfocusedBorderColor = ObsidianBorder,
                        focusedContainerColor = ObsidianSurface,
                        unfocusedContainerColor = ObsidianSurface
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("manual_path_query_input")
                )

                Button(
                    onClick = { onQueryPath(null) },
                    enabled = pathInput.isNotBlank(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricCyan,
                        contentColor = ObsidianBg,
                        disabledContainerColor = ObsidianSurfaceElevated,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("manual_path_query_button")
                ) {
                    Text(
                        text = stringResource(R.string.action_query_path),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                quickRoots.forEach { samplePath ->
                    Surface(
                        onClick = { onQueryPath(samplePath) },
                        shape = RoundedCornerShape(6.dp),
                        color = ObsidianSurfaceElevated,
                        border = BorderStroke(1.dp, ObsidianBorder)
                    ) {
                        Text(
                            text = samplePath,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            if (lastResultSummary.isNotBlank()) {
                val isFound = lastResultSummary.startsWith("Staged")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = lastResultSummary,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = if (isFound) SignalEmerald else AssetAmber,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (isFound) {
                        Text(
                            text = "Staging ›",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = ElectricCyan,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable(onClick = onOpenStaging)
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualJsExecutionDrawer(
    script: String,
    onScriptChanged: (String) -> Unit,
    onExecute: () -> Unit,
    onClose: () -> Unit
) {
    val quickProbes = remember {
        listOf(
            "Live ref" to "window.__wvDiagState && window.__wvDiagState.liveRef ? ({path: window.__wvDiagState.liveRef.path, keys: [window.__wvDiagState.liveRef.xKey, window.__wvDiagState.liveRef.yKey, window.__wvDiagState.liveRef.zKey]}) : null",
            "Entity array" to "window.__wvDiagState ? window.__wvDiagState.discoveredEntityArrayPath : null",
            "Custom globals" to "window.__wvDiagState ? window.__wvDiagState.customGlobals : []",
            "Viewport" to "({w: innerWidth, h: innerHeight, dpr: devicePixelRatio})"
        )
    }

    Surface(
        color = ObsidianSurfaceElevated,
        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.45f)),
        shape = RoundedCornerShape(9.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("manual_js_drawer")
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Code,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Manual JavaScript Evaluator",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = ElectricCyan
                    )
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("close_js_drawer_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.cd_close_drawer),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                quickProbes.forEach { (title, snippet) ->
                    Surface(
                        onClick = { onScriptChanged(snippet) },
                        shape = RoundedCornerShape(6.dp),
                        color = ObsidianBg,
                        border = BorderStroke(1.dp, ObsidianBorder)
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = script,
                    onValueChange = onScriptChanged,
                    placeholder = {
                        Text(
                            text = stringResource(R.string.js_input_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onExecute() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ElectricCyan,
                        unfocusedBorderColor = ObsidianBorder,
                        focusedContainerColor = ObsidianBg,
                        unfocusedContainerColor = ObsidianBg
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("manual_js_input")
                )

                Button(
                    onClick = onExecute,
                    enabled = script.isNotBlank(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricCyan,
                        contentColor = ObsidianBg
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("execute_script_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.action_execute_script),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun AsyncScanStatusPill(
    status: AsyncScanStatus,
    detail: String,
    modifier: Modifier = Modifier
) {
    val accent = when (status) {
        AsyncScanStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
        AsyncScanStatus.RUNNING -> ElectricCyan
        AsyncScanStatus.PAUSED -> AssetAmber
        AsyncScanStatus.COMPLETED -> SignalEmerald
        AsyncScanStatus.ERROR -> AlertCoral
    }
    val statusLabel = when (status) {
        AsyncScanStatus.IDLE -> stringResource(R.string.scan_status_idle)
        AsyncScanStatus.RUNNING -> stringResource(R.string.scan_status_running)
        AsyncScanStatus.PAUSED -> stringResource(R.string.scan_status_paused)
        AsyncScanStatus.COMPLETED -> stringResource(R.string.scan_status_completed)
        AsyncScanStatus.ERROR -> stringResource(R.string.scan_status_error)
    }
    val displayText = if (detail.isNotBlank() && status != AsyncScanStatus.IDLE) {
        detail
    } else {
        "Scan: $statusLabel"
    }

    Row(
        modifier = modifier.testTag("async_scan_status_pill"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (status == AsyncScanStatus.RUNNING) {
            CircularProgressIndicator(
                modifier = Modifier.size(10.dp),
                strokeWidth = 1.6.dp,
                color = accent
            )
        } else {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
        }
        Text(
            text = displayText,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun fmtCoord(value: Double): String = String.format(Locale.US, "%.2f", value)
