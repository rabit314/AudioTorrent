package com.example.rivulet.ui.player

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.rivulet.data.lyrics.ZLibraryClient

/** Temporary access surface. It has no JavaScript bridge and never handles account credentials. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ZLibraryAccessDialog(url: String, onDismiss: () -> Unit, onReady: (String, String) -> Unit) {
  var webView by remember { mutableStateOf<WebView?>(null) }
  var loading by remember { mutableStateOf(true) }
  var completed by remember { mutableStateOf(false) }
  val ready by rememberUpdatedState(onReady)
  fun finish(view: WebView) {
    if (completed) return
    completed = true
    CookieManager.getInstance().flush()
    view.evaluateJavascript("document.documentElement.outerHTML") { value ->
      val html = runCatching { org.json.JSONArray("[$value]").getString(0) }.getOrDefault("")
      ready(html.take(4 * 1024 * 1024), view.url ?: url)
    }
  }
  DisposableEffect(Unit) { onDispose { webView?.stopLoading(); webView?.destroy() } }
  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Surface(Modifier.fillMaxSize()) {
      Column(Modifier.safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
          TextButton(onClick = onDismiss) { Text("Close") }
          TextButton(onClick = { webView?.reload() }) { Text("Reload") }
          TextButton(onClick = { webView?.let { finish(it) } }) { Text("Continue in Rivulet") }
        }
        Text("Complete the site’s browser check or sign in, then return to native book search.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = { context ->
          WebView(context).apply {
            webView = this
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.userAgentString = ZLibraryClient.USER_AGENT
            CookieManager.getInstance().setAcceptCookie(true)
            webViewClient = object : WebViewClient() {
              override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                runCatching { ZLibraryClient.validateUrl(request.url.toString()); false }.getOrDefault(true)
              override fun onPageFinished(view: WebView, loadedUrl: String) {
                loading = false
                // Require actual book/search content, not an empty challenge or redirect page.
                view.evaluateJavascript("Boolean(document.querySelector('z-bookcard, .resItemBox, .bookRow, a[href*=\"/book/\"], a[href*=\"/dl/\"]')) && !document.querySelector('input[type=password]')") { result ->
                  if (result == "true" && !completed) finish(view)
                }
              }
            }
            loadUrl(url)
          }
        })
      }
    }
  }
}
