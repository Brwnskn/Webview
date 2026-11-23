package com.example.ui

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.CandidateStagingItem
import com.example.DiagnosticsUiState
import com.example.DiagnosticsViewModel
import com.example.DiscoveredEntitySample
import com.example.LogCategory
import com.example.PlayerCoordinates
import com.example.R
import com.example.ScopeBatchProgress
import com.example.TelemetryLogEntry
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
 * Live progress banner for Step 1's non-blocking 100-item batched global variable scan,
 * with inline Pause / Resume controls.
 */
@Composable
fun BatchScanProgressBanner(
    progress: ScopeBatchProgress,
    onPause: () -> Unit,
    onResume: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = if (progress.isPaused) AssetAmber else ElectricCyan
    Surface(
        color = accent.copy(alpha = 0.12f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
        modifier = modifier.testTag("batch_scan_progress_banner")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = progress.statusText.ifBlank { "Scanning batch ${progress.currentBatch}/${progress.totalBatches}…" },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Surface(
                    onClick = if (progress.isPaused) onResume else onPause,
                    shape = RoundedCornerShape(6.dp),
                    color = ObsidianBg,
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.7f)),
                    modifier = Modifier
                        .heightIn(min = 36.dp)
                        .testTag(if (progress.isPaused) "resume_batch_scan_button" else "pause_batch_scan_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = if (progress.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = stringResource(
                                if (progress.isPaused) R.string.action_resume_scan else R.string.action_pause_scan
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = accent
                        )
                    }
                }
            }

            LinearProgressIndicator(
                progress = { progress.fraction },
                color = accent,
                trackColor = ObsidianBg,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
            )
        }
    }
}

/**
 * Staging Tab: persistent multi-scan candidate accumulation drawer with keyword search,
 * Pinned-Only filter, one-tap bookmarking, live 200ms tracking lock, and tap-to-copy.
 */
