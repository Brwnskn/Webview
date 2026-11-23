package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.DiagnosticsViewModel
import com.example.LockedWasmCoords
import com.example.R
import com.example.Step2ScanMode
import com.example.WasmFloatDeltaCandidate
import com.example.WasmFloatDeltaState
import com.example.WasmPointerChain
import com.example.WasmPointerStatus
import com.example.ui.theme.AlertCoral
import com.example.ui.theme.AssetAmber
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceElevated
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.TelemetryViolet
import java.util.Locale

/**
 * Compact 2-pill mode switcher inside Step 2 allowing instant switching between
 * WASM Float Heap Delta Mode (`window.HEAPF32`) and JS Objects Delta Mode.
 */
@Composable
fun Step2ModeSelectorRow(
    selectedMode: Step2ScanMode,
    onSelectMode: (Step2ScanMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Step2ModePill(
            label = stringResource(R.string.step2_mode_wasm_float),
            selected = selectedMode == Step2ScanMode.WASM_FLOAT_HEAP,
            accent = ElectricCyan,
            testTag = "step2_mode_wasm_float_button",
            onClick = { onSelectMode(Step2ScanMode.WASM_FLOAT_HEAP) },
            modifier = Modifier.weight(1f)
        )
        Step2ModePill(
            label = stringResource(R.string.step2_mode_js),
            selected = selectedMode == Step2ScanMode.JS_OBJECTS,
            accent = SignalEmerald,
            testTag = "step2_mode_js_button",
            onClick = { onSelectMode(Step2ScanMode.JS_OBJECTS) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun Step2ModePill(
    label: String,
    selected: Boolean,
    accent: Color,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(7.dp),
        color = if (selected) accent.copy(alpha = 0.2f) else ObsidianBg,
        border = BorderStroke(
            1.dp,
            if (selected) accent else ObsidianBorder
        ),
        modifier = modifier
            .heightIn(min = 38.dp)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
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
 * Step 2A & 2B controls for WebAssembly Float Heap Delta Mode (`window.HEAPF32`).
 */
@Composable
fun Step2FloatModeControls(
    floatState: WasmFloatDeltaState,
    onCaptureFloatBaseline: () -> Unit,
    onScanFloatDeltas: () -> Unit,
    onResetFloatDelta: () -> Unit,
    onLockCoords: () -> Unit,
    onUnlockCoords: () -> Unit,
    onFindPointers: () -> Unit,
    onVerifyPointers: () -> Unit,
    onToggleWatchPointers: () -> Unit,
    onStreamPointerToHud: (String) -> Unit,
    onPrunePointers: () -> Unit,
    onTogglePinPointerChain: (String) -> Unit,
    onExportMemoryMap: () -> Unit,
    onCopyText: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Step 2A — Capture Float Baseline Snapshot
        Button(
            onClick = onCaptureFloatBaseline,
            enabled = !floatState.isSamplingBaseline && !floatState.isScanningDeltas,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (floatState.baselineCaptured) ElectricCyan else ElectricCyan.copy(alpha = 0.18f),
                contentColor = if (floatState.baselineCaptured) ObsidianBg else MaterialTheme.colorScheme.onSurface,
                disabledContainerColor = ObsidianSurfaceElevated,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
            ),
            border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.8f)),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("step_2a_float_baseline_button")
        ) {
            if (floatState.isSamplingBaseline) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = ElectricCyan
                )
            } else {
                Icon(
                    imageVector = Icons.Default.TrackChanges,
                    contentDescription = null,
                    tint = if (floatState.baselineCaptured) ObsidianBg else ElectricCyan,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.step_2a_float_baseline),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Step 2B — Scan Float Heap Deltas + Reset
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onScanFloatDeltas,
                enabled = !floatState.isSamplingBaseline && !floatState.isScanningDeltas && floatState.baselineCaptured,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (floatState.candidates.isNotEmpty()) SignalEmerald else SignalEmerald.copy(alpha = 0.22f),
                    contentColor = if (floatState.candidates.isNotEmpty()) ObsidianBg else MaterialTheme.colorScheme.onSurface,
                    disabledContainerColor = ObsidianSurfaceElevated,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                ),
                border = BorderStroke(
                    1.dp,
                    if (floatState.baselineCaptured) SignalEmerald else ObsidianBorder
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("step_2b_scan_float_deltas_button")
            ) {
                if (floatState.isScanningDeltas) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = SignalEmerald
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Timeline,
                        contentDescription = null,
                        tint = when {
                            !floatState.baselineCaptured -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                            floatState.candidates.isNotEmpty() -> ObsidianBg
                            else -> SignalEmerald
                        },
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.step_2b_scan_float_deltas),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (floatState.baselineCaptured || floatState.candidates.isNotEmpty()) {
                Surface(
                    onClick = onResetFloatDelta,
                    shape = RoundedCornerShape(8.dp),
                    color = ObsidianBg,
                    border = BorderStroke(1.dp, ObsidianBorder),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("step_2_float_reset_button")
                ) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.wasm_action_reset),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // Plain-English Instruction
        Text(
            text = stringResource(R.string.wizard_step_2_float_instruction),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Compact Float Baseline & Match Readout Box
        if (floatState.baselineCaptured || floatState.statusMessage.isNotBlank()) {
            Surface(
                color = ObsidianBg,
                shape = RoundedCornerShape(7.dp),
                border = BorderStroke(
                    1.dp,
                    if (floatState.candidates.isNotEmpty()) SignalEmerald.copy(alpha = 0.55f) else ElectricCyan.copy(alpha = 0.45f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    if (floatState.baselineCaptured) {
                        val source = floatState.heapSourceLabel.ifBlank { "window.HEAPF32" }
                        Text(
                            text = "$source • ${floatState.baselineSampleCount} stable floats (pruned ${floatState.baselinePrunedJitterCount} timers)" +
                                if (floatState.scanRound > 0) " • Round #${floatState.scanRound}: ${floatState.totalDeltaMatches} moved" else "",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = if (floatState.candidates.isNotEmpty()) SignalEmerald else ElectricCyan,
                            fontSize = 10.sp
                        )
                    }
                    if (floatState.statusMessage.isNotBlank()) {
                        Text(
                            text = floatState.statusMessage,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 14.sp
                        )
                    }
                }
            }
        }

        // Quick "Lock Coords" button as soon as movement candidates are found (and not locked yet)
        if (floatState.candidates.isNotEmpty() && floatState.lockedCoords == null) {
            Button(
                onClick = onLockCoords,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AssetAmber,
                    contentColor = ObsidianBg
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("step_2_lock_coords_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.action_lock_coords),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Locked WASM Coordinates Readout + Persistent Pointer Scan Action
        floatState.lockedCoords?.let { locked ->
            LockedWasmCoordsCard(
                locked = locked,
                isScanningPointers = floatState.isScanningPointers,
                onFindPointers = onFindPointers,
                onUnlock = onUnlockCoords,
                onCopyText = onCopyText
            )
        }

        // Persistent Pointer Chains Results Card (L1 & L2 HEAPU32 pointer chains + 200ms Live Watch)
        if (floatState.pointerChains.isNotEmpty() || floatState.pointerStatusMessage.isNotBlank() || floatState.isScanningPointers || floatState.isAutoVerifyingOnReload) {
            PersistentPointerChainsCard(
                floatState = floatState,
                onVerifyPointers = onVerifyPointers,
                onToggleWatchPointers = onToggleWatchPointers,
                onStreamPointerToHud = onStreamPointerToHud,
                onPrunePointers = onPrunePointers,
                onTogglePinChain = onTogglePinPointerChain,
                onExportMemoryMap = onExportMemoryMap,
                onCopyText = onCopyText
            )
        }
    }
}

@Composable
private fun LockedWasmCoordsCard(
    locked: LockedWasmCoords,
    isScanningPointers: Boolean,
    onFindPointers: () -> Unit,
    onUnlock: () -> Unit,
    onCopyText: (String) -> Unit
) {
    Surface(
        color = AssetAmber.copy(alpha = 0.11f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, AssetAmber.copy(alpha = 0.7f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("locked_wasm_coords_card")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = AssetAmber,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = stringResource(R.string.wasm_locked_coords_title),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = AssetAmber,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Surface(
                    color = SignalEmerald.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, SignalEmerald.copy(alpha = 0.6f))
                ) {
                    Text(
                        text = if (locked.isLivePolling) "LOCKED ✓ #${locked.liveTickCount}" else "LOCKED ✓",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = SignalEmerald,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
            }

            // Live X / Y / Z Locked Readout Tiles
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                LockedAxisTile(
                    axis = "X",
                    heapIndex = locked.xIndex,
                    byteAddress = locked.xByteAddress,
                    value = locked.xValue,
                    onClick = {
                        onCopyText("X: HEAPF32[${locked.xIndex}] (${DiagnosticsViewModel.formatHexAddress(locked.xByteAddress)}) = ${fmtDec(locked.xValue)}")
                    },
                    modifier = Modifier.weight(1f)
                )
                locked.yIndex?.let { yIdx ->
                    LockedAxisTile(
                        axis = "Y",
                        heapIndex = yIdx,
                        byteAddress = locked.yByteAddress ?: (yIdx.toLong() * 4L),
                        value = locked.yValue,
                        onClick = {
                            onCopyText("Y: HEAPF32[$yIdx] (${DiagnosticsViewModel.formatHexAddress(locked.yByteAddress ?: (yIdx.toLong() * 4L))}) = ${fmtDec(locked.yValue)}")
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                locked.zIndex?.let { zIdx ->
                    LockedAxisTile(
                        axis = "Z",
                        heapIndex = zIdx,
                        byteAddress = locked.zByteAddress ?: (zIdx.toLong() * 4L),
                        value = locked.zValue,
                        onClick = {
                            onCopyText("Z: HEAPF32[$zIdx] (${DiagnosticsViewModel.formatHexAddress(locked.zByteAddress ?: (zIdx.toLong() * 4L))}) = ${fmtDec(locked.zValue)}")
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Find Pointers + Unlock Action Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onFindPointers,
                    enabled = !isScanningPointers,
                    shape = RoundedCornerShape(7.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TelemetryViolet,
                        contentColor = ObsidianBg,
                        disabledContainerColor = ObsidianSurfaceElevated,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("step_2_find_pointers_button")
                ) {
                    if (isScanningPointers) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = TelemetryViolet
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.AccountTree,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.action_find_pointers),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Surface(
                    onClick = onUnlock,
                    shape = RoundedCornerShape(7.dp),
                    color = ObsidianBg,
                    border = BorderStroke(1.dp, ObsidianBorder),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("step_2_unlock_coords_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.LockOpen,
                            contentDescription = stringResource(R.string.action_unlock_coords),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.action_unlock_coords),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LockedAxisTile(
    axis: String,
    heapIndex: Int,
    byteAddress: Long,
    value: Double,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        color = ObsidianBg,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, AssetAmber.copy(alpha = 0.45f)),
        modifier = modifier.testTag("locked_axis_tile_$axis")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = axis,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = AssetAmber,
                    fontSize = 9.sp
                )
                Text(
                    text = "[$heapIndex]",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = ElectricCyan,
                    fontSize = 8.sp
                )
            }
            Text(
                text = fmtDec(value),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = DiagnosticsViewModel.formatHexAddress(byteAddress),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFontFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 8.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
fun PersistentPointerChainsCard(
    floatState: WasmFloatDeltaState,
    onVerifyPointers: () -> Unit,
    onToggleWatchPointers: () -> Unit,
    onStreamPointerToHud: (String) -> Unit,
    onPrunePointers: () -> Unit,
    onTogglePinChain: (String) -> Unit,
    onExportMemoryMap: () -> Unit,
    onCopyText: (String) -> Unit
) {
    val hasPrunable = floatState.pointerChains.any { chain ->
        !chain.isPinned && !chain.isSavedLocally &&
            (chain.status == WasmPointerStatus.ZERO || chain.status == WasmPointerStatus.STALE)
    }

    Surface(
        color = ObsidianBg,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(
            1.dp,
            if (floatState.isWatchingPointers) SignalEmerald.copy(alpha = 0.7f) else TelemetryViolet.copy(alpha = 0.65f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("persistent_pointer_chains_card")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AccountTree,
                    contentDescription = null,
                    tint = if (floatState.isWatchingPointers) SignalEmerald else TelemetryViolet,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = stringResource(R.string.wasm_pointer_chains_title),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (floatState.isWatchingPointers) SignalEmerald else TelemetryViolet,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (floatState.savedPointerCountForHost > 0) {
                    Surface(
                        color = AssetAmber.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "SAVED:${floatState.savedPointerCountForHost}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = AssetAmber,
                            fontSize = 8.sp,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
                if (floatState.isAutoVerifyingOnReload) {
                    Surface(
                        color = ElectricCyan.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(9.dp),
                                strokeWidth = 1.5.dp,
                                color = ElectricCyan
                            )
                            Text(
                                text = "AUTO-VERIFY",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                color = ElectricCyan,
                                fontSize = 8.sp
                            )
                        }
                    }
                } else if (floatState.isWatchingPointers) {
                    Surface(
                        color = SignalEmerald.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(1.dp, SignalEmerald.copy(alpha = 0.55f))
                    ) {
                        Text(
                            text = "WATCH #${floatState.pointerWatchTickCount}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = SignalEmerald,
                            fontSize = 8.sp,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                } else if (floatState.pointerScanTotalL1 > 0 || floatState.pointerScanTotalL2 > 0) {
                    Surface(
                        color = TelemetryViolet.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "L1:${floatState.pointerScanTotalL1} • L2:${floatState.pointerScanTotalL2}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = TelemetryViolet,
                            fontSize = 8.sp,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
            }

            if (floatState.pointerStatusMessage.isNotBlank()) {
                Text(
                    text = floatState.pointerStatusMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 14.sp
                )
            }

            if (floatState.pointerChains.isNotEmpty()) {
                // Primary Live Watch + Verify row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = onToggleWatchPointers,
                        shape = RoundedCornerShape(6.dp),
                        color = if (floatState.isWatchingPointers) AlertCoral.copy(alpha = 0.16f) else SignalEmerald.copy(alpha = 0.2f),
                        border = BorderStroke(
                            1.dp,
                            if (floatState.isWatchingPointers) AlertCoral.copy(alpha = 0.7f) else SignalEmerald.copy(alpha = 0.75f)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("wasm_toggle_watch_pointers_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (floatState.isWatchingPointers) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = if (floatState.isWatchingPointers) AlertCoral else SignalEmerald,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = stringResource(
                                    if (floatState.isWatchingPointers) R.string.action_stop_watch_pointers
                                    else R.string.action_watch_pointers_live
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (floatState.isWatchingPointers) AlertCoral else SignalEmerald,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Surface(
                        onClick = onVerifyPointers,
                        shape = RoundedCornerShape(6.dp),
                        color = ElectricCyan.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.65f)),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("wasm_verify_pointers_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Verified,
                                contentDescription = null,
                                tint = ElectricCyan,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.action_verify_pointers),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = ElectricCyan
                            )
                        }
                    }
                }

                // Secondary Prune Zero/Stale + Export JSON row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (hasPrunable) {
                        Surface(
                            onClick = onPrunePointers,
                            shape = RoundedCornerShape(6.dp),
                            color = AssetAmber.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, AssetAmber.copy(alpha = 0.55f)),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 40.dp)
                                .testTag("wasm_prune_pointers_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CleaningServices,
                                    contentDescription = null,
                                    tint = AssetAmber,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.action_prune_pointers),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = AssetAmber
                                )
                            }
                        }
                    }

                    Surface(
                        onClick = onExportMemoryMap,
                        shape = RoundedCornerShape(6.dp),
                        color = ObsidianSurfaceElevated,
                        border = BorderStroke(1.dp, AssetAmber.copy(alpha = 0.55f)),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 40.dp)
                            .testTag("wasm_pointer_export_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                tint = AssetAmber,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Export JSON",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = AssetAmber
                            )
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.wasm_pointer_chains_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    fontSize = 10.sp,
                    lineHeight = 13.sp
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("wasm_pointer_chain_list"),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    floatState.pointerChains.forEach { chain ->
                        WasmPointerChainRow(
                            chain = chain,
                            onStreamToHud = { onStreamPointerToHud(chain.id) },
                            onTogglePin = { onTogglePinChain(chain.id) },
                            onCopy = {
                                onCopyText(
                                    "${chain.expression} [${chain.status.label}] -> ${DiagnosticsViewModel.formatHexAddress(chain.resolvedTargetAddress)} (${fmtDec(chain.resolvedX)}, ${fmtDec(chain.resolvedY)}, ${fmtDec(chain.resolvedZ)})"
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WasmPointerChainRow(
    chain: WasmPointerChain,
    onStreamToHud: () -> Unit,
    onTogglePin: () -> Unit,
    onCopy: () -> Unit
) {
    val regionAccent = if (chain.isLikelyStatic) SignalEmerald else AssetAmber
    val statusAccent = when (chain.status) {
        WasmPointerStatus.MOVING -> SignalEmerald
        WasmPointerStatus.LIVE -> ElectricCyan
        WasmPointerStatus.ZERO -> AssetAmber
        WasmPointerStatus.STALE -> AlertCoral
        WasmPointerStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val rowBg = when {
        chain.isStreamingToHud -> SignalEmerald.copy(alpha = 0.14f)
        chain.status == WasmPointerStatus.MOVING -> SignalEmerald.copy(alpha = 0.09f)
        else -> ObsidianSurface
    }
    val borderColor = when {
        chain.isStreamingToHud -> SignalEmerald
        chain.status == WasmPointerStatus.MOVING -> SignalEmerald.copy(alpha = 0.8f)
        chain.isPinned || chain.isSavedLocally -> AssetAmber.copy(alpha = 0.75f)
        chain.status == WasmPointerStatus.ZERO -> AssetAmber.copy(alpha = 0.5f)
        chain.status == WasmPointerStatus.STALE -> AlertCoral.copy(alpha = 0.55f)
        else -> ObsidianBorder
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = rowBg,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCopy)
            .testTag("pointer_chain_row_${chain.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 7.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(
                        color = TelemetryViolet.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(3.dp)
                    ) {
                        Text(
                            text = "L${chain.depth}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = TelemetryViolet,
                            fontSize = 8.sp,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                    Surface(
                        color = regionAccent.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(3.dp)
                    ) {
                        Text(
                            text = if (chain.isLikelyStatic) "STATIC" else "HEAP",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = regionAccent,
                            fontSize = 8.sp,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                    Surface(
                        color = statusAccent.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(3.dp)
                    ) {
                        Text(
                            text = chain.status.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = statusAccent,
                            fontSize = 8.sp,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                    if (chain.changeCount > 0) {
                        Surface(
                            color = SignalEmerald.copy(alpha = 0.22f),
                            shape = RoundedCornerShape(3.dp)
                        ) {
                            Text(
                                text = "Δ×${chain.changeCount}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                color = SignalEmerald,
                                fontSize = 8.sp,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                    if (chain.isStreamingToHud) {
                        Surface(
                            color = SignalEmerald,
                            shape = RoundedCornerShape(3.dp)
                        ) {
                            Text(
                                text = "HUD",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                color = ObsidianBg,
                                fontSize = 8.sp,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                    if (chain.isSavedLocally) {
                        Surface(
                            color = AssetAmber.copy(alpha = 0.22f),
                            shape = RoundedCornerShape(3.dp)
                        ) {
                            Text(
                                text = "SAVED",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                color = AssetAmber,
                                fontSize = 8.sp,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                Text(
                    text = chain.expression,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    color = ElectricCyan,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                val targetText = when (chain.status) {
                    WasmPointerStatus.MOVING, WasmPointerStatus.LIVE ->
                        "-> ${DiagnosticsViewModel.formatHexAddress(chain.resolvedTargetAddress)} • (${fmtDec(chain.resolvedX)}, ${fmtDec(chain.resolvedY)}, ${fmtDec(chain.resolvedZ)})"
                    WasmPointerStatus.ZERO ->
                        "-> ${DiagnosticsViewModel.formatHexAddress(chain.resolvedTargetAddress)} • (0.0, 0.0, 0.0) [zeroed]"
                    WasmPointerStatus.PENDING ->
                        "Saved chain awaiting live WASM heap verification…"
                    WasmPointerStatus.STALE ->
                        "Pointer dereference out-of-bounds or null on current heap"
                }
                Text(
                    text = targetText,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = when (chain.status) {
                        WasmPointerStatus.MOVING -> SignalEmerald
                        WasmPointerStatus.LIVE -> MaterialTheme.colorScheme.onSurface
                        WasmPointerStatus.ZERO -> AssetAmber
                        WasmPointerStatus.STALE -> AlertCoral
                        WasmPointerStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Stream to HUD action button (48dp touch target)
            IconButton(
                onClick = onStreamToHud,
                enabled = chain.status == WasmPointerStatus.MOVING || chain.status == WasmPointerStatus.LIVE || chain.isStreamingToHud,
                modifier = Modifier
                    .size(44.dp)
                    .testTag("stream_pointer_hud_${chain.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = stringResource(R.string.cd_stream_pointer_hud),
                    tint = when {
                        chain.isStreamingToHud -> SignalEmerald
                        chain.status == WasmPointerStatus.MOVING || chain.status == WasmPointerStatus.LIVE -> ElectricCyan
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                    },
                    modifier = Modifier.size(18.dp)
                )
            }

            // Bookmark / Save per-site action button (44dp touch target)
            IconButton(
                onClick = onTogglePin,
                modifier = Modifier
                    .size(44.dp)
                    .testTag("pin_pointer_chain_${chain.id}")
            ) {
                Icon(
                    imageVector = if (chain.isPinned) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    contentDescription = stringResource(R.string.cd_pin_pointer_chain),
                    tint = if (chain.isPinned) AssetAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * Inline Live Float Watcher card rendered directly inside the `Coords` tab's single vertical scroll
 * stream (no nested scroll trap, no docked overlay stealing height from Entities/Prober/Logs).
 */
@Composable
fun InlineLiveFloatCandidatesCard(
    floatState: WasmFloatDeltaState,
    onToggleExpanded: () -> Unit,
    onTogglePinCandidate: (heapIndex: Int) -> Unit,
    onPinTopTriplet: () -> Unit,
    onLockCoords: (anchorIndex: Int?) -> Unit,
    onFindPointers: () -> Unit,
    onInspectNeighbors: (heapIndex: Int) -> Unit,
    onStopWatch: () -> Unit,
    onCopyText: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (floatState.candidates.isEmpty()) return

    val topTriplet = floatState.candidates.take(3)
    val locked = floatState.lockedCoords
    val accent = if (locked != null) AssetAmber else if (floatState.isLiveWatching) SignalEmerald else ElectricCyan

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = ObsidianSurface,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.65f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("live_float_watcher_bottom_bar")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header row with Expand/Collapse & Live badge
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clickable(onClick = onToggleExpanded),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "${stringResource(R.string.wasm_float_watcher_title)} (${floatState.candidates.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    color = accent.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(5.dp),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = if (floatState.isLiveWatching) "LIVE #${floatState.liveWatchTickCount}" else "PAUSED",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Icon(
                    imageVector = if (floatState.isWatcherExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Always-visible X / Y / Z Live Decimal Tiles
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                topTriplet.forEachIndexed { idx, candidate ->
                    val axisLabel = candidate.axisHint.ifBlank { listOf("X", "Y", "Z").getOrElse(idx) { "F$idx" } }
                    FloatAxisLiveTile(
                        axis = axisLabel,
                        candidate = candidate,
                        onClick = {
                            onCopyText(
                                "HEAPF32[${candidate.heapIndex}] (${DiagnosticsViewModel.formatHexAddress(candidate.byteAddress)}) = ${fmtDec(candidate.currentValue)}"
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Spacious Action Bar: Lock / Find Pointers + Pin X/Y/Z + Stop
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (locked == null) {
                    Surface(
                        onClick = { onLockCoords(null) },
                        shape = RoundedCornerShape(7.dp),
                        color = AssetAmber,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                            .testTag("float_watcher_lock_coords_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = ObsidianBg,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = stringResource(R.string.action_lock_coords),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = ObsidianBg
                            )
                        }
                    }
                } else {
                    Surface(
                        onClick = onFindPointers,
                        shape = RoundedCornerShape(7.dp),
                        color = TelemetryViolet,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                            .testTag("float_watcher_find_pointers_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AccountTree,
                                contentDescription = null,
                                tint = ObsidianBg,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = stringResource(R.string.action_find_pointers),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = ObsidianBg
                            )
                        }
                    }
                }

                Surface(
                    onClick = onPinTopTriplet,
                    shape = RoundedCornerShape(7.dp),
                    color = AssetAmber.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, AssetAmber.copy(alpha = 0.65f)),
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .testTag("float_watcher_pin_xyz_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bookmark,
                            contentDescription = null,
                            tint = AssetAmber,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = stringResource(R.string.wasm_float_pin_top),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = AssetAmber
                        )
                    }
                }

                if (floatState.isLiveWatching) {
                    Surface(
                        onClick = onStopWatch,
                        shape = RoundedCornerShape(7.dp),
                        color = ObsidianBg,
                        border = BorderStroke(1.dp, ObsidianBorder),
                        modifier = Modifier
                            .heightIn(min = 44.dp)
                            .testTag("float_watcher_stop_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = stringResource(R.string.wasm_float_stop_watch),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = stringResource(R.string.wasm_float_stop_watch),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Expandable non-nested inline list of candidate offsets
            AnimatedVisibility(
                visible = floatState.isWatcherExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("float_watcher_candidate_list"),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    HorizontalDivider(color = ObsidianBorder.copy(alpha = 0.7f), thickness = 1.dp)
                    val lockedSet = setOfNotNull(locked?.xIndex, locked?.yIndex, locked?.zIndex)
                    floatState.candidates.take(24).forEach { candidate ->
                        FloatCandidateWatchRow(
                            candidate = candidate,
                            isLocked = lockedSet.contains(candidate.heapIndex),
                            onLockAsAnchor = { onLockCoords(candidate.heapIndex) },
                            onTogglePin = { onTogglePinCandidate(candidate.heapIndex) },
                            onInspectNeighbors = { onInspectNeighbors(candidate.heapIndex) },
                            onCopy = {
                                onCopyText(
                                    "window.HEAPF32[${candidate.heapIndex}] (${DiagnosticsViewModel.formatHexAddress(candidate.byteAddress)}) cur=${fmtDec(candidate.currentValue)} base=${fmtDec(candidate.baselineValue)}"
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatAxisLiveTile(
    axis: String,
    candidate: WasmFloatDeltaCandidate,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tileBorder = when {
        candidate.changedFromPrev -> SignalEmerald
        candidate.isPinned -> AssetAmber
        else -> SignalEmerald.copy(alpha = 0.35f)
    }
    val tileBg = if (candidate.changedFromPrev) SignalEmerald.copy(alpha = 0.16f) else ObsidianBg

    Surface(
        onClick = onClick,
        color = tileBg,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, tileBorder),
        modifier = modifier.testTag("float_axis_tile_$axis")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = axis,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (candidate.isPinned) AssetAmber else SignalEmerald,
                    fontSize = 9.sp
                )
                Text(
                    text = "[${candidate.heapIndex}]",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 8.sp
                )
            }
            Text(
                text = fmtDec(candidate.currentValue),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun FloatCandidateWatchRow(
    candidate: WasmFloatDeltaCandidate,
    isLocked: Boolean,
    onLockAsAnchor: () -> Unit,
    onTogglePin: () -> Unit,
    onInspectNeighbors: () -> Unit,
    onCopy: () -> Unit
) {
    val rowBg = when {
        isLocked -> AssetAmber.copy(alpha = 0.16f)
        candidate.changedFromPrev -> SignalEmerald.copy(alpha = 0.18f)
        candidate.isPinned -> AssetAmber.copy(alpha = 0.1f)
        else -> ObsidianSurface
    }
    val borderColor = when {
        isLocked -> AssetAmber
        candidate.isPinned -> AssetAmber.copy(alpha = 0.7f)
        candidate.changedFromPrev -> SignalEmerald.copy(alpha = 0.7f)
        else -> ObsidianBorder.copy(alpha = 0.8f)
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = rowBg,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCopy)
            .testTag("float_watch_row_${candidate.heapIndex}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 7.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (candidate.axisHint.isNotBlank()) {
                Surface(
                    color = SignalEmerald.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = candidate.axisHint,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = SignalEmerald,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = "HEAPF32[${candidate.heapIndex}] • ${DiagnosticsViewModel.formatHexAddress(candidate.byteAddress)}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = ElectricCyan,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val deltaSign = if (candidate.delta >= 0.0) "+" else ""
                val moveTag = if (candidate.changeCount > 0) " • LIVE ×${candidate.changeCount}" else ""
                Text(
                    text = "${fmtDec(candidate.currentValue)}  (Δ $deltaSign${String.format(Locale.US, "%.2f", candidate.delta)}$moveTag)",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    color = if (candidate.changeCount > 0) SignalEmerald else MaterialTheme.colorScheme.onSurface,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Inspect ±16B neighbors around this float
            Surface(
                onClick = onInspectNeighbors,
                shape = RoundedCornerShape(6.dp),
                color = TelemetryViolet.copy(alpha = 0.16f),
                border = BorderStroke(1.dp, TelemetryViolet.copy(alpha = 0.55f)),
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .testTag("float_inspect_neighbors_${candidate.heapIndex}")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = stringResource(R.string.cd_inspect_float_neighbors),
                        tint = TelemetryViolet,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "±16B",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = TelemetryViolet,
                        fontSize = 9.sp
                    )
                }
            }

            // Lock this float index as X (with idx+1, idx+2 as Y, Z)
            IconButton(
                onClick = onLockAsAnchor,
                modifier = Modifier
                    .size(40.dp)
                    .testTag("float_lock_button_${candidate.heapIndex}")
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = stringResource(R.string.cd_lock_float_candidate),
                    tint = if (isLocked) AssetAmber else ElectricCyan,
                    modifier = Modifier.size(17.dp)
                )
            }

            // Pin / Unpin button
            IconButton(
                onClick = onTogglePin,
                modifier = Modifier
                    .size(40.dp)
                    .testTag("float_pin_button_${candidate.heapIndex}")
            ) {
                Icon(
                    imageVector = if (candidate.isPinned) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    contentDescription = stringResource(R.string.cd_pin_float_candidate),
                    tint = if (candidate.isPinned) AssetAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

private fun fmtDec(value: Double): String {
    return if (value.isFinite()) String.format(Locale.US, "%.3f", value) else "NaN"
}
