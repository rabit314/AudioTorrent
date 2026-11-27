package com.example.audiotorrent.ui.player

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.audiotorrent.data.lyrics.AnnasArchiveClient
import com.example.audiotorrent.data.lyrics.CatalogDownloadBridge
import com.example.audiotorrent.data.lyrics.CatalogHttpHelpers
import com.example.audiotorrent.data.lyrics.LibGenClient
import com.example.audiotorrent.data.lyrics.WeLibClient
import com.example.audiotorrent.data.lyrics.ZLibraryClient
import com.example.audiotorrent.data.lyrics.ZLibraryPrewarmer
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Visible user-interactive in-app browser for solving CAPTCHAs, Cloudflare, DDoS-Guard,
 * or site logins across Z-Library, LibGen, WeLib, and Anna's Archive.
 * Has no JS bridge and never handles or stores site account credentials.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SiteAccessDialog(
  url: String,
  onDismiss: () -> Unit,
  onReady: (String, String) -> Unit,
  onImportLocalFile: (() -> Unit)? = null,
) {
  val context = LocalContext.current
  var webView by remember { mutableStateOf<WebView?>(null) }
  var loading by remember { mutableStateOf(true) }
  var canGoBack by remember { mutableStateOf(false) }
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
    val currentUrl = view.url ?: return
    val currentHost = runCatching { currentUrl.toHttpUrl().host }.getOrDefault("")
    val isWeLib = WeLibClient.isAllowedHost(currentHost)
    val isAnnas = AnnasArchiveClient.isAllowedHost(currentHost)
    val isLibGen = LibGenClient.isAllowedHost(currentHost)

    val js = """
      JSON.stringify([
        document.title || '',
        Boolean(document.querySelector('input[type=password]')),
        Boolean(document.querySelector('z-bookcard, .resItemBox, .bookRow, a[href*="/dl/"], a[href*="/download/"], #tablelibgen a[href*="ads.php"], a[href*="get.php"], #aarecord-list .book-card a[href*="/md5/"], .js-aarecord-list-outer a[href*="/md5/"]')),
        Boolean(/no (books|matches|results)|nothing found/i.test((document.body && document.body.innerText) || '')),
        Boolean(document.querySelector('#md5-panel-downloads, a[href*="/slow_download/"], a[href*="/fast_download/"]')),
        (function() {
          var docs = [document];
          var bf = document.querySelector('iframe#bookIframe');
          try { if (bf && bf.contentDocument) docs.push(bf.contentDocument); } catch (e) {}
          for (var i = 0; i < docs.length; i++) {
            var d = docs[i];
            var f = d.querySelector('iframe#foliate-iframe');
            if (f && f.src) return f.src;
            var a = d.querySelector('a[href*="/fast_view"]');
            if (a && a.href) return a.href;
          }
          if (bf && bf.src && bf.src.indexOf('url=') !== -1) return bf.src;
          return '';
        })()
      ])
    """.trimIndent()

    view.evaluateJavascript(js) { raw ->
      if (completed) return@evaluateJavascript
      val probe = runCatching {
        val unquoted = org.json.JSONArray("[$raw]")
        if (unquoted.isNull(0)) return@runCatching SiteProbe("", false, false, false, false, "")
        val arr = org.json.JSONArray(unquoted.getString(0))
        SiteProbe(
          title = arr.optString(0, ""),
          hasPassword = arr.optBoolean(1, false),
          hasContent = arr.optBoolean(2, false),
          hasNoResults = arr.optBoolean(3, false),
          isDetailDownloadPage = arr.optBoolean(4, false),
          viewerSrc = arr.optString(5, ""),
        )
      }.getOrDefault(SiteProbe("", false, false, false, false, ""))

      val inChallenge = ZLibraryPrewarmer.isChallengeTitle(probe.title)
      loading = inChallenge
      canGoBack = view.canGoBack()
      if (inChallenge || probe.hasPassword) return@evaluateJavascript

      // When tapping "Read" on WeLib, intercept the Foliate/fast_view CDN book URL directly into AudioTorrent.
      if (isWeLib && probe.viewerSrc.isNotBlank()) {
        val previewBookUrl = WeLibClient.extractFileUrlFromViewerSrc(probe.viewerSrc)
        if (previewBookUrl != null) {
          CookieManager.getInstance().flush()
          completed = true
          val encoded = CatalogDownloadBridge.encode(
            downloadUrl = previewBookUrl,
            referer = currentUrl,
            userAgent = CatalogHttpHelpers.DEFAULT_USER_AGENT,
          )
          ready(encoded, currentUrl)
          return@evaluateJavascript
        }
      }

      val parsedCurrent = runCatching { currentUrl.toHttpUrl() }.getOrNull()
      val parsedTarget = runCatching { url.toHttpUrl() }.getOrNull()
      if (!autoNavigatedTarget &&
        parsedCurrent != null &&
        parsedTarget != null &&
        parsedTarget.encodedPath.length > 1 &&
        (parsedCurrent.encodedPath == "/" || parsedCurrent.encodedPath.isEmpty()) &&
        !probe.hasContent
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

      // On WeLib / Anna's Archive detail/slow_download pages, stay interactive so the user can tap Download / Slow download.
      if ((isWeLib || isAnnas) && (probe.isDetailDownloadPage || currentUrl.contains("/md5/") || currentUrl.contains("_download/"))) {
        return@evaluateJavascript
      }

      val isSearchUrl = currentUrl.contains("/s/") || currentUrl.contains("/search") || currentUrl.contains("index.php")
      val searchResultsReady = when {
        isWeLib -> isSearchUrl && (probe.hasContent || probe.hasNoResults)
        isAnnas -> isSearchUrl && (probe.hasContent || probe.hasNoResults)
        isLibGen -> probe.hasContent || (isSearchUrl && probe.hasNoResults)
        else -> probe.hasContent || (probe.hasNoResults && currentUrl.contains("/s/"))
      }
      if (searchResultsReady) {
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
        Row(
          Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Row {
            TextButton(onClick = onDismiss) { Text("Close") }
            if (canGoBack) {
              TextButton(onClick = { webView?.goBack() }) { Text("Back") }
            }
            TextButton(onClick = { loading = true; webView?.reload() }) { Text("Reload") }
          }
          Row {
            TextButton(onClick = {
              val activeUrl = webView?.url ?: url
              runCatching {
                CatalogHttpHelpers.validateHttpsUrl(activeUrl)
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(activeUrl)))
              }
            }) { Text("Browser") }
            if (onImportLocalFile != null) {
              TextButton(onClick = { onDismiss(); onImportLocalFile() }) { Text("Import") }
            }
            TextButton(onClick = { webView?.let { finish(it) } }) { Text("Use page") }
          }
        }
        Text(
          "Solve any CAPTCHA or browser check below, then wait for results or tap Download.",
          Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = { viewContext ->
          WebView(viewContext).apply {
            webView = this
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.userAgentString = CatalogHttpHelpers.DEFAULT_USER_AGENT
            resumeTimers()
            val targetWebView = this
            CookieManager.getInstance().apply {
              setAcceptCookie(true)
              setAcceptThirdPartyCookies(targetWebView, true)
            }
            webChromeClient = object : WebChromeClient() {
              override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (!completed && newProgress < 100) loading = true
              }
            }
            setDownloadListener { downloadUrl, userAgent, contentDisposition, mimeType, _ ->
              if (!completed && runCatching { CatalogHttpHelpers.validateHttpsUrl(downloadUrl) }.isSuccess) {
                CookieManager.getInstance().flush()
                completed = true
                val referer = webView?.url ?: url
                val encoded = CatalogDownloadBridge.encode(
                  downloadUrl = downloadUrl,
                  referer = referer,
                  userAgent = userAgent.ifBlank { CatalogHttpHelpers.DEFAULT_USER_AGENT },
                  filename = contentDisposition,
                  mimeType = mimeType,
                )
                ready(encoded, referer)
              }
            }
            webViewClient = object : WebViewClient() {
              override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val target = request.url.toString()
                if (!request.isForMainFrame) {
                  val scheme = request.url.scheme?.lowercase()
                  return scheme !in setOf("https", "about", "data", "blob")
                }
                return runCatching {
                  CatalogHttpHelpers.validateHttpsUrl(target)
                  false
                }.getOrDefault(true)
              }

              override fun onPageFinished(view: WebView, loadedUrl: String) {
                canGoBack = view.canGoBack()
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

private data class SiteProbe(
  val title: String,
  val hasPassword: Boolean,
  val hasContent: Boolean,
  val hasNoResults: Boolean,
  val isDetailDownloadPage: Boolean,
  val viewerSrc: String,
)
