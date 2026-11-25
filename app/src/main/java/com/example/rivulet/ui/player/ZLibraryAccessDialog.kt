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
import com.example.rivulet.data.lyrics.ZLibraryPrewarmer
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Temporary access surface. It has no JavaScript bridge and never handles account credentials. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ZLibraryAccessDialog(url: String, onDismiss: () -> Unit, onReady: (String, String) -> Unit) {
  var webView by remember { mutableStateOf<WebView?>(null) }
  var loading by remember { mutableStateOf(true) }
  var completed by remember { mutableStateOf(false) }
  var autoNavigatedTarget by remember { mutableStateOf(false) }
  val ready by rememberUpdatedState(onReady)

  fun finish(view: WebView, resolvedOverride: String? = null) {
    if (completed) return
    completed = true
    CookieManager.getInstance().flush()
    view.evaluateJavascript("document.documentElement ? document.documentElement.outerHTML : ''") { value ->
      val html = runCatching {
        val parsed = org.json.JSONArray("[$value]")
        if (parsed.isNull(0)) "" else parsed.getString(0)
      }.getOrDefault("")
      ready(html.take(4 * 1024 * 1024), resolvedOverride ?: view.url ?: url)
    }
  }

  fun probeDom(view: WebView) {
    if (completed) return
    val js = """
      JSON.stringify([
        document.title || '',
        Boolean(document.querySelector('input[type=password]')),
        Boolean(document.querySelector('z-bookcard, .resItemBox, .bookRow, a[href*="/dl/"], a[href*="/download/"]')),
        Boolean(/no (books|matches|results)/i.test((document.body && document.body.innerText) || ''))
      ])
    """.trimIndent()
    view.evaluateJavascript(js) { raw ->
      if (completed) return@evaluateJavascript
      val (title, hasPassword, hasContent, hasNoResults) = runCatching {
        val unquoted = org.json.JSONArray("[$raw]")
        if (unquoted.isNull(0)) return@runCatching Quad("", false, false, false)
        val arr = org.json.JSONArray(unquoted.getString(0))
        Quad(arr.optString(0, ""), arr.optBoolean(1, false), arr.optBoolean(2, false), arr.optBoolean(3, false))
      }.getOrDefault(Quad("", false, false, false))

      val inChallenge = ZLibraryPrewarmer.isChallengeTitle(title)
      loading = inChallenge
      if (inChallenge || hasPassword) return@evaluateJavascript

      val currentUrl = view.url ?: return@evaluateJavascript
      val parsedCurrent = runCatching { currentUrl.toHttpUrl() }.getOrNull()
      val parsedTarget = runCatching { url.toHttpUrl() }.getOrNull()
      // If challenge/login landed on mirror home `/`, automatically navigate inside WebView to the requested search/book path.
      if (!autoNavigatedTarget &&
        parsedCurrent != null &&
        parsedTarget != null &&
        parsedTarget.encodedPath.length > 1 &&
        (parsedCurrent.encodedPath == "/" || parsedCurrent.encodedPath.isEmpty()) &&
        !hasContent
      ) {
        autoNavigatedTarget = true
        val redirected = parsedTarget.newBuilder()
          .scheme(parsedCurrent.scheme)
          .host(parsedCurrent.host)
          .build()
          .toString()
        loading = true
        view.loadUrl(redirected)
        return@evaluateJavascript
      }

      if (hasContent || (hasNoResults && currentUrl.contains("/s/"))) {
        finish(view)
      }
    }
  }

  LaunchedEffect(webView, completed) {
    val activeView = webView ?: return@LaunchedEffect
    while (!completed) {
      delay(450L)
      probeDom(activeView)
    }
  }

  DisposableEffect(Unit) {
    onDispose {
      webView?.stopLoading()
      webView?.pauseTimers()
      webView?.destroy()
    }
  }

  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Surface(Modifier.fillMaxSize()) {
      Column(Modifier.safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
          TextButton(onClick = onDismiss) { Text("Close") }
          TextButton(onClick = { loading = true; webView?.reload() }) { Text("Reload") }
          TextButton(onClick = { webView?.let { finish(it) } }) { Text("Continue in Rivulet") }
        }
        Text(
          "Complete the site’s browser check or sign in, then return to native book search.",
          Modifier.padding(horizontal = 16.dp),
          style = MaterialTheme.typography.bodySmall,
        )
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
            resumeTimers()
            CookieManager.getInstance().setAcceptCookie(true)
            setDownloadListener { downloadUrl, _, _, _, _ ->
              if (!completed && runCatching { ZLibraryClient.validateUrl(downloadUrl) }.isSuccess) {
                val safeHref = downloadUrl.replace("&", "&amp;").replace("\"", "&quot;")
                CookieManager.getInstance().flush()
                completed = true
                ready("<html><body><a href=\"$safeHref\" class=\"dlButton\">Download EPUB</a></body></html>", webView?.url ?: url)
              }
            }
            webViewClient = object : WebViewClient() {
              override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                runCatching { ZLibraryClient.validateUrl(request.url.toString()); false }.getOrDefault(true)
              override fun onPageFinished(view: WebView, loadedUrl: String) {
                probeDom(view)
              }
            }
            loadUrl(url)
          }
        })
      }
    }
  }
}

private data class Quad(val title: String, val hasPassword: Boolean, val hasContent: Boolean, val hasNoResults: Boolean)
