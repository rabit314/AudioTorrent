package com.example.rivulet.ui.main

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.OfflineBolt
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.rivulet.BuildConfig
import com.example.rivulet.R

@Composable
fun AboutDialog(
  onDismiss: () -> Unit,
) {
  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Card(
      modifier = Modifier
        .fillMaxWidth(0.92f)
        .padding(vertical = 24.dp),
      shape = RoundedCornerShape(24.dp),
      colors = CardDefaults.cardColors(containerColor = Color(0xFF14171F)),
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
            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.LightGray)
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
          color = Color.White,
        )

        Spacer(Modifier.height(4.dp))

        // Version Badge
        Surface(
          color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
          shape = RoundedCornerShape(12.dp),
        ) {
          Text(
            text = "v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
          )
        }

        Spacer(Modifier.height(8.dp))

        Text(
          text = "Streaming Torrent Audiobook Player with Synchronized Read-Along",
          style = MaterialTheme.typography.bodySmall,
          color = Color.Gray,
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(horizontal = 8.dp),
        )

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = Color(0xFF262C3A), thickness = 1.dp)
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
            description = "Standard EPUB chapter alignment with zlib.li web companion and 1-tap sync.",
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
        HorizontalDivider(color = Color(0xFF262C3A), thickness = 1.dp)
        Spacer(Modifier.height(12.dp))

        // System Info
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text("Package", fontSize = 11.sp, color = Color.Gray)
          Text("com.example.rivulet", fontSize = 11.sp, color = Color.LightGray)
        }
        Spacer(Modifier.height(4.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text("Compatibility", fontSize = 11.sp, color = Color.Gray)
          Text("Android 8.0+ (API 26–36)", fontSize = 11.sp, color = Color.LightGray)
        }

        Spacer(Modifier.height(20.dp))

        FilledTonalButton(
          onClick = onDismiss,
          modifier = Modifier.fillMaxWidth(),
          colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
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
      color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
      shape = RoundedCornerShape(8.dp),
      modifier = Modifier.size(32.dp),
    ) {
      Box(contentAlignment = Alignment.Center) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
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
        color = Color.White,
      )
      Text(
        text = description,
        style = MaterialTheme.typography.bodySmall,
        color = Color.Gray,
        fontSize = 11.sp,
        lineHeight = 15.sp,
      )
    }
  }
}
