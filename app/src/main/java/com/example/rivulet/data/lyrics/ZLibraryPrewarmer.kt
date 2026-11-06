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
  fun warmUp(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      mainHandler.post { warmUp(context, onComplete) }
      return
    }

    val now = System.currentTimeMillis()
    val hasValidCookies = hasClearanceCookie()

    if (hasValidCookies && (now - lastWarmedAt < COOLDOWN_MS)) {
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

  @SuppressLint("SetJavaScriptEnabled")
  private fun startHeadlessLoad(context: Context) {
    val timeoutRunnable = Runnable {
      Log.d(TAG, "Pre-warming timed out")
      finish(hasClearanceCookie())
    }

    val webView = try {
      WebView(context)
    } catch (t: Throwable) {
      Log.d(TAG, "Could not create headless WebView: ${t.message}")
      mainHandler.removeCallbacks(timeoutRunnable)
      finish(false)
      return
    }

    headlessWebView = webView

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
          runCatching { CookieManager.getInstance().flush() }
          view.evaluateJavascript("document.title") { rawTitle ->
            val title = rawTitle?.lowercase()?.trim('"').orEmpty()
            val isChallenge = title.contains("just a moment") || title.contains("checking your browser")
            val ready = !isChallenge && hasClearanceCookie()
            if (ready) {
              mainHandler.removeCallbacks(timeoutRunnable)
              lastWarmedAt = System.currentTimeMillis()
              finish(true)
            }
          }
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
    val cookie = CookieManager.getInstance().getCookie(ZLibraryClient.BASE)
    cookie != null && (cookie.contains("cf_clearance") || cookie.contains("remix_userkey") || cookie.isNotBlank())
  }.getOrDefault(false)

  private fun finish(success: Boolean) {
    runCatching { CookieManager.getInstance().flush() }
    runCatching {
      headlessWebView?.stopLoading()
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
