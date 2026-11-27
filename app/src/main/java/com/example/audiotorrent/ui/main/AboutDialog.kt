package com.example.audiotorrent.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.OfflineBolt
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.audiotorrent.BuildConfig
import com.example.audiotorrent.R

object AppLinks {
  const val GITHUB_REPO = "https://github.com/rabit314/AudioTorrent"
  const val GITHUB_RELEASES = "https://github.com/rabit314/AudioTorrent/releases"
  const val GITHUB_ISSUES = "https://github.com/rabit314/AudioTorrent/issues/new/choose"
}

data class ReleaseNote(
  val version: String,
  val subtitle: String,
  val highlights: List<String>,
)

val APP_CHANGELOG = listOf(
  ReleaseNote(
    version = "v0.4.0-beta (Build 5)",
    subtitle = "Current · Whole-App Themes, UI Polish, Performance & Read-Along Overhaul",
    highlights = listOf(
      "System-wide Material 3 theme engine (Black & Blue, Kindle Paper, Warm Sepia, Soft Sage, OLED Midnight) with adaptive light/dark system bars.",
      "Swipe-first Cover/Text player, streamlined single-row reader header with T-settings menu, and unified Correct sync tools.",
      "On-device Whisper Read-Along matching across Gutenberg, LibriVox, Z-Library, and EPUB/TXT/LRC/SRT files with lean SQLite timing storage and gap interpolation.",
      "4 charger-independent sync horizons (Playhead 1m/40s, Balanced 5m/3m, Extended 15m/10m, Full track) plus clickable sync coverage breakdown.",
      "Chapter TOC search, quote bookmarks, per-book playback speed memory, and major idle CPU & thermal reductions.",
    ),
  ),
  ReleaseNote(
    version = "v0.3.0-beta (Build 4)",
    subtitle = "Oct 24, 2026 · AudiobookBay Discovery & Library",
    highlights = listOf(
      "AudiobookBay trending shelf & full-catalog search with recent query history and want-to-listen wishlist.",
      "Listening history timeline grouped by date, natural chapter track ordering, and smart resume positions.",
    ),
  ),
  ReleaseNote(
    version = "v0.2.0-beta (Build 3)",
    subtitle = "Oct 23, 2026 · Streaming Engine & Media3 Player",
    highlights = listOf(
      "Zero-wait sequential BitTorrent audio streaming over local HTTP range bridge (libtorrent4j).",
      "Media3 foreground service notifications, Bluetooth controls, playback speed, and Voice Boost.",
    ),
  ),
)

@Composable
fun ChangelogDialog(
  onOpenReleases: () -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.NewReleases, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text("Changelog")
      }
    },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        APP_CHANGELOG.forEachIndexed { index, release ->
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (index == 0) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface,
            border = BorderStroke(
              if (index == 0) 1.dp else 0.75.dp,
              if (index == 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant,
            ),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
              Text(
                release.version,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (index == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
              )
              Text(
                release.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
              release.highlights.forEach { bullet ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                  Text("•", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                  Text(
                    bullet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 16.sp,
                  )
                }
              }
            }
          }
        }
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    dismissButton = {
      TextButton(onClick = onOpenReleases) {
        Text("GitHub Releases")
        Spacer(Modifier.width(4.dp))
        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
      }
    },
  )
}

@Composable
fun AboutDialog(
  onDismiss: () -> Unit,
  onOpenChangelog: () -> Unit = {},
) {
  val uriHandler = LocalUriHandler.current
  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Card(
      modifier = Modifier
        .fillMaxWidth(0.92f)
        .padding(vertical = 24.dp),
      shape = RoundedCornerShape(24.dp),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
      colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
      ),
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(24.dp)
          .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        // Top row with close icon
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End,
        ) {
          IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(32.dp),
          ) {
            Icon(
              Icons.Default.Close,
              contentDescription = "Close",
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        // App Logo
        Image(
          painter = painterResource(R.drawable.app_logo),
          contentDescription = "AudioTorrent Logo",
          modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(16.dp)),
        )

        Spacer(Modifier.height(12.dp))

        Text(
          text = "AudioTorrent",
          style = MaterialTheme.typography.headlineMedium,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(4.dp))

        // Version Badge
        Surface(
          color = MaterialTheme.colorScheme.primaryContainer,
          shape = RoundedCornerShape(12.dp),
        ) {
          Text(
            text = "v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
          )
        }

        Spacer(Modifier.height(8.dp))

        Text(
          text = "Streaming Torrent Audiobook Player with Synchronized Read-Along",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(horizontal = 8.dp),
        )

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
        Spacer(Modifier.height(16.dp))

        // Feature Highlights
        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          FeatureRow(
            icon = Icons.Default.OfflineBolt,
            title = "Zero-Wait Streaming Engine",
            description = "High-priority sequential piece streaming powered by native libtorrent4j.",
          )
          FeatureRow(
            icon = Icons.AutoMirrored.Filled.MenuBook,
            title = "Companion Ebook Read-Along",
            description = "Native ebook lookup, EPUB/TXT imports and on-phone English narration matching with saved sync corrections.",
          )
          FeatureRow(
            icon = Icons.Default.Headphones,
            title = "Background Audio & UX",
            description = "Media3 MediaSessionService, silence skipping, and speed adjustment.",
          )
          FeatureRow(
            icon = Icons.Default.Storage,
            title = "Durable Offline Library",
            description = "SQLite resume positions, listening history, bookmarks, and downloads.",
          )
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
        Spacer(Modifier.height(12.dp))

        // System Info
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text("Package", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Text("com.example.audiotorrent", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(4.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text("Compatibility", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Text("Android 8.0+ (API 26–36)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
        }

        Spacer(Modifier.height(16.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          OutlinedButton(
            onClick = { uriHandler.openUri(AppLinks.GITHUB_REPO) },
            modifier = Modifier.weight(1f),
          ) {
            Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("GitHub", maxLines = 1)
          }
          OutlinedButton(
            onClick = {
              onDismiss()
              onOpenChangelog()
            },
            modifier = Modifier.weight(1f),
          ) {
            Icon(Icons.Default.NewReleases, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Changelog", maxLines = 1)
          }
        }

        Spacer(Modifier.height(10.dp))

        FilledTonalButton(
          onClick = onDismiss,
          modifier = Modifier.fillMaxWidth(),
          colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
          ),
        ) {
          Text("Close", fontWeight = FontWeight.Bold)
        }
      }
    }
  }
}

@Composable
private fun FeatureRow(
  icon: ImageVector,
  title: String,
  description: String,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.Top,
  ) {
    Surface(
      color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
      shape = RoundedCornerShape(8.dp),
      modifier = Modifier.size(32.dp),
    ) {
      Box(contentAlignment = Alignment.Center) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onPrimaryContainer,
          modifier = Modifier.size(18.dp),
        )
      }
    }
    Spacer(Modifier.width(12.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
        lineHeight = 15.sp,
      )
    }
  }
}
