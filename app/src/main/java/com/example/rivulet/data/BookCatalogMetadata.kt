package com.example.rivulet.data

import android.content.Context

/** Catalog labels are identity hints, not torrent metadata; a root folder may name only the author. */
class BookCatalogMetadata(context: Context) {
  private val preferences = context.applicationContext.getSharedPreferences("book_catalog_metadata", Context.MODE_PRIVATE)
  fun title(hash: String): String? = preferences.getString(hash, null)
  fun save(hash: String, title: String) { preferences.edit().putString(hash, title.take(1000)).apply() }
  fun remove(hash: String) { preferences.edit().remove(hash).apply() }
}
