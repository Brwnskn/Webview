package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import com.example.R
import com.example.WasmStructFieldKind
import com.example.WasmStructProbeAnchor
import com.example.WasmStructProbeCandidate
import com.example.WasmStructProberState
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

/**
 * Semantic accent color for each entity struct field (`HP`, `MP`, `FP`, `Name`, `Aggro`).
 */
fun structFieldAccent(kind: WasmStructFieldKind): Color = when (kind) {
    WasmStructFieldKind.HP, WasmStructFieldKind.MAX_HP -> AlertCoral
    WasmStructFieldKind.MP, WasmStructFieldKind.MAX_MP -> ElectricCyan
    WasmStructFieldKind.FP, WasmStructFieldKind.MAX_FP -> AssetAmber
    WasmStructFieldKind.NAME -> TelemetryViolet
    WasmStructFieldKind.AGGRO -> AlertCoral
}

/**
 * Compact color-coded vital stat badge with an optional 3dp micro-progress bar,
 * shared by both the persistent Top HUD Strip and Entity Cards.
 */
@Composable
fun VitalGaugePill(
    label: String,
    current: Int,
    max: Int?,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val ratio = if (max != null && max > 0) {
        (current.toFloat() / max.toFloat()).coerceIn(0f, 1f)
    } else null
    val valueText = if (max != null && max > 0) "$current/$max" else "$current"

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = accent.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.5f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                    fontSize = 10.sp
                )
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (ratio != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(ObsidianBg.copy(alpha = 0.85f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(ratio)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(accent)
                    )
                }
            }
        }
    }
}

