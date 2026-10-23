package com.example.rivulet.ui.player

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Log
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ZLibBrowserDialog(
  initialQuery: String? = null,
  onDismiss: () -> Unit,
  onBookDownloaded: (bytes: ByteArray, fileName: String) -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var webView: WebView? by remember { mutableStateOf(null) }
  var isLoading by remember { mutableStateOf(true) }
  var pageTitle by remember { mutableStateOf("Z-Library (zlib.li)") }

  val targetUrl = remember {
    if (!initialQuery.isNullOrBlank()) {
      val encoded = java.net.URLEncoder.encode(initialQuery.trim(), "UTF-8")
      "https://zlib.li/s/$encoded"
    } else {
      "https://zlib.li/"
    }
  }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(
      modifier = Modifier.fillMaxSize(),
      color = Color(0xFF0F1115),
    ) {
      Column(modifier = Modifier.fillMaxSize()) {
        // Dialog Top Bar
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF181C26))
            .padding(horizontal = 8.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
          }

          Icon(
            Icons.Default.Language,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
          )
          Spacer(Modifier.width(8.dp))

          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = pageTitle,
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.Bold,
              color = Color.White,
              maxLines = 1,
            )
            Text(
              text = "Solve challenge & download or read EPUB",
              style = MaterialTheme.typography.labelSmall,
              color = Color.Gray,
              fontSize = 10.sp,
            )
          }

          IconButton(
            onClick = {
              if (webView?.canGoBack() == true) webView?.goBack()
            }
          ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.LightGray)
          }

          IconButton(onClick = { webView?.reload() }) {
            Icon(Icons.Default.Refresh, contentDescription = "Reload", tint = Color.LightGray)
          }
        }

        // Web View Area
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
          AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
              WebView(ctx).apply {
                webView = this
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.userAgentString =
                  "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

                webViewClient = object : WebViewClient() {
                  override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    isLoading = true
                    pageTitle = view?.title ?: "Loading zlib.li..."
                  }

                  override fun onPageFinished(view: WebView?, url: String?) {
                    isLoading = false
                    pageTitle = view?.title ?: "Z-Library"
                  }

                  override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    return false
                  }
                }

                // Download listener to intercept EPUB downloads directly
                setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
                  val fileName = if (contentDisposition?.contains("filename=") == true) {
                    contentDisposition.substringAfter("filename=").replace("\"", "").trim()
                  } else {
                    url.substringAfterLast('/').substringBefore('?').ifBlank { "book.epub" }
                  }

                  Toast.makeText(context, "Downloading companion book: $fileName", Toast.LENGTH_SHORT).show()

                  scope.launch(Dispatchers.IO) {
                    try {
                      val cookie = CookieManager.getInstance().getCookie(url)
                      val okClient = OkHttpClient.Builder().build()
                      val reqBuilder = Request.Builder().url(url)
                      if (!cookie.isNullOrBlank()) {
                        reqBuilder.addHeader("Cookie", cookie)
                      }
                      if (!userAgent.isNullOrBlank()) {
                        reqBuilder.addHeader("User-Agent", userAgent)
                      }
                      val resp = okClient.newCall(reqBuilder.build()).execute()
                      val bytes = resp.body?.bytes()
                      if (bytes != null && bytes.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                          Toast.makeText(context, "Companion book attached successfully!", Toast.LENGTH_SHORT).show()
                          onBookDownloaded(bytes, fileName)
                          onDismiss()
                        }
                      }
                    } catch (t: Throwable) {
                      Log.e("ZLibBrowserDialog", "Failed to download companion book $url", t)
                      withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Download failed: ${t.message}", Toast.LENGTH_LONG).show()
                      }
                    }
                  }
                }

                loadUrl(targetUrl)
              }
            },
          )

          if (isLoading) {
            Box(
              modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)),
              contentAlignment = Alignment.Center,
            ) {
              CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
          }
        }
      }
    }
  }
}
