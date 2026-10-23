package com.example.rivulet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.example.rivulet.theme.RivuletTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val container = (application as RivuletApp).container

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
      }
    }

    enableEdgeToEdge()
    val startInPlayer = intent?.getBooleanExtra("openPlayer", false) ?: false
    setContent {
      RivuletTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          MainNavigation(container, startInPlayer = startInPlayer)
        }
      }
    }

    if (savedInstanceState == null) {
      handleIntent(intent)
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleIntent(intent)
  }

  private fun handleIntent(intent: Intent?) {
    val container = (application as RivuletApp).container
    when (intent?.action) {
      Intent.ACTION_VIEW -> {
        val uri = intent.data ?: return
        when {
          uri.scheme.equals("magnet", ignoreCase = true) -> {
            lifecycleScope.launch {
              container.engine
                .addMagnet(uri.toString())
                .onSuccess { container.pendingTorrent.value = it }
            }
          }
          intent.type == "application/x-bittorrent" ||
            uri.path?.endsWith(".torrent", ignoreCase = true) == true -> {
            lifecycleScope.launch {
              val bytes = withContext(Dispatchers.IO) {
                runCatching {
                  contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
              }
              if (bytes != null) {
                container.engine
                  .addTorrentFile(bytes)
                  .onSuccess { container.pendingTorrent.value = it }
              }
            }
          }
        }
      }
      Intent.ACTION_SEND -> {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        if (text != null && text.startsWith("magnet:", ignoreCase = true)) {
          lifecycleScope.launch {
            container.engine
              .addMagnet(text)
              .onSuccess { container.pendingTorrent.value = it }
          }
        }
      }
    }
  }
}