@Composable
fun StagingBufferView(
    state: DiagnosticsUiState,
    onTogglePin: (String) -> Unit,
    onTogglePinnedOnly: () -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onClearUnpinned: () -> Unit,
    onTrackLive: (String) -> Unit,
    onPauseBatchScan: () -> Unit,
    onResumeBatchScan: () -> Unit,
    onCopyPath: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val filteredItems = remember(state.stagingBuffer, state.showPinnedOnly, state.stagingSearchQuery) {
        DiagnosticsViewModel.filterStagingBuffer(
            items = state.stagingBuffer,
            showPinnedOnly = state.showPinnedOnly,
            rawQuery = state.stagingSearchQuery
        )
    }

    Column(modifier = modifier.background(ObsidianBg)) {
        // Search & Filter Toolbar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(ObsidianSurface)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (state.scopeBatchProgress.isActive || state.scopeBatchProgress.isPaused) {
                BatchScanProgressBanner(
                    progress = state.scopeBatchProgress,
                    onPause = onPauseBatchScan,
                    onResume = onResumeBatchScan,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            OutlinedTextField(
                value = state.stagingSearchQuery,
                onValueChange = onSearchQueryChanged,
                singleLine = true,
                placeholder = {
                    Text(
                        text = stringResource(R.string.staging_search_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = AssetAmber,
                        modifier = Modifier.size(16.dp)
                    )
                },
                trailingIcon = {
                    if (state.stagingSearchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { onSearchQueryChanged("") },
                            modifier = Modifier.testTag("clear_staging_search_button")
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
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = JetBrainsMonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AssetAmber,
                    unfocusedBorderColor = ObsidianBorder,
                    focusedContainerColor = ObsidianBg,
                    unfocusedContainerColor = ObsidianBg
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("staging_search_input")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "${filteredItems.size}/${state.stagingBuffer.size} staged • ${state.pinnedCandidateCount} pinned",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Surface(
                    onClick = onTogglePinnedOnly,
                    shape = RoundedCornerShape(6.dp),
                    color = if (state.showPinnedOnly) AssetAmber.copy(alpha = 0.22f) else ObsidianBg,
                    border = BorderStroke(1.dp, if (state.showPinnedOnly) AssetAmber else ObsidianBorder),
                    modifier = Modifier.testTag("staging_pinned_only_toggle")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bookmark,
                            contentDescription = null,
                            tint = if (state.showPinnedOnly) AssetAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "Pinned Only",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (state.showPinnedOnly) AssetAmber else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (state.stagingBuffer.any { !it.isPinned }) {
                    Surface(
                        onClick = onClearUnpinned,
                        shape = RoundedCornerShape(6.dp),
                        color = ObsidianBg,
                        border = BorderStroke(1.dp, ObsidianBorder),
                        modifier = Modifier.testTag("staging_clear_unpinned_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "Prune",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = ObsidianBorder, thickness = 1.dp)

        if (filteredItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Radar,
                        contentDescription = null,
                        tint = AssetAmber.copy(alpha = 0.75f),
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        text = stringResource(R.string.staging_empty_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.staging_empty_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("staging_buffer_list"),
                contentPadding = PaddingValues(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filteredItems, key = { it.path }) { item ->
                    StagingCandidateCard(
                        item = item,
                        onTogglePin = { onTogglePin(item.path) },
                        onTrackLive = { onTrackLive(item.path) },
                        onCopy = { onCopyPath("${item.path} | ${item.detail}") }
                    )
                }
            }
        }
    }
}

@Composable
private fun StagingCandidateCard(
    item: CandidateStagingItem,
    onTogglePin: () -> Unit,
    onTrackLive: () -> Unit,
    onCopy: () -> Unit
) {
    val isJsTrackable = !item.path.startsWith("WASM_PTR:") && !item.path.startsWith("window.HEAP")
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (item.isPinned) AssetAmber.copy(alpha = 0.08f) else ObsidianSurface,
        border = BorderStroke(1.dp, if (item.isPinned) AssetAmber.copy(alpha = 0.7f) else ObsidianBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCopy)
            .testTag("staging_item_${item.path}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 9.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Surface(
                        color = ElectricCyan.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = item.sourceStep,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = ElectricCyan,
                            fontSize = 9.sp,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                    Text(
                        text = "Scan #${item.scanNumber} • ×${item.seenCount} • ${item.lastSeenTimestamp}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = item.path,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.detail.isNotBlank()) {
                    Text(
                        text = item.detail,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (isJsTrackable) {
                IconButton(
                    onClick = onTrackLive,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("track_live_candidate_${item.path}")
                ) {
                    Icon(
                        imageVector = Icons.Default.MyLocation,
                        contentDescription = stringResource(R.string.cd_track_candidate),
                        tint = SignalEmerald,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }

            IconButton(
                onClick = onTogglePin,
                modifier = Modifier
                    .size(40.dp)
                    .testTag("pin_candidate_${item.path}")
            ) {
                Icon(
                    imageVector = if (item.isPinned) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    contentDescription = stringResource(R.string.cd_pin_candidate),
                    tint = if (item.isPinned) AssetAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * Entities Tab: visual inspection console for Step 3 discovered entities with world coordinates,
 * player distance, projected 2D screen coordinates, viewport visibility badge, and one-tap pinning.
 */
@Composable
fun EntityInspectionConsoleView(
    state: DiagnosticsUiState,
    onRescanEntities: () -> Unit,
    onToggleLiveEntities: () -> Unit,
    onOpenTargetFinder: () -> Unit,
    onOpenProber: () -> Unit,
    onToggleTrackEntity: (String) -> Unit,
    onClearTrackedEntity: () -> Unit,
    onPinPath: (path: String, detail: String) -> Unit,
    onCopyPayload: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val isWasmEntityMode = state.entityProjectionMode.startsWith("WASM", ignoreCase = true) ||
        state.discoveredEntities.any { it.sampleKeys.contains("WASM_STRUCT") } ||
        state.hasLiveWasmPlayerAnchor ||
        state.structProber.schema.hasAnyBound

    val trackedEntity = remember(state.discoveredEntities, state.trackedEntityKey) {
        state.trackedEntityKey?.let { key ->
            state.discoveredEntities.firstOrNull { it.selectionKey == key }
        }
    }

    Column(modifier = modifier.background(ObsidianBg)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(ObsidianSurface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = state.discoveredEntityArrayPath ?: "Nearby Entities (Not Scanned)",
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = TelemetryViolet,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val meta = buildString {
                        append("${state.discoveredEntities.size} detected")
                        if (state.isWasmEntitiesLivePolling) append(" • LIVE #${state.wasmEntityLiveTickCount}")
                        if (state.structProber.schema.hasAnyBound) {
                            append(" • ${state.structProber.schema.boundCount} offsets")
                        }
                    }
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = if (state.isWasmEntitiesLivePolling) SignalEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Quick shortcut to Selected Target Finder
                Surface(
                    onClick = onOpenTargetFinder,
                    shape = RoundedCornerShape(8.dp),
                    color = AssetAmber.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, AssetAmber.copy(alpha = 0.65f)),
                    modifier = Modifier
                        .heightIn(min = 42.dp)
                        .testTag("entities_open_target_finder_shortcut")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.action_open_target_finder),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = AssetAmber
                        )
                    }
                }

                // Quick shortcut straight to Struct Prober
                Surface(
                    onClick = onOpenProber,
                    shape = RoundedCornerShape(8.dp),
                    color = ElectricCyan.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.65f)),
                    modifier = Modifier
                        .heightIn(min = 42.dp)
                        .testTag("entities_open_prober_shortcut")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.action_probe_hp_names_shortcut),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = ElectricCyan
                        )
                    }
                }
            }

            // Primary spacious 48dp Action Row: Scan Entities + Live 500ms Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = onRescanEntities,
                    shape = RoundedCornerShape(8.dp),
                    color = TelemetryViolet.copy(alpha = 0.22f),
                    border = BorderStroke(1.dp, TelemetryViolet.copy(alpha = 0.75f)),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("rescan_entities_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = TelemetryViolet,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Scan Entities",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = TelemetryViolet
                        )
                    }
                }

                if (isWasmEntityMode && state.discoveredEntities.isNotEmpty()) {
                    Surface(
                        onClick = onToggleLiveEntities,
                        shape = RoundedCornerShape(8.dp),
                        color = if (state.isWasmEntitiesLivePolling) AlertCoral.copy(alpha = 0.18f) else SignalEmerald.copy(alpha = 0.2f),
                        border = BorderStroke(
                            1.dp,
                            if (state.isWasmEntitiesLivePolling) AlertCoral.copy(alpha = 0.75f) else SignalEmerald.copy(alpha = 0.75f)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("toggle_wasm_entities_live_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = if (state.isWasmEntitiesLivePolling) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = stringResource(R.string.cd_toggle_wasm_entities_live),
                                tint = if (state.isWasmEntitiesLivePolling) AlertCoral else SignalEmerald,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(
                                    if (state.isWasmEntitiesLivePolling) R.string.action_live_entities_off
                                    else R.string.action_live_entities_on
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (state.isWasmEntitiesLivePolling) AlertCoral else SignalEmerald
                            )
                        }
                    }
                }
            }
        }

        // Read-Only Entity Location Verification Card (tap any entity below to track distance & heading)
        if (trackedEntity != null) {
            ReadOnlyEntityLocationTrackerCard(
                entity = trackedEntity,
                playerCoords = state.livePlayerCoords,
                onDismiss = onClearTrackedEntity
            )
        }

        HorizontalDivider(color = ObsidianBorder, thickness = 1.dp)

        if (state.discoveredEntities.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ViewInAr,
                        contentDescription = null,
                        tint = TelemetryViolet.copy(alpha = 0.8f),
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        text = stringResource(R.string.entities_empty_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.entities_empty_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("discovered_entities_list"),
                contentPadding = PaddingValues(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(state.discoveredEntities, key = { it.selectionKey }) { entity ->
                    val isTracked = state.trackedEntityKey == entity.selectionKey
                    EntitySampleCard(
                        entity = entity,
                        isTracked = isTracked,
                        onToggleTrack = { onToggleTrackEntity(entity.selectionKey) },
                        onPin = {
                            onPinPath(
                                entity.path,
                                "${entity.displayName} world(${fmt2(entity.worldX)}, ${fmt2(entity.worldY)}, ${fmt2(entity.worldZ)})"
                            )
                        },
                        onCopy = {
                            val vitalsPart = buildList {
                                entity.hp?.let { add("HP=$it${entity.maxHp?.let { m -> "/$m" } ?: ""}") }
                                entity.mp?.let { add("MP=$it${entity.maxMp?.let { m -> "/$m" } ?: ""}") }
                                entity.fp?.let { add("FP=$it${entity.maxFp?.let { m -> "/$m" } ?: ""}") }
                                entity.isAggro?.let { add(if (it) "AGGRO" else "PASSIVE") }
                            }.joinToString(" ")
                            onCopyPayload(
                                "${entity.displayName} (${entity.path})" +
                                    (if (vitalsPart.isNotBlank()) " [$vitalsPart]" else "") +
                                    " world=(${fmt2(entity.worldX)}, ${fmt2(entity.worldY)}, ${fmt2(entity.worldZ)}) " +
                                    "dist=${fmt2(entity.distanceToPlayer)}"
                            )
                        }
                    )
                }
            }
        }
    }
}

/**
 * Strictly read-only Location Verification HUD card shown when a user taps an entity in the list.
 * Displays live 3D distance, ΔX/ΔY/ΔZ vector, and compass heading so the user can manually walk
 * toward the entity in-game and confirm its coordinates hit 0.0u (zero memory writes / zero input injection).
 */
@Composable
private fun ReadOnlyEntityLocationTrackerCard(
    entity: DiscoveredEntitySample,
    playerCoords: PlayerCoordinates?,
    onDismiss: () -> Unit
) {
    val dx = if (playerCoords != null) entity.worldX - playerCoords.x else null
    val dy = if (playerCoords != null) entity.worldY - playerCoords.y else null
    val dz = if (playerCoords != null) entity.worldZ - playerCoords.z else null
    val liveDist = if (dx != null && dy != null && dz != null) {
        kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    } else {
        entity.distanceToPlayer
    }
    val headingText = if (dx != null && dz != null) {
        formatCompassHeading(dx, dz)
    } else {
        "Heading pending player coords"
    }
    val isAtMob = liveDist <= 3.5

    Surface(
        color = if (isAtMob) SignalEmerald.copy(alpha = 0.16f) else ElectricCyan.copy(alpha = 0.12f),
        border = BorderStroke(1.5.dp, if (isAtMob) SignalEmerald else ElectricCyan),
        shape = RoundedCornerShape(9.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("readonly_entity_location_tracker")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = null,
                    tint = if (isAtMob) SignalEmerald else ElectricCyan,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = entity.displayName,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    color = (if (isAtMob) SignalEmerald else ElectricCyan).copy(alpha = 0.22f),
                    shape = RoundedCornerShape(5.dp)
                ) {
                    Text(
                        text = if (isAtMob) "AT MOB ✓ (${fmt2(liveDist)}u)" else "${fmt2(liveDist)}u • $headingText",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = if (isAtMob) SignalEmerald else ElectricCyan,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("dismiss_entity_tracker_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.cd_clear_search),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            if (dx != null && dy != null && dz != null) {
                Text(
                    text = "ΔX=${fmtSigned2(dx)}  ΔY=${fmtSigned2(dy)}  ΔZ=${fmtSigned2(dz)}  |  Target (${fmt2(entity.worldX)}, ${fmt2(entity.worldY)}, ${fmt2(entity.worldZ)})",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = SignalEmerald,
                    fontSize = 10.sp
                )
            }
            Text(
                text = stringResource(R.string.entity_location_tracker_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp
            )
        }
    }
}

private fun formatCompassHeading(dx: Double, dz: Double): String {
    val angleRad = kotlin.math.atan2(dx, dz)
    val deg = ((Math.toDegrees(angleRad) % 360.0) + 360.0) % 360.0
    val dirs = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val idx = (((deg + 22.5) % 360.0) / 45.0).toInt().coerceIn(0, 7)
    return "${dirs[idx]} ${deg.toInt()}°"
}

private fun fmtSigned2(value: Double): String {
    val sign = if (value >= 0.0) "+" else ""
    return "$sign${String.format(Locale.US, "%.1f", value)}"
}

@Composable
private fun EntitySampleCard(
    entity: DiscoveredEntitySample,
    isTracked: Boolean,
    onToggleTrack: () -> Unit,
    onPin: () -> Unit,
    onCopy: () -> Unit
) {
    val borderColor = when {
        isTracked -> ElectricCyan
        entity.isActiveGameTarget -> SignalEmerald
        entity.isAggro == true -> AlertCoral.copy(alpha = 0.75f)
        entity.inViewport -> TelemetryViolet.copy(alpha = 0.6f)
        else -> ObsidianBorder
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = when {
            isTracked -> ElectricCyan.copy(alpha = 0.08f)
            entity.isActiveGameTarget -> SignalEmerald.copy(alpha = 0.08f)
            else -> ObsidianSurface
        },
        border = BorderStroke(
            width = if (isTracked || entity.isActiveGameTarget) 1.5.dp else 1.dp,
            color = borderColor
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleTrack)
            .testTag("entity_sample_${entity.path}")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = entity.displayName + if (entity.resolvedName == null && entity.id.isNotBlank()) " #${entity.id}" else "",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (entity.resolvedName != null) TelemetryViolet else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (entity.isActiveGameTarget) {
                    Surface(
                        color = SignalEmerald.copy(alpha = 0.22f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "TARGET",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = SignalEmerald,
                            fontSize = 9.sp,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
                entity.isAggro?.let { aggro ->
                    Surface(
                        color = (if (aggro) AlertCoral else SignalEmerald).copy(alpha = 0.22f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = if (aggro) "AGGRO" else "PASSIVE",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (aggro) AlertCoral else SignalEmerald,
                            fontSize = 9.sp,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
                Surface(
                    color = ElectricCyan.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = "${fmt2(entity.distanceToPlayer)}u",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = ElectricCyan,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
            }

            if (entity.hasAnyVitals) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    entity.hp?.let { hpVal ->
                        VitalGaugePill(
                            label = "HP",
                            current = hpVal,
                            max = entity.maxHp,
                            accent = AlertCoral,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    entity.mp?.let { mpVal ->
                        VitalGaugePill(
                            label = "MP",
                            current = mpVal,
                            max = entity.maxMp,
                            accent = ElectricCyan,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    entity.fp?.let { fpVal ->
                        VitalGaugePill(
                            label = "FP",
                            current = fpVal,
                            max = entity.maxFp,
                            accent = AssetAmber,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Text(
                text = entity.path + if (isTracked) " • TRACKING LOCATION ✓" else " • Tap card to verify location",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFontFamily,
                color = if (isTracked) ElectricCyan else TelemetryViolet,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(ObsidianBg)
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "World: X=${fmt2(entity.worldX)}  Y=${fmt2(entity.worldY)}  Z=${fmt2(entity.worldZ)}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = SignalEmerald,
                        fontSize = 10.sp
                    )
                    val isWasmStruct = entity.sampleKeys.contains("WASM_STRUCT")
                    val secondaryLine = if (isWasmStruct) {
                        entity.sampleKeys.filter { it != "WASM_STRUCT" }.joinToString(" • ")
                    } else {
                        "Screen: (${fmt2(entity.screenX)}px, ${fmt2(entity.screenY)}px)"
                    }
                    Text(
                        text = secondaryLine,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onPin,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.BookmarkBorder,
                        contentDescription = stringResource(R.string.cd_pin_candidate),
                        tint = AssetAmber,
                        modifier = Modifier.size(17.dp)
                    )
                }

                IconButton(
                    onClick = onCopy,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.cd_copy_log),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

/**
 * Logs Tab: category-filterable rolling telemetry log viewer with `.txt` / `.json` full log dump
 * export and share controls.
 */
@Composable
fun LiveLogConsoleView(
    state: DiagnosticsUiState,
    onSelectFilter: (LogCategory) -> Unit,
    onExportLogDumpTxt: () -> Unit,
    onExportLogDumpJson: () -> Unit,
    onShareLogDump: () -> Unit,
    onCopyPayload: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val filteredLogs = remember(state.logs, state.selectedFilter) {
        val base = if (state.selectedFilter == LogCategory.ALL) {
            state.logs
        } else {
            state.logs.filter { it.category == state.selectedFilter }
        }
        base.asReversed()
    }

    Column(modifier = modifier.background(ObsidianBg)) {
        // Export Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ObsidianSurface)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                onClick = onExportLogDumpTxt,
                enabled = !state.isExportingLogDump,
                shape = RoundedCornerShape(8.dp),
                color = SignalEmerald.copy(alpha = 0.18f),
                border = BorderStroke(1.dp, SignalEmerald.copy(alpha = 0.7f)),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .weight(1f)
                    .testTag("export_log_dump_txt_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (state.isExportingLogDump) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            strokeWidth = 2.dp,
                            color = SignalEmerald
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            tint = SignalEmerald,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = stringResource(R.string.action_export_log_dump_txt),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = SignalEmerald,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Surface(
                onClick = onExportLogDumpJson,
                enabled = !state.isExportingLogDump,
                shape = RoundedCornerShape(8.dp),
                color = ElectricCyan.copy(alpha = 0.16f),
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.65f)),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("export_log_dump_json_button")
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.action_export_log_dump_json),
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = ElectricCyan
                    )
                }
            }

            if (state.lastLogDumpExport?.success == true && state.lastLogDumpExport.contentUri != null) {
                IconButton(
                    onClick = onShareLogDump,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("share_log_dump_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = stringResource(R.string.cd_share_log_dump),
                        tint = SignalEmerald,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Category Filter Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ObsidianSurface)
                .horizontalScroll(rememberScrollState())
                .padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LogCategory.entries.forEach { category ->
                val selected = state.selectedFilter == category
                val accent = categoryAccentColor(category)
                Surface(
                    onClick = { onSelectFilter(category) },
                    shape = RoundedCornerShape(6.dp),
                    color = if (selected) accent.copy(alpha = 0.22f) else ObsidianBg,
                    border = BorderStroke(1.dp, if (selected) accent else ObsidianBorder),
                    modifier = Modifier.testTag("log_filter_${category.name.lowercase(Locale.US)}")
                ) {
                    Text(
                        text = category.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }

        HorizontalDivider(color = ObsidianBorder.copy(alpha = 0.75f), thickness = 1.dp)

        if (filteredLogs.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        tint = ElectricCyan.copy(alpha = 0.75f),
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        text = stringResource(R.string.console_empty_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.console_empty_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("telemetry_log_list"),
                contentPadding = PaddingValues(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filteredLogs, key = { it.id }) { entry ->
                    LogEntryCard(
                        entry = entry,
                        onCopy = {
                            onCopyPayload("[${entry.timestamp}] [${entry.category.tag}] ${entry.summary}\n${entry.payload}")
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun LogEntryCard(
    entry: TelemetryLogEntry,
    onCopy: () -> Unit
) {
    var expanded by remember(entry.id) { mutableStateOf(false) }
    val accent = categoryAccentColor(entry.category)

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = ObsidianSurface,
        border = BorderStroke(1.dp, if (expanded) accent.copy(alpha = 0.65f) else ObsidianBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .testTag("log_entry_${entry.id}")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = accent.copy(alpha = 0.18f)
                ) {
                    Text(
                        text = entry.category.tag,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }

                Text(
                    text = entry.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (expanded) 4 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Text(
                    text = entry.timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 9.sp
                )

                IconButton(
                    onClick = onCopy,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.cd_copy_log),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            if (expanded && entry.payload.isNotBlank()) {
                Text(
                    text = entry.payload,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(ObsidianSurfaceElevated)
                        .padding(8.dp)
                )
            }
        }
    }
}

fun categoryAccentColor(category: LogCategory): Color = when (category) {
    LogCategory.ALL -> ElectricCyan
    LogCategory.SCOPE -> ElectricCyan
    LogCategory.DELTA -> SignalEmerald
    LogCategory.ENTITY -> TelemetryViolet
    LogCategory.WASM -> SignalEmerald
    LogCategory.ASSET -> AssetAmber
    LogCategory.JS -> ElectricCyan
    LogCategory.SYSTEM -> Color(0xFF94A3B8)
    LogCategory.ERROR -> AlertCoral
}

private fun fmt2(value: Double): String = if (value.isFinite()) String.format(Locale.US, "%.2f", value) else "NaN"
