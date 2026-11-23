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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.example.AssetCaptureStatus
import com.example.BinaryAssetInspector
import com.example.DiagnosticsUiState
import com.example.InterceptedAsset
import com.example.R
import com.example.ui.theme.AlertCoral
import com.example.ui.theme.AssetAmber
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.TelemetryViolet
import java.util.Locale

/**
 * Local Binary Asset Metadata Inspector (`.bin`, `.wasm`, `.json` & tracked chunks).
 * Displays files passively mirrored into `context.filesDir` along with ByteBuffer-decoded
 * headers, magic signatures, entropy, WASM sections, Float32 summaries, and JSON keys.
 */
@Composable
fun AssetMetadataInspectorView(
    state: DiagnosticsUiState,
    onClearAssetCache: () -> Unit,
    onExportAssetDumpJson: () -> Unit,
    onCopyMetadata: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedExtFilter by remember { mutableStateOf("ALL") }
    val filterOptions = remember { listOf("ALL", ".wasm", ".bin", ".json", "Other") }

    val visibleAssets = remember(state.interceptedAssets, selectedExtFilter) {
        when (selectedExtFilter) {
            "ALL" -> state.interceptedAssets
            "Other" -> state.interceptedAssets.filter {
                it.extension !in setOf(".wasm", ".bin", ".json")
            }
            else -> state.interceptedAssets.filter {
                it.extension.equals(selectedExtFilter, ignoreCase = true)
            }
        }
    }

    Column(modifier = modifier.background(ObsidianBg)) {
        // Summary & Actions Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ObsidianSurface)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = "Cached: ${state.cachedAssetCount}/${state.interceptedAssets.size} • ${BinaryAssetInspector.formatBytes(state.totalCachedAssetBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = AssetAmber
                )
                Text(
                    text = "Read-only ByteBuffer audit in private filesDir",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp
                )
            }

            Surface(
                onClick = onExportAssetDumpJson,
                shape = RoundedCornerShape(6.dp),
                color = SignalEmerald.copy(alpha = 0.16f),
                border = BorderStroke(1.dp, SignalEmerald.copy(alpha = 0.65f)),
                modifier = Modifier.testTag("assets_export_json_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        tint = SignalEmerald,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "Export JSON",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = SignalEmerald
                    )
                }
            }

            Surface(
                onClick = onClearAssetCache,
                shape = RoundedCornerShape(6.dp),
                color = ObsidianBg,
                border = BorderStroke(1.dp, ObsidianBorder),
                modifier = Modifier.testTag("assets_clear_cache_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = stringResource(R.string.cd_clear_asset_cache),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = stringResource(R.string.action_clear_asset_cache),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Extension Filter Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ObsidianSurface)
                .horizontalScroll(rememberScrollState())
                .padding(start = 8.dp, end = 8.dp, bottom = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            filterOptions.forEach { option ->
                val selected = selectedExtFilter == option
                val accent = extensionAccent(option)
                Surface(
                    onClick = { selectedExtFilter = option },
                    shape = RoundedCornerShape(5.dp),
                    color = if (selected) accent.copy(alpha = 0.22f) else ObsidianBg,
                    border = BorderStroke(1.dp, if (selected) accent else ObsidianBorder),
                    modifier = Modifier.testTag("asset_filter_${option.removePrefix(".").lowercase()}")
                ) {
                    Text(
                        text = option,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }

        HorizontalDivider(color = ObsidianBorder.copy(alpha = 0.7f), thickness = 1.dp)

        if (visibleAssets.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = null,
                        tint = AssetAmber.copy(alpha = 0.75f),
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        text = stringResource(R.string.assets_empty_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.assets_empty_subtitle),
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
                    .testTag("asset_metadata_list"),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(visibleAssets, key = { it.url }) { asset ->
                    AssetMetadataCard(
                        asset = asset,
                        onCopyMetadata = {
                            val summaryBlob = buildString {
                                appendLine("File: ${asset.fileName.ifBlank { asset.url }}")
                                appendLine("URL: ${asset.url}")
                                appendLine("Status: ${asset.captureStatus.label} (${BinaryAssetInspector.formatBytes(asset.byteSize)})")
                                if (asset.localRelativePath != null) appendLine("Local: filesDir/${asset.localRelativePath}")
                                if (asset.magicSignature.isNotBlank()) appendLine("Magic: ${asset.magicSignature}")
                                if (asset.sha256Prefix.isNotBlank()) appendLine("SHA256: ${asset.sha256Prefix}…")
                                if (asset.structuralDetails.isNotBlank()) appendLine(asset.structuralDetails)
                                if (asset.hexHeaderPreview.isNotBlank()) appendLine("Header:\n${asset.hexHeaderPreview}")
                            }.trimEnd()
                            onCopyMetadata(summaryBlob)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AssetMetadataCard(
    asset: InterceptedAsset,
    onCopyMetadata: () -> Unit
) {
    var expanded by remember(asset.url) { mutableStateOf(false) }
    val extAccent = extensionAccent(asset.extension)
    val statusAccent = when (asset.captureStatus) {
        AssetCaptureStatus.CAPTURED -> SignalEmerald
        AssetCaptureStatus.CACHING -> ElectricCyan
        AssetCaptureStatus.FAILED -> AlertCoral
        AssetCaptureStatus.OBSERVED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = ObsidianSurface,
        border = BorderStroke(
            1.dp,
            if (expanded) extAccent.copy(alpha = 0.7f) else ObsidianBorder
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .testTag("asset_card_${asset.fileName}")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    color = extAccent.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = asset.extension.uppercase(Locale.US),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = extAccent,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }

                Text(
                    text = asset.fileName.ifBlank { asset.url.substringAfterLast('/') },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (asset.captureStatus == AssetCaptureStatus.CACHING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.6.dp,
                        color = ElectricCyan
                    )
                } else {
                    Surface(
                        color = statusAccent.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = if (asset.byteSize > 0L) {
                                "${asset.captureStatus.label} • ${BinaryAssetInspector.formatBytes(asset.byteSize)}"
                            } else {
                                asset.captureStatus.label
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = statusAccent,
                            fontSize = 9.sp,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }

                IconButton(
                    onClick = onCopyMetadata,
                    modifier = Modifier.size(26.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.cd_copy_log),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            if (asset.structuralSummary.isNotBlank()) {
                Text(
                    text = asset.structuralSummary,
                    style = MaterialTheme.typography.labelSmall,
                    color = extAccent.copy(alpha = 0.92f),
                    maxLines = if (expanded) 4 else 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = asset.url,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = asset.timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 9.sp
                )
            }

            if (expanded) {
                Spacer(Modifier.height(2.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(ObsidianBg)
                        .padding(7.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (!asset.localRelativePath.isNullOrBlank()) {
                        Text(
                            text = "Local: filesDir/${asset.localRelativePath}  |  SHA-256: ${asset.sha256Prefix}…",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = SignalEmerald,
                            fontSize = 9.sp
                        )
                    }
                    if (asset.structuralDetails.isNotBlank()) {
                        Text(
                            text = asset.structuralDetails,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 10.sp,
                            lineHeight = 14.sp
                        )
                    }
                    if (asset.hexHeaderPreview.isNotBlank()) {
                        Text(
                            text = "ByteBuffer Header (first 32B):\n${asset.hexHeaderPreview}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = ElectricCyan.copy(alpha = 0.9f),
                            fontSize = 9.sp,
                            lineHeight = 13.sp
                        )
                    }
                }
            }
        }
    }
}

private fun extensionAccent(ext: String): Color = when (ext.lowercase(Locale.US)) {
    ".wasm" -> TelemetryViolet
    ".bin" -> ElectricCyan
    ".json" -> SignalEmerald
    else -> AssetAmber
}
