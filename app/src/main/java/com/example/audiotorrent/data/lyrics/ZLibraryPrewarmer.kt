package com.example.audiotorrent.data.lyrics

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Headless WebView pre-warming service for Z-Library.
 *
 * Rotates quickly past stalled mirrors (`PER_MIRROR_TIMEOUT_MS`) so standard browser
 * checks (`c_token`, `cf_clearance`, `DiamWall`) populate CookieManager before native OkHttp calls.
 */
object ZLibraryPrewarmer {
  private const val TAG = "ZLibPrewarmer"
  private const val COOLDOWN_MS = 20 * 60 * 1000L
  private const val TIMEOUT_MS = 24_000L
  private const val PER_MIRROR_TIMEOUT_MS = 7_000L

  private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
  private var isWarming = false
  private var lastWarmedAt = 0L
  private var headlessWebView: WebView? = null
  private val completionCallbacks = mutableListOf<(Boolean) -> Unit>()

  fun warmUp(
    context: Context,
    forceRefresh: Boolean = false,
    targetUrl: String? = null,
    onComplete: ((Boolean) -> Unit)? = null,
  ) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      mainHandler.post { warmUp(context, forceRefresh, targetUrl, onComplete) }
      return
    }

    val now = System.currentTimeMillis()
    val hasValidCookies = hasClearanceCookie(targetUrl)

    if (!forceRefresh && hasValidCookies && (now - lastWarmedAt < COOLDOWN_MS)) {
      onComplete?.invoke(true)
      return
    }

    if (isWarming) {
      if (onComplete != null) completionCallbacks.add(onComplete)
      return
    }

    if (onComplete != null) completionCallbacks.add(onComplete)
    isWarming = true

    try {
      startHeadlessLoad(context.applicationContext, targetUrl)
    } catch (t: Throwable) {
      Log.d(TAG, "Pre-warming unavailable: ${t.message}")
      finish(false)
    }
  }

  private var activePollRunnable: Runnable? = null
  private var activeTimeoutRunnable: Runnable? = null
  private var activeMirrorTimer: Runnable? = null

  @SuppressLint("SetJavaScriptEnabled")
  private fun startHeadlessLoad(context: Context, targetUrl: String?) {
    val preferredOrigin = targetUrl?.let { raw ->
      runCatching { raw.toHttpUrl().let { "${it.scheme}://${it.host}" } }.getOrNull()
    }?.takeIf { !it.contains("zlib.li", ignoreCase = true) }
    val candidates = (listOfNotNull(preferredOrigin) + ZLibraryClient.MIRRORS).distinct()
    var mirrorIdx = 0

    val timeoutRunnable = Runnable {
      Log.d(TAG, "Pre-warming timed out")
      finish(hasStrongClearanceCookie(targetUrl))
    }
    activeTimeoutRunnable = timeoutRunnable

    val webView = try {
      WebView(context)
    } catch (t: Throwable) {
      Log.d(TAG, "Could not create headless WebView: ${t.message}")
      mainHandler.removeCallbacks(timeoutRunnable)
      activeTimeoutRunnable = null
      finish(false)
      return
    }

    headlessWebView = webView

    fun scheduleMirrorWatchdog(view: WebView) {
      activeMirrorTimer?.let { mainHandler.removeCallbacks(it) }
      val timer = Runnable {
        if (!isWarming || headlessWebView !== view) return@Runnable
        val nextIdx = ++mirrorIdx
        if (nextIdx < candidates.size) {
          Log.d(TAG, "Mirror ${candidates[nextIdx - 1]} stalled; switching to ${candidates[nextIdx]}")
          scheduleMirrorWatchdog(view)
          view.loadUrl(candidates[nextIdx])
        }
      }
      activeMirrorTimer = timer
      mainHandler.postDelayed(timer, PER_MIRROR_TIMEOUT_MS)
    }

    fun evaluateReadiness(view: WebView, attempt: Int = 0) {
      if (!isWarming || headlessWebView !== view) return
      runCatching { CookieManager.getInstance().flush() }
      view.evaluateJavascript(
        "JSON.stringify([document.title || '', Boolean(document.querySelector('z-bookcard, .resItemBox, .bookRow, a[href*=\"/book/\"], input[name=q], input[name=query], form[action*=\"/s/\"]'))])"
      ) { rawResult ->
        if (!isWarming || headlessWebView !== view) return@evaluateJavascript
        val (title, hasShell) = runCatching {
          val unquoted = org.json.JSONArray("[$rawResult]").optString(0, "")
          val arr = org.json.JSONArray(unquoted)
          arr.optString(0, "").lowercase() to arr.optBoolean(1, false)
        }.getOrDefault("" to false)
        val isChallenge = isChallengeTitle(title)
        val hasStrongCookie = hasStrongClearanceCookie(view.url ?: targetUrl)
        val ready = !isChallenge && title.isNotBlank() && (hasShell || hasStrongCookie)
        if (ready) {
          Log.d(TAG, "Pre-warming succeeded after $attempt polls (${view.url})")
          mainHandler.removeCallbacks(timeoutRunnable)
          activeMirrorTimer?.let { mainHandler.removeCallbacks(it) }
          lastWarmedAt = System.currentTimeMillis()
          finish(true)
        } else if (attempt < 44) {
          val poll = Runnable { evaluateReadiness(view, attempt + 1) }
          activePollRunnable = poll
          mainHandler.postDelayed(poll, 450L)
        }
      }
    }

    try {
      webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        userAgentString = ZLibraryClient.USER_AGENT
      }
      webView.onResume()
      webView.resumeTimers()
      runCatching { CookieManager.getInstance().setAcceptCookie(true) }

      webView.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
          runCatching { ZLibraryClient.validateUrl(request.url.toString()); false }.getOrDefault(true)

        override fun onPageFinished(view: WebView, loadedUrl: String) {
          activePollRunnable?.let { mainHandler.removeCallbacks(it) }
          evaluateReadiness(view, 0)
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
          if (request.isForMainFrame && errorResponse.statusCode !in setOf(429, 503, 513) && errorResponse.statusCode >= 500) {
            val nextIdx = ++mirrorIdx
            if (nextIdx < candidates.size) {
              scheduleMirrorWatchdog(view)
              view.loadUrl(candidates[nextIdx])
            }
          }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
          if (!request.isForMainFrame) return
          val nextIdx = ++mirrorIdx
          if (nextIdx < candidates.size) {
            scheduleMirrorWatchdog(view)
            view.loadUrl(candidates[nextIdx])
          } else {
            mainHandler.removeCallbacks(timeoutRunnable)
            activeMirrorTimer?.let { mainHandler.removeCallbacks(it) }
            finish(hasStrongClearanceCookie(targetUrl))
          }
        }
      }

      mainHandler.postDelayed(timeoutRunnable, TIMEOUT_MS)
      scheduleMirrorWatchdog(webView)
      webView.loadUrl(candidates[mirrorIdx])
    } catch (t: Throwable) {
      Log.d(TAG, "Failed to start WebView load: ${t.message}")
      mainHandler.removeCallbacks(timeoutRunnable)
      finish(false)
    }
  }

  fun hasClearanceCookie(targetUrl: String? = null): Boolean = runCatching {
    val cm = CookieManager.getInstance()
    val urls = (listOfNotNull(targetUrl) + ZLibraryClient.MIRRORS).distinct()
    urls.any { url -> hasClearanceToken(cm.getCookie(url)) }
  }.getOrDefault(false)

  private fun hasStrongClearanceCookie(targetUrl: String? = null): Boolean = runCatching {
    val cm = CookieManager.getInstance()
    val urls = (listOfNotNull(targetUrl) + ZLibraryClient.MIRRORS).distinct()
    urls.any { url -> hasTokenNamed(cm.getCookie(url), STRONG_CLEARANCE_COOKIE_NAMES) }
  }.getOrDefault(false)

  internal fun hasClearanceToken(cookie: String?): Boolean = hasTokenNamed(cookie, CLEARANCE_COOKIE_NAMES)

  private fun hasTokenNamed(cookie: String?, allowedNames: Set<String>): Boolean {
    if (cookie.isNullOrBlank()) return false
    return cookie.split(';').any { part ->
      val eq = part.indexOf('=')
      if (eq <= 0) false
      else {
        val name = part.substring(0, eq).trim()
        val value = part.substring(eq + 1).trim()
        value.isNotEmpty() && name in allowedNames
      }
    }
  }

  internal fun isChallengeTitle(rawTitle: String): Boolean {
    val title = rawTitle.lowercase()
    return title.contains("just a moment") ||
      title.contains("checking your browser") ||
      title.contains("verifying your browser") ||
      title.contains("verify you are human") ||
      title.contains("verify you're human") ||
      title.contains("wait a moment") ||
      title.contains("attention required") ||
      title.contains("performing security verification") ||
      title.contains("security verification") ||
      title.contains("diamwall") ||
      title.contains("ddos")
  }

  private val STRONG_CLEARANCE_COOKIE_NAMES = setOf("c_token", "cf_clearance", "remix_userkey")
  private val CLEARANCE_COOKIE_NAMES = STRONG_CLEARANCE_COOKIE_NAMES + setOf("diamwall", "__diamwall")

  private fun finish(success: Boolean) {
    activeTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
    activeTimeoutRunnable = null
    activeMirrorTimer?.let { mainHandler.removeCallbacks(it) }
    activeMirrorTimer = null
    activePollRunnable?.let { mainHandler.removeCallbacks(it) }
    activePollRunnable = null
    runCatching { CookieManager.getInstance().flush() }
    runCatching {
      headlessWebView?.stopLoading()
      headlessWebView?.pauseTimers()
      headlessWebView?.webViewClient = WebViewClient()
      headlessWebView?.destroy()
    }
    headlessWebView = null
    isWarming = false

    val callbacks = completionCallbacks.toList()
    completionCallbacks.clear()
    callbacks.forEach { cb ->
      runCatching { cb(success) }
    }
  }
}
