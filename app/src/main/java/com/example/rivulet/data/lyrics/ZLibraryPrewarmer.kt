package com.example.rivulet.data.lyrics

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Headless WebView pre-warming service for Z-Library.
 *
 * Runs passively in the background on app startup, text view entry, or on-demand
 * to resolve Cloudflare browser challenges and prime CookieManager with clearance
 * cookies, eliminating manual browser access prompts whenever possible.
 */
object ZLibraryPrewarmer {
  private const val TAG = "ZLibPrewarmer"
  private const val COOLDOWN_MS = 20 * 60 * 1000L // 20 minutes cooldown once warmed
  private const val TIMEOUT_MS = 20_000L

  private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
  private var isWarming = false
  private var lastWarmedAt = 0L
  private var headlessWebView: WebView? = null
  private val completionCallbacks = mutableListOf<(Boolean) -> Unit>()

  /**
   * Triggers a silent, headless pre-warm of Z-Library cookies.
   * Safe to call from any thread and handles headless/test environments gracefully.
   */
  fun warmUp(context: Context, forceRefresh: Boolean = false, onComplete: ((Boolean) -> Unit)? = null) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      mainHandler.post { warmUp(context, forceRefresh, onComplete) }
      return
    }

    val now = System.currentTimeMillis()
    val hasValidCookies = hasClearanceCookie()

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
      startHeadlessLoad(context.applicationContext)
    } catch (t: Throwable) {
      Log.d(TAG, "Pre-warming unavailable: ${t.message}")
      finish(false)
    }
  }

  private var activePollRunnable: Runnable? = null
  private var activeTimeoutRunnable: Runnable? = null

  @SuppressLint("SetJavaScriptEnabled")
  private fun startHeadlessLoad(context: Context) {
    val timeoutRunnable = Runnable {
      Log.d(TAG, "Pre-warming timed out")
      finish(hasClearanceCookie())
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

    fun evaluateReadiness(view: WebView, attempt: Int = 0) {
      if (!isWarming || headlessWebView !== view) return
      runCatching { CookieManager.getInstance().flush() }
      view.evaluateJavascript("document.title") { rawTitle ->
        if (!isWarming || headlessWebView !== view) return@evaluateJavascript
        val title = rawTitle?.lowercase()?.trim('"').orEmpty()
        val isChallenge = isChallengeTitle(title)
        val ready = !isChallenge && title.isNotBlank() && hasClearanceCookie()
        if (ready) {
          Log.d(TAG, "Pre-warming succeeded after ${attempt} polls")
          mainHandler.removeCallbacks(timeoutRunnable)
          lastWarmedAt = System.currentTimeMillis()
          finish(true)
        } else if (attempt < 36) {
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

        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
          mainHandler.removeCallbacks(timeoutRunnable)
          finish(hasClearanceCookie())
        }
      }

      mainHandler.postDelayed(timeoutRunnable, TIMEOUT_MS)
      webView.loadUrl(ZLibraryClient.BASE)
    } catch (t: Throwable) {
      Log.d(TAG, "Failed to start WebView load: ${t.message}")
      mainHandler.removeCallbacks(timeoutRunnable)
      finish(false)
    }
  }

  fun hasClearanceCookie(): Boolean = runCatching {
    hasClearanceToken(CookieManager.getInstance().getCookie(ZLibraryClient.BASE))
  }.getOrDefault(false)

  internal fun hasClearanceToken(cookie: String?): Boolean {
    if (cookie.isNullOrBlank()) return false
    return cookie.split(';').any { part ->
      val eq = part.indexOf('=')
      if (eq <= 0) false
      else {
        val name = part.substring(0, eq).trim()
        val value = part.substring(eq + 1).trim()
        value.isNotEmpty() && name in CLEARANCE_COOKIE_NAMES
      }
    }
  }

  internal fun isChallengeTitle(rawTitle: String): Boolean {
    val title = rawTitle.lowercase()
    return title.contains("just a moment") ||
      title.contains("checking your browser") ||
      title.contains("wait a moment")
  }

  private val CLEARANCE_COOKIE_NAMES = setOf("c_token", "cf_clearance", "remix_userkey")

  private fun finish(success: Boolean) {
    activeTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
    activeTimeoutRunnable = null
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
