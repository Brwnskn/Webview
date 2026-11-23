package com.example.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.example.R
import com.example.SavedSiteProfile
import com.example.SitePointerStore
import com.example.ui.theme.AlertCoral
import com.example.ui.theme.AssetAmber
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.TelemetryViolet

/**
 * Startup & Coords-tab card listing all saved sites with bookmarked pointer chains and/or
 * bound entity struct offsets persisted synchronously on disk.
 *
 * Visible immediately on app launch (even on `about:blank`) so users can verify their saved
 * pointers persisted across restarts and one-tap Open & Auto-Verify.
 */
@Composable
fun SavedSitesStartupCard(
    profiles: List<SavedSiteProfile>,
    activeUrl: String,
    onOpenProfile: (SavedSiteProfile) -> Unit,
    onDeleteProfile: (hostKey: String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (profiles.isEmpty()) return
    val activeHost = SitePointerStore.normalizeHostKey(activeUrl)

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = ObsidianSurface,
        border = BorderStroke(1.dp, AssetAmber.copy(alpha = 0.6f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("saved_sites_startup_card")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Bookmark,
                    contentDescription = null,
                    tint = AssetAmber,
                    modifier = Modifier.size(18.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${stringResource(R.string.saved_sites_title)} (${profiles.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = AssetAmber
                    )
                    Text(
                        text = stringResource(R.string.saved_sites_subtitle),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            profiles.forEach { profile ->
                val isCurrentSite = activeHost != null && (
                    activeHost == profile.hostKey ||
                        SitePointerStore.extractRegistrableDomain(activeHost) ==
                        SitePointerStore.extractRegistrableDomain(profile.hostKey)
                    )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isCurrentSite) SignalEmerald.copy(alpha = 0.10f) else ObsidianBg,
                    border = BorderStroke(
                        1.dp,
                        if (isCurrentSite) SignalEmerald.copy(alpha = 0.6f) else ObsidianBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("saved_site_row_${profile.hostKey}")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Text(
                                    text = profile.hostKey,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isCurrentSite) SignalEmerald else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (isCurrentSite) {
                                    Surface(
                                        color = SignalEmerald.copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = "ACTIVE",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = SignalEmerald,
                                            fontSize = 8.sp,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }

                            val statsSummary = buildList {
                                if (profile.pointerCount > 0) {
                                    add("${profile.pointerCount} pointer${if (profile.pointerCount == 1) "" else "s"}")
                                }
                                if (profile.boundStructFieldCount > 0) {
                                    add("${profile.boundStructFieldCount} struct offset${if (profile.boundStructFieldCount == 1) "" else "s"}")
                                }
                            }.joinToString(" • ")

                            Text(
                                text = statsSummary,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetBrainsMonoFontFamily,
                                color = ElectricCyan,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            if (profile.pointerExpressions.isNotEmpty()) {
                                Text(
                                    text = profile.pointerExpressions.joinToString("  |  "),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 9.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        if (!isCurrentSite) {
                            Button(
                                onClick = { onOpenProfile(profile) },
                                shape = RoundedCornerShape(7.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ElectricCyan,
                                    contentColor = ObsidianBg
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .heightIn(min = 44.dp)
                                    .testTag("open_saved_site_${profile.hostKey}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Launch,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.action_open_and_verify),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        IconButton(
                            onClick = { onDeleteProfile(profile.hostKey) },
                            modifier = Modifier
                                .size(44.dp)
                                .testTag("delete_saved_site_${profile.hostKey}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = stringResource(R.string.cd_delete_saved_site),
                                tint = AlertCoral.copy(alpha = 0.85f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Reusable spacious segmented toggle pill used for sub-modes inside the `Prober` and `Logs/Assets` tabs.
 */
@Composable
fun ConsoleSubTabSegmentPill(
    label: String,
    selected: Boolean,
    accent: Color,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) accent.copy(alpha = 0.22f) else ObsidianBg,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) accent else ObsidianBorder
        ),
        modifier = modifier
            .heightIn(min = 44.dp)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
