package com.example.rivulet.data.catalog

import com.example.rivulet.data.audiobookbay.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AudioSearchState(
  val query: String = "", val source: AudioBookSource? = null, val started: Boolean = false,
  val books: List<AudioBookItem> = emptyList(), val loading: Set<AudioBookSource> = emptySet(),
  val errors: Map<AudioBookSource, String> = emptyMap(), val pages: Map<AudioBookSource, Int> = emptyMap(),
  val more: Set<AudioBookSource> = emptySet(),
)

class AudioCatalogSearch(
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
  private val loader: suspend (AudioBookSource, String, Int) -> AudioCatalogPage,
) {
  private val mutable = MutableStateFlow(AudioSearchState())
  val state = mutable.asStateFlow()
  private var generation = 0L
  private val jobs = mutableMapOf<AudioBookSource, Job>()

  fun search(query: String, source: AudioBookSource? = null) {
    generation++
    jobs.values.forEach { it.cancel() }; jobs.clear()
    mutable.value = AudioSearchState(query.trim(), source, started = true)
    (source?.let { listOf(it) } ?: AudioBookSource.entries).forEach { load(it, 1) }
  }
  fun more() { mutable.value.more.filter { it !in mutable.value.loading }.forEach { load(it, (mutable.value.pages[it] ?: 0) + 1) } }
  fun retry(source: AudioBookSource) { if (source !in mutable.value.loading) load(source, (mutable.value.pages[source] ?: 0) + 1) }

  private fun load(source: AudioBookSource, page: Int) {
    val version = generation
    val query = mutable.value.query
    mutable.update { it.copy(loading = it.loading + source, errors = it.errors - source) }
    jobs[source] = scope.launch {
      try {
        val result = loader(source, query, page)
        ensureActive()
        if (version != generation) return@launch
        mutable.update { it.copy(books = AudiobookCatalog.rank(it.books + result.books, query), loading = it.loading - source,
          pages = it.pages + (source to page), more = if (result.hasMore) it.more + source else it.more - source) }
      } catch (error: CancellationException) { throw error }
      catch (error: Exception) {
        if (version == generation) mutable.update { it.copy(loading = it.loading - source,
          errors = it.errors + (source to (error.message ?: "Catalog unavailable."))) }
      }
    }
  }
}