/**
 * Dedicated full-height single-scroll Entity Struct Field Prober workspace inside the `Prober` tab.
 * Gives every field pill, input box, Probe/Refine button, bound schema chip, and mob-preview
 * candidate generous breathing room and 48dp touch targets without clipping or nested scrolls.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StructProberWorkspace(
    prober: WasmStructProberState,
    hasPlayerAnchor: Boolean,
    hasSelectedTargetAnchor: Boolean,
    selectedTargetLabel: String?,
    trackedMobCount: Int,
    onSelectAnchor: (WasmStructProbeAnchor) -> Unit,
    onSelectField: (WasmStructFieldKind) -> Unit,
    onInputChanged: (String) -> Unit,
    onRunProbe: () -> Unit,
    onRunRefine: () -> Unit,
    onRunAutoProbeNames: () -> Unit,
    onRunAggroProbe: (isAggroedNow: Boolean, refineExisting: Boolean) -> Unit,
    onBindCandidate: (WasmStructProbeCandidate) -> Unit,
    onClearField: (WasmStructFieldKind) -> Unit,
    onClearAllFields: () -> Unit,
    onGoToCoords: () -> Unit,
    onGoToTargetFinder: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeField = prober.activeField
    val activeAccent = structFieldAccent(activeField)
    val schema = prober.schema
    val isTargetAnchor = prober.probeAnchor == WasmStructProbeAnchor.SELECTED_TARGET
    val activeAnchorReady = if (isTargetAnchor) hasSelectedTargetAnchor else hasPlayerAnchor

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("struct_prober_panel"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Probe Anchor Switcher (Player Struct vs Selected Target Mob) + Status Banner
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = if (activeAnchorReady) SignalEmerald.copy(alpha = 0.10f) else AssetAmber.copy(alpha = 0.10f),
            border = BorderStroke(
                1.dp,
                if (activeAnchorReady) SignalEmerald.copy(alpha = 0.5f) else AssetAmber.copy(alpha = 0.55f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ConsoleSubTabSegmentPill(
                        label = if (hasPlayerAnchor) "Player Struct ✓" else "Player Struct",
                        selected = !isTargetAnchor,
                        accent = SignalEmerald,
                        testTag = "prober_anchor_player",
                        onClick = { onSelectAnchor(WasmStructProbeAnchor.PLAYER) },
                        modifier = Modifier.weight(1f)
                    )
                    ConsoleSubTabSegmentPill(
                        label = if (hasSelectedTargetAnchor) {
                            "Target: ${selectedTargetLabel?.take(12) ?: "Mob ✓"}"
                        } else {
                            "Selected Target"
                        },
                        selected = isTargetAnchor,
                        accent = ElectricCyan,
                        testTag = "prober_anchor_target",
                        onClick = { onSelectAnchor(WasmStructProbeAnchor.SELECTED_TARGET) },
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = if (activeAnchorReady) Icons.Default.Memory else Icons.Default.MyLocation,
                        contentDescription = null,
                        tint = if (activeAnchorReady) SignalEmerald else AssetAmber,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = when {
                            isTargetAnchor && hasSelectedTargetAnchor ->
                                "Probing Selected Target mob struct • $trackedMobCount nearby mobs cross-checked."
                            isTargetAnchor ->
                                "No mob target locked yet. Isolate your Selected Target pointer in Target Finder first."
                            hasPlayerAnchor ->
                                "Probing live player struct (+1-hop sub-structs) • $trackedMobCount nearby mobs cross-checked."
                            else ->
                                stringResource(R.string.prober_no_anchor_banner)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    if (!activeAnchorReady) {
                        OutlinedButton(
                            onClick = if (isTargetAnchor) onGoToTargetFinder else onGoToCoords,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, AssetAmber),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier
                                .heightIn(min = 44.dp)
                                .testTag("prober_go_to_coords_button")
                        ) {
                            Text(
                                text = if (isTargetAnchor) "Target Finder" else stringResource(R.string.action_go_to_coords),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = AssetAmber
                            )
                        }
                    }
                }
            }
        }

        // 2. Field Selector Card (Wrapped FlowRow — zero cramped horizontal scrolling)
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = ObsidianSurface,
            border = BorderStroke(1.dp, ObsidianBorder),
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
                    Text(
                        text = "1. Select Target Field",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (schema.hasAnyBound) {
                        Surface(
                            color = SignalEmerald.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "${schema.boundCount} Bound" + if (schema.isSavedLocally) " • Saved ✓" else "",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                color = SignalEmerald,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    WasmStructFieldKind.entries.forEach { kind ->
                        val isSelected = kind == activeField
                        val isBound = schema.fieldFor(kind) != null
                        val accent = structFieldAccent(kind)
                        Surface(
                            onClick = { onSelectField(kind) },
                            shape = RoundedCornerShape(8.dp),
                            color = when {
                                isSelected -> accent.copy(alpha = 0.24f)
                                isBound -> accent.copy(alpha = 0.10f)
                                else -> ObsidianBg
                            },
                            border = BorderStroke(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = when {
                                    isSelected -> accent
                                    isBound -> accent.copy(alpha = 0.55f)
                                    else -> ObsidianBorder
                                }
                            ),
                            modifier = Modifier
                                .heightIn(min = 44.dp)
                                .testTag("struct_field_pill_${kind.jsonKey}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Text(
                                    text = kind.shortLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isSelected || isBound) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected || isBound) accent else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (isBound) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = SignalEmerald,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Probe Value / Name Input + Dedicated Full-Width Action Buttons Card
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = ObsidianSurface,
            border = BorderStroke(1.dp, activeAccent.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = when {
                        activeField.isAggroField -> "2. Probe Mob Aggro Status (Passive vs Aggroed)"
                        activeField.isNameField -> "2. Discover Mob & Player Names"
                        else -> "2. Probe ${activeField.shortLabel} Value"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = activeAccent
                )

                Text(
                    text = stringResource(
                        when {
                            activeField.isAggroField -> R.string.struct_prober_hint_aggro
                            activeField.isNameField -> R.string.struct_prober_hint_name
                            else -> R.string.struct_prober_hint_stat
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (activeField.isAggroField) {
                    val hasPriorAggro = prober.candidates.isNotEmpty()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { onRunAggroProbe(false, hasPriorAggro) },
                            enabled = !prober.isProbing && hasSelectedTargetAnchor,
                            shape = RoundedCornerShape(9.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SignalEmerald,
                                contentColor = ObsidianBg
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("struct_probe_aggro_passive")
                        ) {
                            Text(
                                text = if (hasPriorAggro) "Refine Passive (0)" else stringResource(R.string.action_probe_passive),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Button(
                            onClick = { onRunAggroProbe(true, hasPriorAggro) },
                            enabled = !prober.isProbing && hasSelectedTargetAnchor,
                            shape = RoundedCornerShape(9.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AlertCoral,
                                contentColor = ObsidianBg
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("struct_probe_aggro_active")
                        ) {
                            Text(
                                text = if (hasPriorAggro) "Refine Aggroed (!=0)" else stringResource(R.string.action_probe_aggroed),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    if (hasPriorAggro) {
                        OutlinedButton(
                            onClick = { onRunAggroProbe(false, false) },
                            enabled = !prober.isProbing && hasSelectedTargetAnchor,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, ObsidianBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp)
                                .testTag("struct_probe_aggro_fresh")
                        ) {
                            Text(
                                text = "Start Fresh Passive Scan",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    // Dedicated full-width input row (never squeezed beside buttons)
                    OutlinedTextField(
                        value = prober.queryInput,
                        onValueChange = onInputChanged,
                        singleLine = true,
                        placeholder = {
                            Text(
                                text = stringResource(
                                    if (activeField.isNameField) R.string.struct_prober_name_placeholder
                                    else R.string.struct_prober_stat_placeholder
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = JetBrainsMonoFontFamily
                            )
                        },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (activeField.isNameField) KeyboardType.Text else KeyboardType.Number,
                            imeAction = ImeAction.Search
                        ),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                if (activeField.isNameField && prober.queryInput.isBlank()) {
                                    onRunAutoProbeNames()
                                } else {
                                    onRunProbe()
                                }
                            }
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = activeAccent,
                            unfocusedBorderColor = ObsidianBorder,
                            focusedContainerColor = ObsidianBg,
                            unfocusedContainerColor = ObsidianBg
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .testTag("struct_prober_input")
                    )

                    // Dedicated 48dp Action Button Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (activeField.isNameField) {
                            Button(
                                onClick = onRunAutoProbeNames,
                                enabled = !prober.isProbing && (activeAnchorReady || trackedMobCount > 0),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = TelemetryViolet,
                                    contentColor = ObsidianBg
                                ),
                                shape = RoundedCornerShape(9.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("struct_auto_probe_names_button")
                            ) {
                                if (prober.isProbing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = ObsidianBg
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.action_auto_probe_names),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            if (prober.queryInput.isNotBlank()) {
                                OutlinedButton(
                                    onClick = onRunProbe,
                                    enabled = !prober.isProbing,
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                                    border = BorderStroke(1.dp, TelemetryViolet),
                                    shape = RoundedCornerShape(9.dp),
                                    modifier = Modifier
                                        .weight(0.85f)
                                        .heightIn(min = 48.dp)
                                        .testTag("struct_search_names_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = null,
                                        tint = TelemetryViolet,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.action_search_names),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = TelemetryViolet
                                    )
                                }
                            }
                        } else {
                            Button(
                                onClick = onRunProbe,
                                enabled = !prober.isProbing && activeAnchorReady && prober.queryInput.isNotBlank(),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = activeAccent,
                                    contentColor = ObsidianBg
                                ),
                                shape = RoundedCornerShape(9.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("struct_probe_button")
                            ) {
                                if (prober.isProbing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = ObsidianBg
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.action_probe_struct),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            if (prober.candidates.isNotEmpty()) {
                                OutlinedButton(
                                    onClick = onRunRefine,
                                    enabled = !prober.isProbing && prober.queryInput.isNotBlank(),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                                    border = BorderStroke(1.5.dp, SignalEmerald),
                                    shape = RoundedCornerShape(9.dp),
                                    modifier = Modifier
                                        .weight(0.9f)
                                        .heightIn(min = 48.dp)
                                        .testTag("struct_refine_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FilterAlt,
                                        contentDescription = null,
                                        tint = SignalEmerald,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.action_refine_struct),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = SignalEmerald
                                    )
                                }
                            }
                        }
                    }
                }

                if (prober.statusMessage.isNotBlank()) {
                    Surface(
                        color = ObsidianBg,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, activeAccent.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = prober.statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = activeAccent,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }

        // 4. Candidate Offsets List (Spacious cards with full mob previews & 48dp Bind button)
        if (prober.candidates.isNotEmpty()) {
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
                        .testTag("struct_probe_candidates_list"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "3. Matching Offsets (${prober.candidates.size}) — Tap Bind to Save",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = SignalEmerald
                    )
                    prober.candidates.take(20).forEach { cand ->
                        StructProbeCandidateCard(
                            candidate = cand,
                            accent = activeAccent,
                            onBind = { onBindCandidate(cand) }
                        )
                    }
                }
            }
        }

        // 5. Saved / Bound Schema Summary Card
        if (schema.hasAnyBound) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = ObsidianSurface,
                border = BorderStroke(1.dp, SignalEmerald.copy(alpha = 0.55f)),
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
                        Text(
                            text = "Bound Offsets" + if (schema.hostKey.isNotBlank()) " (${schema.hostKey})" else "",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = SignalEmerald,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            onClick = onClearAllFields,
                            shape = RoundedCornerShape(7.dp),
                            color = AlertCoral.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, AlertCoral.copy(alpha = 0.55f)),
                            modifier = Modifier
                                .heightIn(min = 38.dp)
                                .testTag("clear_all_struct_schema_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteSweep,
                                    contentDescription = null,
                                    tint = AlertCoral,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = stringResource(R.string.action_clear_schema),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AlertCoral,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        WasmStructFieldKind.entries.forEach { kind ->
                            val bound = schema.fieldFor(kind) ?: return@forEach
                            val accent = structFieldAccent(kind)
                            Surface(
                                onClick = { onClearField(kind) },
                                shape = RoundedCornerShape(7.dp),
                                color = ObsidianBg,
                                border = BorderStroke(1.dp, accent.copy(alpha = 0.65f)),
                                modifier = Modifier
                                    .heightIn(min = 40.dp)
                                    .testTag("bound_schema_chip_${kind.jsonKey}")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "${kind.shortLabel} ${bound.displayExpression}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        color = accent
                                    )
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringResource(R.string.cd_clear_bound_field),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(14.dp)
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

@Composable
private fun StructProbeCandidateCard(
    candidate: WasmStructProbeCandidate,
    accent: Color,
    onBind: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = ObsidianBg,
        border = BorderStroke(
            1.dp,
            if (candidate.nonZeroMobCount > 0) SignalEmerald.copy(alpha = 0.6f) else ObsidianBorder
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("struct_candidate_${candidate.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = candidate.expression,
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = accent
                    )
                    Surface(
                        color = ObsidianSurfaceElevated,
                        shape = RoundedCornerShape(5.dp)
                    ) {
                        Text(
                            text = "Player: ${candidate.playerDisplayValue}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                if (candidate.adjacentMaxValue != null) {
                    Text(
                        text = "Adjacent Max detected: ~${candidate.adjacentMaxValue} (auto-binds Max)",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = SignalEmerald
                    )
                }
                if (candidate.mobPreviews.isNotEmpty()) {
                    Text(
                        text = "Nearby Mobs: ${candidate.mobPreviews.joinToString("  •  ")}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = if (candidate.nonZeroMobCount > 0) SignalEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Button(
                onClick = onBind,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SignalEmerald,
                    contentColor = ObsidianBg
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("bind_struct_candidate_${candidate.id}")
            ) {
                Text(
                    text = stringResource(R.string.action_bind_offset),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
