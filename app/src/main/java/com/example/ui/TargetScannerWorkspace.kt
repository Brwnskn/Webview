package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.LiveSelectedTargetSnapshot
import com.example.R
import com.example.SelectedTargetCandidate
import com.example.TargetScanFilterMode
import com.example.WasmStructFieldKind
import com.example.WasmTargetScannerState
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
 * Dedicated full-height single-scroll Selected Target Differential Scanner inside the `Prober` tab.
 *
 * Strictly read-only:
 * - Captures candidate HEAPU32 pointer/ID slots while Mob A is selected.
 * - Narrows by selecting a different mob, keeping the same mob, deselecting (0), or selecting
 *   another mob of the same type.
 * - Watches the isolated slot live at 200ms, exposes 1-tap HP / Name / Aggro struct probing
 *   anchored to the targeted mob, and assigns decoded mob details onto the Entities list.
 */
@Composable
fun TargetScannerWorkspace(
    targetState: WasmTargetScannerState,
    onCaptureMobA: () -> Unit,
    onFilterTarget: (TargetScanFilterMode) -> Unit,
    onLockTargetSlot: (Long) -> Unit,
    onStopTargetWatch: () -> Unit,
    onResetTargetScan: () -> Unit,
    onProbeTargetField: (WasmStructFieldKind) -> Unit,
    onAssignTargetToEntities: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("target_scanner_workspace"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Live Locked Selected Target Card (when watching a target pointer slot)
        targetState.liveTarget?.let { live ->
            LiveSelectedTargetCard(
                live = live,
                isWatching = targetState.isWatchingTarget,
                onStopWatch = onStopTargetWatch,
                onResumeWatch = { onLockTargetSlot(live.slotByteAddress) },
                onProbeField = onProbeTargetField,
                onAssignToEntities = onAssignTargetToEntities
            )
        }

        // 2. Step 1: Capture Mob A + Reset Card
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = ObsidianSurface,
            border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.GpsFixed,
                            contentDescription = null,
                            tint = ElectricCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringResource(R.string.target_finder_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = ElectricCyan
                        )
                    }
                    if (targetState.scanRound > 0) {
                        Surface(
                            color = ElectricCyan.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "R${targetState.scanRound} • ${targetState.totalCandidates}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                color = ElectricCyan,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.target_finder_instructions),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onCaptureMobA,
                        enabled = !targetState.isScanning,
                        shape = RoundedCornerShape(9.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricCyan,
                            contentColor = ObsidianBg
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("target_capture_mob_a_button")
                    ) {
                        if (targetState.isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = ObsidianBg
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.TrackChanges,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.action_target_capture_a),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = onResetTargetScan,
                        enabled = !targetState.isScanning && (targetState.scanRound > 0 || targetState.liveTarget != null),
                        shape = RoundedCornerShape(9.dp),
                        border = BorderStroke(1.dp, ObsidianBorder),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("target_reset_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.wasm_action_reset),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 3. Step 2: 2x2 Differential Filter Grid (48dp spacious buttons)
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = ObsidianSurface,
            border = BorderStroke(1.dp, ObsidianBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "2. Narrow Candidates (Switch or Deselect Mob In-Game)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                val filtersEnabled = !targetState.isScanning && targetState.scanRound > 0
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TargetFilterButton(
                        label = stringResource(R.string.action_target_diff_mob),
                        accent = SignalEmerald,
                        enabled = filtersEnabled,
                        testTag = "target_filter_diff_mob",
                        onClick = { onFilterTarget(TargetScanFilterMode.CHANGED_NON_ZERO) },
                        modifier = Modifier.weight(1f)
                    )
                    TargetFilterButton(
                        label = stringResource(R.string.action_target_deselected),
                        accent = AlertCoral,
                        enabled = filtersEnabled,
                        testTag = "target_filter_deselected",
                        onClick = { onFilterTarget(TargetScanFilterMode.DESELECTED_ZERO) },
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TargetFilterButton(
                        label = stringResource(R.string.action_target_same_mob),
                        accent = AssetAmber,
                        enabled = filtersEnabled,
                        testTag = "target_filter_same_mob",
                        onClick = { onFilterTarget(TargetScanFilterMode.UNCHANGED) },
                        modifier = Modifier.weight(1f)
                    )
                    TargetFilterButton(
                        label = stringResource(R.string.action_target_same_type),
                        accent = TelemetryViolet,
                        enabled = filtersEnabled,
                        testTag = "target_filter_same_type",
                        onClick = { onFilterTarget(TargetScanFilterMode.SAME_TYPE_RESELECT) },
                        modifier = Modifier.weight(1f)
                    )
                }

                if (targetState.statusMessage.isNotBlank()) {
                    Surface(
                        color = ObsidianBg,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = targetState.statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = ElectricCyan,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }

        // 4. Ranked Surviving Target Slot Candidates
        if (targetState.candidates.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = ObsidianSurface,
                border = BorderStroke(1.dp, SignalEmerald.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .testTag("target_candidates_list"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "3. Candidate Target Slots (${targetState.candidates.size} shown of ${targetState.totalCandidates})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = SignalEmerald
                    )
                    targetState.candidates.take(20).forEach { cand ->
                        val isLocked = targetState.lockedSlotAddress == cand.slotByteAddress
                        TargetSlotCandidateCard(
                            candidate = cand,
                            isLocked = isLocked,
                            onWatchSlot = { onLockTargetSlot(cand.slotByteAddress) }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun LiveSelectedTargetCard(
    live: LiveSelectedTargetSnapshot,
    isWatching: Boolean,
    onStopWatch: () -> Unit,
    onResumeWatch: () -> Unit,
    onProbeField: (WasmStructFieldKind) -> Unit,
    onAssignToEntities: () -> Unit
) {
    val accent = if (live.hasTarget) SignalEmerald else AssetAmber
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = accent.copy(alpha = 0.10f),
        border = BorderStroke(1.5.dp, accent.copy(alpha = 0.7f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("live_selected_target_card")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = if (live.hasTarget) {
                            live.resolvedName ?: "Selected Target @ ${formatHexShort(live.targetStructAddress)}"
                        } else {
                            "No Mob Selected (0)"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Slot ${live.slotHex} • Tick #${live.tickCount}" +
                            (live.distanceToPlayer?.let { " • ${String.format(Locale.US, "%.1f", it)}u" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Aggro Status Badge if bound
                live.isAggro?.let { aggro ->
                    Surface(
                        color = (if (aggro) AlertCoral else SignalEmerald).copy(alpha = 0.2f),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, if (aggro) AlertCoral else SignalEmerald)
                    ) {
                        Text(
                            text = if (aggro) "AGGRO!" else "PASSIVE",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (aggro) AlertCoral else SignalEmerald,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                }

                Surface(
                    onClick = if (isWatching) onStopWatch else onResumeWatch,
                    shape = RoundedCornerShape(7.dp),
                    color = ObsidianBg,
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.7f)),
                    modifier = Modifier.heightIn(min = 38.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = if (isWatching) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = if (isWatching) "Pause" else "Watch",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = accent
                        )
                    }
                }
            }

            if (live.hasTarget) {
                if (live.hp != null) {
                    VitalGaugePill(
                        label = "TARGET HP",
                        current = live.hp,
                        max = live.maxHp,
                        accent = AlertCoral,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (live.worldX != null && live.worldY != null && live.worldZ != null) {
                    Text(
                        text = "World: X=${String.format(Locale.US, "%.2f", live.worldX)}  Y=${String.format(Locale.US, "%.2f", live.worldY)}  Z=${String.format(Locale.US, "%.2f", live.worldZ)}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = ElectricCyan
                    )
                }

                // Quick 1-tap Target Struct Probing Buttons (Name / HP / Aggro)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TargetQuickProbeChip(
                        label = "Probe Name",
                        accent = TelemetryViolet,
                        testTag = "target_quick_probe_name",
                        onClick = { onProbeField(WasmStructFieldKind.NAME) },
                        modifier = Modifier.weight(1f)
                    )
                    TargetQuickProbeChip(
                        label = "Probe HP",
                        accent = AlertCoral,
                        testTag = "target_quick_probe_hp",
                        onClick = { onProbeField(WasmStructFieldKind.HP) },
                        modifier = Modifier.weight(1f)
                    )
                    TargetQuickProbeChip(
                        label = "Probe Aggro",
                        accent = AssetAmber,
                        testTag = "target_quick_probe_aggro",
                        onClick = { onProbeField(WasmStructFieldKind.AGGRO) },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Assign decoded mob details to Entity List (read-only link)
                Button(
                    onClick = onAssignToEntities,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SignalEmerald,
                        contentColor = ObsidianBg
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("assign_target_to_entities_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.AddLink,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.action_assign_target_to_entities),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun TargetQuickProbeChip(
    label: String,
    accent: Color,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = accent.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.65f)),
        modifier = modifier
            .heightIn(min = 42.dp)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TargetFilterButton(
    label: String,
    accent: Color,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(9.dp),
        border = BorderStroke(1.dp, if (enabled) accent.copy(alpha = 0.75f) else ObsidianBorder),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (enabled) accent.copy(alpha = 0.14f) else ObsidianBg,
            contentColor = accent
        ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
        modifier = modifier
            .heightIn(min = 48.dp)
            .testTag(testTag)
    ) {
        Icon(
            imageVector = Icons.Default.FilterAlt,
            contentDescription = null,
            tint = if (enabled) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (enabled) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TargetSlotCandidateCard(
    candidate: SelectedTargetCandidate,
    isLocked: Boolean,
    onWatchSlot: () -> Unit
) {
    val hasEntityMatch = candidate.matchedEntityId != null || candidate.resolvedCoordAddress > 0L
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = ObsidianBg,
        border = BorderStroke(
            width = if (isLocked) 1.5.dp else 1.dp,
            color = when {
                isLocked -> SignalEmerald
                hasEntityMatch -> ElectricCyan.copy(alpha = 0.65f)
                else -> ObsidianBorder
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("target_candidate_${candidate.slotHex}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = candidate.slotHex,
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = ElectricCyan
                    )
                    Text(
                        text = "-> ${candidate.currentHex}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                val tagText = buildList {
                    candidate.matchedEntityName?.let { add("ENTITY MATCH: $it") }
                    candidate.nearPlayerOffsetBytes?.let { off ->
                        val sign = if (off < 0) "-" else "+"
                        add("Player ${sign}0x${kotlin.math.abs(off).toString(16).uppercase()}")
                    }
                    candidate.previewName?.let { add("\"$it\"") }
                    candidate.previewHp?.let { add("HP=$it") }
                    if (candidate.resolvedX != null && candidate.resolvedZ != null) {
                        add("(${String.format(Locale.US, "%.1f", candidate.resolvedX)}, ${String.format(Locale.US, "%.1f", candidate.resolvedZ)})")
                    }
                }.joinToString(" • ")

                if (tagText.isNotBlank()) {
                    Text(
                        text = tagText,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = if (hasEntityMatch) SignalEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Button(
                onClick = onWatchSlot,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isLocked) SignalEmerald else ElectricCyan,
                    contentColor = ObsidianBg
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("lock_target_slot_${candidate.slotHex}")
            ) {
                Text(
                    text = if (isLocked) "Watching ✓" else "Watch Live",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private fun formatHexShort(address: Long): String {
    val safe = address.coerceAtLeast(0L)
    return "0x" + safe.toString(16).uppercase(Locale.US).padStart(8, '0')
}
