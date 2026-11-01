package com.example.rivulet.data.catalog

import com.example.rivulet.data.lyrics.BookImport
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.CookieJar
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CatalogHttpException(val status: Int, host: String) : IOException("$host returned HTTP $status. Please try again later.")

/** Public clients never share the authenticated catalog's cookies. Every redirect is checked. */
class PublicCatalogHttp(private val domains: Set<String>, client: OkHttpClient = OkHttpClient()) {
  private val client = client.newBuilder().cookieJar(CookieJar.NO_COOKIES).connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(25, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS)
    .addNetworkInterceptor { chain ->
      try { validate(chain.request().url.toString()) }
      catch (error: IllegalArgumentException) { throw IOException("Unsupported catalog redirect.", error) }
      if (!chain.request().url.isHttps) throw IOException("Insecure catalog redirect.")
      chain.proceed(chain.request().newBuilder().removeHeader("Cookie").build())
    }.build()

  fun validate(raw: String): String {
    val url = raw.toHttpUrl()
    require(url.scheme in setOf("http", "https") && domains.any { url.host == it || url.host.endsWith(".$it") } &&
      url.username.isEmpty() && url.password.isEmpty() && url.port in setOf(80, 443)) { "Unsupported catalog link." }
    return url.newBuilder().scheme("https").port(443).build().toString()
  }

  fun bytes(raw: String, limit: Int = 4 * 1024 * 1024): ByteArray {
    return call(raw).execute().use { read(it, limit) }
  }

  suspend fun bytesAsync(raw: String, limit: Int = 4 * 1024 * 1024): ByteArray = suspendCancellableCoroutine { continuation ->
    val call = call(raw)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
      override fun onResponse(call: Call, response: Response) {
        try {
          val bytes = response.use { read(it, limit) }
          if (continuation.isActive) continuation.resume(bytes)
        } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
      }
    })
  }
  private fun call(raw: String): Call = client.newCall(Request.Builder().url(validate(raw))
    .header("User-Agent", "AudioTorrent/0.3 public-catalog client").build())
  private fun read(response: Response, limit: Int): ByteArray {
    if (!response.isSuccessful) throw CatalogHttpException(response.code, response.request.url.host)
    val body = response.body ?: throw IOException("The catalog returned an empty response.")
    if (body.contentLength() > limit) throw IOException("The catalog response is too large.")
    return body.byteStream().use { BookImport.read(it, limit) }
  }

  fun text(url: String): String = bytes(url).toString(Charsets.UTF_8)
  suspend fun textAsync(url: String): String = bytesAsync(url).toString(Charsets.UTF_8)

  fun <T> range(url: String, from: Long, to: Long, block: (Response) -> T): T {
    require(from >= 0 && to >= from)
    val request = Request.Builder().url(validate(url)).header("Range", "bytes=$from-$to")
      .header("User-Agent", "AudioTorrent/0.3 public-webseed client").build()
    return client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) throw CatalogHttpException(response.code, response.request.url.host)
      block(response)
    }
  }
}
