package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.DiagnosticsUiState
import com.example.DiagnosticsViewModel
import com.example.R
import com.example.WasmCandidateAddress
import com.example.WasmHeapTrackerState
import com.example.WasmNeighborFloat
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
 * Targeted WebAssembly Heap Value-Tracker UI.
 *
 * Strictly observational and read-only:
 * 1. Scans `window.HEAP32` and `window.HEAPF32` (with automatic Module fallback) for an exact stat/HP value.
 * 2. Filters down matching indices after the stat changes in-game.
 * 3. Streams live neighboring floats (±4 to ±16 bytes) every 200ms and highlights moving coordinates.
 * 4. Exports the isolated memory map as JSON to Downloads (strictly read-only).
 */
@Composable
fun WasmHeapTrackerView(
    state: DiagnosticsUiState,
    onValueInputChanged: (String) -> Unit,
    onRunInitialScan: () -> Unit,
    onRunFilterDelta: () -> Unit,
    onInspectCandidate: (heapType: String, index: Int) -> Unit,
    onStopNeighborPoll: () -> Unit,
    onResetTracker: () -> Unit,
    onExportMemoryMap: () -> Unit,
    onShareMemoryMap: () -> Unit,
    onCopyText: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val wasm = state.wasmHeap

    Column(
        modifier = modifier
            .background(ObsidianBg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Card 1: HP / Stat Search & Value-Delta Filter
        WasmStatScannerCard(
            wasm = wasm,
            onValueInputChanged = onValueInputChanged,
            onRunInitialScan = onRunInitialScan,
            onRunFilterDelta = onRunFilterDelta,
            onInspectCandidate = onInspectCandidate,
            onResetTracker = onResetTracker
        )

        // Card 2: Neighboring Memory Inspector (±4 to ±16 Bytes)
        WasmNeighborInspectorCard(
            wasm = wasm,
            onStopNeighborPoll = onStopNeighborPoll,
            onCopyText = onCopyText
        )

        // Card 3: Export Memory Map (.json)
        WasmMemoryMapExportCard(
            wasm = wasm,
            onExportMemoryMap = onExportMemoryMap,
            onShareMemoryMap = onShareMemoryMap
        )
    }
}

@Composable
private fun WasmStatScannerCard(
    wasm: WasmHeapTrackerState,
    onValueInputChanged: (String) -> Unit,
    onRunInitialScan: () -> Unit,
    onRunFilterDelta: () -> Unit,
    onInspectCandidate: (heapType: String, index: Int) -> Unit,
    onResetTracker: () -> Unit
) {
    val canFilter = !wasm.isScanning && wasm.totalMatches > 0
    val hasInput = wasm.targetValueInput.trim().toDoubleOrNull() != null

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = ObsidianSurface,
        border = BorderStroke(
            1.dp,
            if (wasm.isolatedIndex != null) SignalEmerald.copy(alpha = 0.65f) else ElectricCyan.copy(alpha = 0.45f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Memory,
                    contentDescription = null,
                    tint = ElectricCyan,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = stringResource(R.string.wasm_tracker_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (wasm.totalMatches > 0 || wasm.isolatedIndex != null || wasm.statusMessage.isNotBlank()) {
                    Surface(
                        onClick = onResetTracker,
                        shape = RoundedCornerShape(6.dp),
                        color = ObsidianBg,
                        border = BorderStroke(1.dp, ObsidianBorder),
                        modifier = Modifier.testTag("wasm_reset_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = stringResource(R.string.wasm_action_reset),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (wasm.heapSourceLabel.isNotBlank()) {
                val mb = wasm.heapByteLength / (1024.0 * 1024.0)
                val sizeLabel = if (wasm.heapByteLength > 0L) {
                    String.format(Locale.US, " • %.1f MB linear heap", mb)
                } else ""
                Text(
                    text = "${wasm.heapSourceLabel}$sizeLabel",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = ElectricCyan,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Stat / HP Value Input
            OutlinedTextField(
                value = wasm.targetValueInput,
                onValueChange = onValueInputChanged,
                singleLine = true,
                placeholder = {
                    Text(
                        text = stringResource(R.string.wasm_value_input_hint),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    if (wasm.targetValueInput.isNotEmpty()) {
                        IconButton(
                            onClick = { onValueInputChanged("") },
                            modifier = Modifier.testTag("wasm_clear_value_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.cd_clear_search),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                },
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (canFilter) onRunFilterDelta() else onRunInitialScan()
                    }
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ElectricCyan,
                    unfocusedBorderColor = ObsidianBorder,
                    focusedContainerColor = ObsidianBg,
                    unfocusedContainerColor = ObsidianBg,
                    cursorColor = ElectricCyan
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("wasm_hp_value_input")
            )

            // Primary Actions: Step 1 Initial Scan & Step 2 Value-Delta Filter
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onRunInitialScan,
                    enabled = !wasm.isScanning && hasInput,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricCyan.copy(alpha = 0.2f),
                        contentColor = ElectricCyan,
                        disabledContainerColor = ObsidianSurfaceElevated,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (!wasm.isScanning && hasInput) ElectricCyan else ObsidianBorder
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("wasm_scan_initial_button")
                ) {
                    if (wasm.isScanning && wasm.filterRound == 0) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            strokeWidth = 2.dp,
                            color = ElectricCyan
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = stringResource(R.string.wasm_action_scan_heap),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Button(
                    onClick = onRunFilterDelta,
                    enabled = canFilter && hasInput,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (canFilter && hasInput) SignalEmerald else ObsidianSurfaceElevated,
                        contentColor = if (canFilter && hasInput) ObsidianBg else MaterialTheme.colorScheme.onSurfaceVariant,
                        disabledContainerColor = ObsidianSurfaceElevated,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (canFilter && hasInput) SignalEmerald else ObsidianBorder
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("wasm_filter_delta_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.FilterAlt,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = stringResource(R.string.wasm_action_filter_delta),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Plain-English Delta Filter Guidance
            Text(
                text = stringResource(R.string.wasm_filter_instruction),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Match Summary Badges & Status
            if (wasm.totalMatches > 0 || wasm.statusMessage.isNotBlank()) {
                Surface(
                    color = ObsidianBg,
                    shape = RoundedCornerShape(7.dp),
                    border = BorderStroke(1.dp, ObsidianBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            WasmCountChip(
                                label = "Matches: ${wasm.totalMatches}",
                                accent = if (wasm.totalMatches == 1) SignalEmerald else ElectricCyan
                            )
                            WasmCountChip(
                                label = "HEAP32: ${wasm.heap32MatchCount}",
                                accent = ElectricCyan
                            )
                            WasmCountChip(
                                label = "HEAPF32: ${wasm.heapF32MatchCount}",
                                accent = TelemetryViolet
                            )
                            if (wasm.filterRound > 0) {
                                WasmCountChip(
                                    label = "Round #${wasm.filterRound}",
                                    accent = AssetAmber
                                )
                            }
                        }

                        if (wasm.statusMessage.isNotBlank()) {
                            Text(
                                text = wasm.statusMessage,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetBrainsMonoFontFamily,
                                color = if (wasm.isolatedIndex != null) SignalEmerald else MaterialTheme.colorScheme.onSurface,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }

            // Clickable Surviving Candidate Chips (when narrowed down or sample addresses available)
            if (wasm.survivingCandidates.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = if (wasm.totalMatches <= wasm.survivingCandidates.size) {
                            "Surviving Addresses — tap any to stream ±16B neighbors:"
                        } else {
                            "Sample Matches (showing ${wasm.survivingCandidates.size} of ${wasm.totalMatches}) — filter after HP changes or tap to inspect:"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        wasm.survivingCandidates.forEach { candidate ->
                            WasmCandidateChip(
                                candidate = candidate,
                                isSelected = wasm.isolatedIndex == candidate.index && wasm.isolatedHeapType == candidate.heapType,
                                onClick = { onInspectCandidate(candidate.heapType, candidate.index) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WasmNeighborInspectorCard(
    wasm: WasmHeapTrackerState,
    onStopNeighborPoll: () -> Unit,
    onCopyText: (String) -> Unit
) {
    val isolatedIndex = wasm.isolatedIndex
    val isolatedType = wasm.isolatedHeapType ?: "HEAP32"
    val isolatedHex = wasm.isolatedByteAddress?.let { DiagnosticsViewModel.formatHexAddress(it) } ?: ""

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = ObsidianSurface,
        border = BorderStroke(
            1.dp,
            if (wasm.isNeighborPolling) SignalEmerald.copy(alpha = 0.7f) else ObsidianBorder
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("wasm_neighbor_inspector_card")
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.wasm_neighbors_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isolatedIndex != null) SignalEmerald else MaterialTheme.colorScheme.onSurface
                    )
                    if (isolatedIndex != null) {
                        Text(
                            text = "$isolatedType[$isolatedIndex] • $isolatedHex • Val=${wasm.isolatedCurrentValue}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = ElectricCyan
                        )
                    }
                }

                if (wasm.isNeighborPolling) {
                    Surface(
                        color = SignalEmerald.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(5.dp),
                        border = BorderStroke(1.dp, SignalEmerald.copy(alpha = 0.55f))
                    ) {
                        Text(
                            text = "LIVE #${wasm.neighborPollTickCount}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = SignalEmerald,
                            fontSize = 9.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }

                    Surface(
                        onClick = onStopNeighborPoll,
                        shape = RoundedCornerShape(6.dp),
                        color = ObsidianBg,
                        border = BorderStroke(1.dp, AssetAmber.copy(alpha = 0.7f)),
                        modifier = Modifier.testTag("wasm_stop_poll_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = null,
                                tint = AssetAmber,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = stringResource(R.string.wasm_action_stop_poll),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = AssetAmber
                            )
                        }
                    }
                }
            }

            Text(
                text = stringResource(R.string.wasm_neighbors_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (wasm.neighborFloats.isEmpty()) {
                Surface(
                    color = ObsidianBg,
                    shape = RoundedCornerShape(7.dp),
                    border = BorderStroke(1.dp, ObsidianBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Isolate an HP/stat address above (or tap a surviving candidate chip) to stream live ±4B, ±8B, ±12B, and ±16B Float32 / Int32 neighbors here.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            } else {
                Surface(
                    color = ObsidianBg,
                    shape = RoundedCornerShape(7.dp),
                    border = BorderStroke(1.dp, ObsidianBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("wasm_neighbors_table")
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Table Header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(ObsidianSurfaceElevated)
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "OFFSET",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 9.sp,
                                modifier = Modifier.width(56.dp)
                            )
                            Text(
                                text = "HEX ADDR",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 9.sp,
                                modifier = Modifier.width(82.dp)
                            )
                            Text(
                                text = "FLOAT32",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = ElectricCyan,
                                fontSize = 9.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "INT32",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 9.sp,
                                modifier = Modifier.width(72.dp)
                            )
                        }

                        wasm.neighborFloats.forEachIndexed { idx, row ->
                            if (idx > 0) {
                                HorizontalDivider(color = ObsidianBorder.copy(alpha = 0.55f), thickness = 1.dp)
                            }
                            WasmNeighborRow(
                                neighbor = row,
                                onClick = {
                                    val sign = if (row.byteOffset >= 0) "+" else ""
                                    val hex = DiagnosticsViewModel.formatHexAddress(row.byteAddress)
                                    onCopyText(
                                        "${sign}${row.byteOffset}B | idx=${row.heapIndex} ($hex) | F32=${fmtFloat(row.floatValue)} | I32=${row.intValue}"
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WasmNeighborRow(
    neighbor: WasmNeighborFloat,
    onClick: () -> Unit
) {
    val isTarget = neighbor.byteOffset == 0
    val isDynamic = !isTarget && neighbor.changeCount > 0
    val rowBg = when {
        isTarget -> ElectricCyan.copy(alpha = 0.14f)
        neighbor.changedFromPrev -> SignalEmerald.copy(alpha = 0.24f)
        isDynamic -> SignalEmerald.copy(alpha = 0.11f)
        else -> Color.Transparent
    }
    val offsetLabel = when {
        isTarget -> "0B ★"
        neighbor.byteOffset > 0 -> "+${neighbor.byteOffset}B"
        else -> "${neighbor.byteOffset}B"
    }
    val offsetColor = when {
        isTarget -> ElectricCyan
        isDynamic -> SignalEmerald
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag("wasm_neighbor_offset_${neighbor.byteOffset}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = offsetLabel,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold,
                color = offsetColor,
                fontSize = 10.sp,
                modifier = Modifier.width(56.dp)
            )
            Text(
                text = DiagnosticsViewModel.formatHexAddress(neighbor.byteAddress),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFontFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 9.sp,
                modifier = Modifier.width(82.dp)
            )
            Text(
                text = fmtFloat(neighbor.floatValue),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = if (isDynamic || isTarget) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    isTarget -> ElectricCyan
                    isDynamic -> SignalEmerald
                    else -> MaterialTheme.colorScheme.onSurface
                },
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = neighbor.intValue.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFontFamily,
                color = if (isTarget) ElectricCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(72.dp)
            )
        }

        if (isTarget || isDynamic) {
            Row(
                modifier = Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isTarget) "ISOLATED TARGET STAT (idx=${neighbor.heapIndex})"
                    else "DYNAMIC COORD CANDIDATE • MOVING ×${neighbor.changeCount}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isTarget) ElectricCyan else SignalEmerald,
                    fontSize = 9.sp
                )
            }
        }
    }
}

@Composable
private fun WasmMemoryMapExportCard(
    wasm: WasmHeapTrackerState,
    onExportMemoryMap: () -> Unit,
    onShareMemoryMap: () -> Unit
) {
    val canExport = !wasm.isExportingMemoryMap && (wasm.isolatedIndex != null || wasm.totalMatches > 0)

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = ObsidianSurface,
        border = BorderStroke(
            1.dp,
            if (canExport) AssetAmber.copy(alpha = 0.55f) else ObsidianBorder
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onExportMemoryMap,
                    enabled = canExport,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (canExport) AssetAmber else ObsidianSurfaceElevated,
                        contentColor = if (canExport) ObsidianBg else MaterialTheme.colorScheme.onSurfaceVariant,
                        disabledContainerColor = ObsidianSurfaceElevated,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                    ),
                    border = BorderStroke(1.dp, if (canExport) AssetAmber else ObsidianBorder),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("wasm_export_memory_map_button")
                ) {
                    if (wasm.isExportingMemoryMap) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = ObsidianBg
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = stringResource(R.string.cd_export_wasm_map),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.wasm_action_export_map),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (wasm.lastMemoryMapExport?.success == true && wasm.lastMemoryMapExport.contentUri != null) {
                    OutlinedButton(
                        onClick = onShareMemoryMap,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, SignalEmerald),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("wasm_share_memory_map_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = stringResource(R.string.cd_share_report),
                            tint = SignalEmerald,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.action_share_log_dump),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = SignalEmerald
                        )
                    }
                }
            }

            if (wasm.lastMemoryMapExport?.success == true) {
                Text(
                    text = "Saved to ${wasm.lastMemoryMapExport.displayPath}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = SignalEmerald,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(ObsidianBg)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun WasmCountChip(
    label: String,
    accent: Color
) {
    Surface(
        color = accent.copy(alpha = 0.16f),
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = label,
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
private fun WasmCandidateChip(
    candidate: WasmCandidateAddress,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val accent = if (isSelected) SignalEmerald else ElectricCyan
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = if (isSelected) SignalEmerald.copy(alpha = 0.2f) else ObsidianBg,
        border = BorderStroke(1.dp, accent.copy(alpha = if (isSelected) 0.9f else 0.55f)),
        modifier = Modifier
            .heightIn(min = 40.dp)
            .testTag("wasm_candidate_${candidate.heapType}_${candidate.index}")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = "${candidate.heapType}[${candidate.index}]",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold,
                color = accent,
                fontSize = 10.sp
            )
            Text(
                text = "${DiagnosticsViewModel.formatHexAddress(candidate.byteAddress)} = ${candidate.currentValue}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFontFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 9.sp
            )
        }
    }
}

private fun fmtFloat(value: Double): String {
    return if (value.isFinite()) String.format(Locale.US, "%.4f", value) else "NaN"
}
