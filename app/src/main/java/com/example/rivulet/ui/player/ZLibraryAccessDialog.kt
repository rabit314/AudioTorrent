package com.example.rivulet.ui.player

import androidx.compose.runtime.Composable

/** Backwards-compatible delegate to [SiteAccessDialog]. */
@Composable
fun ZLibraryAccessDialog(
  url: String,
  onDismiss: () -> Unit,
  onReady: (String, String) -> Unit,
  onImportLocalFile: (() -> Unit)? = null,
) {
  SiteAccessDialog(
    url = url,
    onDismiss = onDismiss,
    onReady = onReady,
    onImportLocalFile = onImportLocalFile,
  )
}
