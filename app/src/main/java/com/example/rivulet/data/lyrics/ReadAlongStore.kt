package com.example.rivulet.data.lyrics

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.example.rivulet.data.TorrentDatabase

/** Persistent source and immutable base timestamps; user offsets are stored separately. */
class ReadAlongStore(private val database: TorrentDatabase) {
  fun attach(track: ReadAlongTrack, stored: StoredReadAlongBook, fileIndices: Set<Int>) {
    require(fileIndices.isNotEmpty()) { "Select at least one audio track." }
    val db = database.writableDatabase
    db.beginTransaction()
    try {
      db.insertWithOnConflict("readalong_books", null, ContentValues().apply {
        put("id", stored.id); put("info_hash", track.infoHash); put("title", stored.book.title)
        put("author", stored.book.author); put("isbn", stored.book.isbn); put("file_name", stored.fileName)
        put("source", stored.source.name)
      }, SQLiteDatabase.CONFLICT_REPLACE)
      db.delete("readalong_chapters", "book_id = ?", arrayOf(stored.id))
      stored.book.chapters.forEach { chapter ->
        db.insertOrThrow("readalong_chapters", null, ContentValues().apply {
          put("book_id", stored.id); put("chapter_index", chapter.index); put("chapter_id", chapter.id)
          put("href", chapter.href); put("title", chapter.title); put("content", chapter.text); put("word_count", chapter.wordCount)
        })
      }
      fileIndices.forEach { index ->
        db.insertWithOnConflict("readalong_tracks", null, ContentValues().apply {
          put("info_hash", track.infoHash); put("file_index", index); put("book_id", stored.id); put("offset_ms", 0L)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        db.delete("readalong_timings", "info_hash = ? AND file_index = ?", arrayOf(track.infoHash, index.toString()))
        db.delete("readalong_windows", "info_hash = ? AND file_index = ?", arrayOf(track.infoHash, index.toString()))
        db.delete("page_text_cache", "info_hash = ? AND file_index = ?", arrayOf(track.infoHash, index.toString()))
      }
      db.setTransactionSuccessful()
    } finally { db.endTransaction() }
  }

  fun book(track: ReadAlongTrack): StoredReadAlongBook? {
    val db = database.readableDatabase
    val row = db.rawQuery("SELECT b.id,b.title,b.author,b.isbn,b.file_name,b.source FROM readalong_books b JOIN readalong_tracks t ON t.book_id=b.id WHERE t.info_hash=? AND t.file_index=?", arrayOf(track.infoHash, track.fileIndex.toString())).use { c ->
      if (!c.moveToFirst()) return null
      arrayOf(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5))
    }
    val chapters = db.query("readalong_chapters", null, "book_id=?", arrayOf(row[0]), null, null, "chapter_index ASC").use { c ->
      buildList { while (c.moveToNext()) add(EpubChapter(
        c.getInt(c.getColumnIndexOrThrow("chapter_index")), c.getString(c.getColumnIndexOrThrow("chapter_id")),
        c.getString(c.getColumnIndexOrThrow("href")), c.getString(c.getColumnIndexOrThrow("title")),
        c.getString(c.getColumnIndexOrThrow("content")), c.getInt(c.getColumnIndexOrThrow("word_count")),
      )) }
    }
    return StoredReadAlongBook(row[0], EpubBook(row[1], row[2] ?: "Unknown Author", chapters, row[3]), row[4], TextSyncSource.valueOf(row[5]))
  }

  fun associatedTracks(bookId: String): Set<Int> = database.readableDatabase.query("readalong_tracks", arrayOf("file_index"), "book_id=?", arrayOf(bookId), null, null, null).use { c ->
    buildSet { while (c.moveToNext()) add(c.getInt(0)) }
  }

  fun setTrackSelection(track: ReadAlongTrack, bookId: String, indices: Set<Int>) {
    val db = database.writableDatabase
    db.beginTransaction()
    try {
      val current = book(track)?.id
      if (current != bookId) return
      val selected = indices + track.fileIndex
      associatedTracks(bookId).filter { it !in selected }.forEach { index ->
        db.delete("readalong_tracks", "info_hash=? AND file_index=? AND book_id=?", arrayOf(track.infoHash, index.toString(), bookId))
      }
      selected.forEach { index ->
        val unchanged = db.rawQuery("SELECT 1 FROM readalong_tracks WHERE info_hash=? AND file_index=? AND book_id=?", arrayOf(track.infoHash, index.toString(), bookId)).use { it.moveToFirst() }
        if (unchanged) return@forEach // Preserve both the offset and chapter_hint on the existing row.
        db.insertWithOnConflict("readalong_tracks", null, ContentValues().apply {
          put("info_hash", track.infoHash); put("file_index", index); put("book_id", bookId); put("offset_ms", 0L)
        }, SQLiteDatabase.CONFLICT_REPLACE)
      }
      db.setTransactionSuccessful()
    } finally { db.endTransaction() }
  }

  fun offset(track: ReadAlongTrack): Long = database.readableDatabase.query("readalong_tracks", arrayOf("offset_ms"), "info_hash=? AND file_index=?", arrayOf(track.infoHash, track.fileIndex.toString()), null, null, null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
  fun setOffset(track: ReadAlongTrack, value: Long, bookId: String? = null) {
    val where = "info_hash=? AND file_index=?" + if (bookId == null) "" else " AND book_id=?"
    val args = if (bookId == null) arrayOf(track.infoHash, track.fileIndex.toString()) else arrayOf(track.infoHash, track.fileIndex.toString(), bookId)
    database.writableDatabase.update("readalong_tracks", ContentValues().apply { put("offset_ms", value) }, where, args)
  }

  fun chapterHint(track: ReadAlongTrack): Int? = database.readableDatabase.query("readalong_tracks", arrayOf("chapter_hint"), "info_hash=? AND file_index=?", arrayOf(track.infoHash, track.fileIndex.toString()), null, null, null).use { if (it.moveToFirst() && !it.isNull(0)) it.getInt(0) else null }
  fun setCorrection(track: ReadAlongTrack, bookId: String, offsetMs: Long, chapterHint: Int?) {
    database.writableDatabase.update("readalong_tracks", ContentValues().apply {
      put("offset_ms", offsetMs)
      if (chapterHint == null) putNull("chapter_hint") else put("chapter_hint", chapterHint)
    }, "info_hash=? AND file_index=? AND book_id=?", arrayOf(track.infoHash, track.fileIndex.toString(), bookId))
  }
  fun setChapterHint(track: ReadAlongTrack, chapter: Int) { database.writableDatabase.update("readalong_tracks", ContentValues().apply { put("chapter_hint", chapter) }, "info_hash=? AND file_index=?", arrayOf(track.infoHash, track.fileIndex.toString())) }

  fun timings(track: ReadAlongTrack, bookId: String): List<TimedLine> = database.readableDatabase.query("readalong_timings", null, "info_hash=? AND file_index=? AND book_id=?", arrayOf(track.infoHash, track.fileIndex.toString(), bookId), null, null, "start_ms").use { c ->
    buildList { while (c.moveToNext()) add(TimedLine(c.getLong(c.getColumnIndexOrThrow("sentence_id")), c.getLong(c.getColumnIndexOrThrow("start_ms")), c.getLong(c.getColumnIndexOrThrow("end_ms")), c.getString(c.getColumnIndexOrThrow("text")))) }
  }

  fun saveWindow(track: ReadAlongTrack, bookId: String, startMs: Long, endMs: Long, lines: List<TimedLine>) {
    val db = database.writableDatabase
    db.beginTransaction()
    try {
      // A delayed result must not write into a replaced attachment.
      val current = db.rawQuery("SELECT book_id FROM readalong_tracks WHERE info_hash=? AND file_index=?", arrayOf(track.infoHash, track.fileIndex.toString())).use { if (it.moveToFirst()) it.getString(0) else null }
      if (current != bookId) return
      lines.filter { it.isTimed }.forEach { line -> db.insertWithOnConflict("readalong_timings", null, ContentValues().apply {
        put("info_hash", track.infoHash); put("file_index", track.fileIndex); put("book_id", bookId)
        put("sentence_id", line.id); put("start_ms", line.startTimeMs); put("end_ms", line.endTimeMs); put("text", line.text)
      }, SQLiteDatabase.CONFLICT_REPLACE) }
      db.insertWithOnConflict("readalong_windows", null, ContentValues().apply {
        put("info_hash", track.infoHash); put("file_index", track.fileIndex); put("book_id", bookId); put("start_ms", startMs); put("end_ms", endMs)
      }, SQLiteDatabase.CONFLICT_REPLACE)
      db.setTransactionSuccessful()
    } finally { db.endTransaction() }
  }

  fun windowReady(track: ReadAlongTrack, bookId: String, startMs: Long): Boolean = database.readableDatabase.rawQuery("SELECT 1 FROM readalong_windows WHERE info_hash=? AND file_index=? AND book_id=? AND start_ms<=? AND end_ms>? LIMIT 1", arrayOf(track.infoHash, track.fileIndex.toString(), bookId, startMs.toString(), startMs.toString())).use { it.moveToFirst() }

  fun resetAlignment(track: ReadAlongTrack, bookId: String) {
    val db = database.writableDatabase
    db.beginTransaction()
    try {
      listOf("readalong_timings", "readalong_windows").forEach { table -> db.delete(table, "info_hash=? AND file_index=? AND book_id=?", arrayOf(track.infoHash, track.fileIndex.toString(), bookId)) }
      db.setTransactionSuccessful()
    } finally { db.endTransaction() }
  }

  companion object {
    fun createSchema(db: SQLiteDatabase) {
      db.execSQL("CREATE TABLE IF NOT EXISTS readalong_books (id TEXT PRIMARY KEY, info_hash TEXT NOT NULL, title TEXT NOT NULL, author TEXT, isbn TEXT, file_name TEXT NOT NULL, source TEXT NOT NULL)")
      db.execSQL("CREATE TABLE IF NOT EXISTS readalong_chapters (book_id TEXT NOT NULL, chapter_index INTEGER NOT NULL, chapter_id TEXT NOT NULL, href TEXT NOT NULL, title TEXT NOT NULL, content TEXT NOT NULL, word_count INTEGER NOT NULL, PRIMARY KEY(book_id,chapter_index))")
      db.execSQL("CREATE TABLE IF NOT EXISTS readalong_tracks (info_hash TEXT NOT NULL, file_index INTEGER NOT NULL, book_id TEXT NOT NULL, offset_ms INTEGER NOT NULL DEFAULT 0, chapter_hint INTEGER, PRIMARY KEY(info_hash,file_index))")
      db.execSQL("CREATE TABLE IF NOT EXISTS readalong_timings (info_hash TEXT NOT NULL, file_index INTEGER NOT NULL, book_id TEXT NOT NULL, sentence_id INTEGER NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, text TEXT NOT NULL, PRIMARY KEY(info_hash,file_index,book_id,sentence_id))")
      db.execSQL("CREATE TABLE IF NOT EXISTS readalong_windows (info_hash TEXT NOT NULL, file_index INTEGER NOT NULL, book_id TEXT NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, PRIMARY KEY(info_hash,file_index,book_id,start_ms))")
      db.execSQL("CREATE INDEX IF NOT EXISTS idx_readalong_book_tracks ON readalong_tracks(book_id)")
    }

    fun migrateLegacy(db: SQLiteDatabase) {
      createSchema(db)
      db.execSQL("INSERT OR IGNORE INTO readalong_books SELECT 'legacy:'||info_hash,info_hash,title,author,isbn,COALESCE(file_name,'book.epub'),'NARRATION' FROM companion_books")
      db.execSQL("INSERT OR IGNORE INTO readalong_chapters SELECT 'legacy:'||info_hash,chapter_index,'legacy:'||chapter_index,'',title,content,word_count FROM companion_chapters")
      db.execSQL("INSERT OR IGNORE INTO readalong_tracks(info_hash,file_index,book_id) SELECT info_hash,mapped_file_index,'legacy:'||info_hash FROM companion_chapters WHERE mapped_file_index IS NOT NULL")
      // Preserve historical fallback associations only for tracks already recorded in playback history.
      db.execSQL("INSERT OR IGNORE INTO readalong_tracks(info_hash,file_index,book_id) SELECT DISTINCT h.info_hash,h.file_index,'legacy:'||h.info_hash FROM playback_history h JOIN companion_books b ON b.info_hash=h.info_hash")
    }

    fun deleteTorrent(db: SQLiteDatabase, infoHash: String) {
      val args = arrayOf(infoHash)
      db.execSQL("DELETE FROM readalong_chapters WHERE book_id IN (SELECT id FROM readalong_books WHERE info_hash=?)", args)
      listOf("readalong_tracks", "readalong_timings", "readalong_windows", "readalong_books").forEach { db.delete(it, "info_hash=?", args) }
    }
  }
}
