package com.example.rivulet.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.example.rivulet.data.lyrics.EpubBook
import com.example.rivulet.data.lyrics.EpubChapter
import java.util.Calendar
import java.util.LinkedHashMap

data class CompanionBookSummary(
  val infoHash: String,
  val title: String,
  val author: String,
  val fileName: String,
  val totalChapters: Int,
  val createdAt: Long,
  val isbn: String? = null,
)

data class SavedTorrent(
  val infoHash: String,
  val name: String,
  val addedAt: Long,
  val magnetUri: String?,
  val torrentBytes: ByteArray?,
  val coverUrl: String? = null,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false
    other as SavedTorrent
    return infoHash == other.infoHash
  }

  override fun hashCode(): Int = infoHash.hashCode()
}

data class SavedPlaybackPosition(
  val infoHash: String,
  val fileIndex: Int,
  val positionMs: Long,
  val durationMs: Long,
  val updatedAt: Long,
)

data class ListeningHistoryItem(
  val infoHash: String,
  val bookTitle: String,
  val coverUrl: String?,
  val fileIndex: Int,
  val positionMs: Long,
  val durationMs: Long,
  val lastPlayedAt: Long,
) {
  val progress: Float
    get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
  val remainingMs: Long
    get() = (durationMs - positionMs).coerceAtLeast(0L)
}

enum class HistoryDateGroup(val title: String) {
  TODAY("Today"),
  YESTERDAY("Yesterday"),
  THIS_WEEK("Earlier This Week"),
  EARLIER("Past Month & Earlier"),
}

data class HistoryEntry(
  val id: Long,
  val infoHash: String,
  val bookTitle: String,
  val coverUrl: String?,
  val fileIndex: Int,
  val fileName: String,
  val positionMs: Long,
  val durationMs: Long,
  val playedAt: Long,
) {
  val progress: Float
    get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
  val remainingMs: Long
    get() = (durationMs - positionMs).coerceAtLeast(0L)
}

data class BookmarkEntry(
  val id: Long = 0L,
  val infoHash: String,
  val fileIndex: Int,
  val chapterTitle: String,
  val positionMs: Long,
  val note: String? = null,
  val createdAt: Long = System.currentTimeMillis(),
)

data class WishlistBook(
  val infoHash: String,
  val title: String,
  val author: String,
  val coverUrl: String?,
  val magnetUri: String,
  val addedAt: Long = System.currentTimeMillis(),
)

class TorrentDatabase(context: Context) :
  SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(
      """
      CREATE TABLE torrents (
        info_hash TEXT PRIMARY KEY,
        name TEXT NOT NULL,
        added_at INTEGER NOT NULL,
        magnet_uri TEXT,
        torrent_bytes BLOB,
        cover_url TEXT
      )
      """.trimIndent()
    )

    db.execSQL(
      """
      CREATE TABLE playback_state (
        info_hash TEXT NOT NULL,
        file_index INTEGER NOT NULL,
        position_ms INTEGER NOT NULL,
        duration_ms INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        PRIMARY KEY (info_hash, file_index)
      )
      """.trimIndent()
    )

    db.execSQL(
      """
      CREATE TABLE file_priorities (
        info_hash TEXT NOT NULL,
        file_index INTEGER NOT NULL,
        wanted INTEGER NOT NULL,
        PRIMARY KEY (info_hash, file_index)
      )
      """.trimIndent()
    )

    db.execSQL(
      """
      CREATE TABLE playback_history (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        info_hash TEXT NOT NULL,
        file_index INTEGER NOT NULL,
        file_name TEXT NOT NULL,
        position_ms INTEGER NOT NULL,
        duration_ms INTEGER NOT NULL,
        played_at INTEGER NOT NULL
      )
      """.trimIndent()
    )

    db.execSQL(
      """
      CREATE TABLE page_text_cache (
        info_hash TEXT NOT NULL,
        file_index INTEGER NOT NULL,
        page_index INTEGER NOT NULL,
        start_time_ms INTEGER NOT NULL,
        end_time_ms INTEGER NOT NULL,
        lines_json TEXT NOT NULL,
        PRIMARY KEY (info_hash, file_index, page_index)
      )
      """.trimIndent()
    )

    db.execSQL(
      """
      CREATE TABLE recent_searches (
        query TEXT PRIMARY KEY,
        searched_at INTEGER NOT NULL
      )
      """.trimIndent()
    )

    db.execSQL(
      """
      CREATE TABLE bookmarks (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        info_hash TEXT NOT NULL,
        file_index INTEGER NOT NULL,
        chapter_title TEXT NOT NULL,
        position_ms INTEGER NOT NULL,
        note TEXT,
        created_at INTEGER NOT NULL
      )
      """.trimIndent()
    )

    db.execSQL(
      """
      CREATE TABLE wishlist_books (
        info_hash TEXT PRIMARY KEY,
        title TEXT NOT NULL,
        author TEXT,
        cover_url TEXT,
        magnet_uri TEXT NOT NULL,
        added_at INTEGER NOT NULL
      )
      """.trimIndent()
    )

    db.execSQL(
      """
      CREATE TABLE IF NOT EXISTS companion_books (
        info_hash TEXT PRIMARY KEY,
        title TEXT NOT NULL,
        author TEXT,
        file_name TEXT,
        total_chapters INTEGER NOT NULL,
        created_at INTEGER NOT NULL,
        isbn TEXT
      )
      """.trimIndent()
    )

    db.execSQL(
      """
      CREATE TABLE IF NOT EXISTS companion_chapters (
        info_hash TEXT NOT NULL,
        chapter_index INTEGER NOT NULL,
        title TEXT NOT NULL,
        content TEXT NOT NULL,
        word_count INTEGER NOT NULL,
        mapped_file_index INTEGER,
        PRIMARY KEY (info_hash, chapter_index)
      )
      """.trimIndent()
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_comp_chap_hash_file ON companion_chapters(info_hash, mapped_file_index)")

    db.execSQL("CREATE INDEX IF NOT EXISTS idx_playback_updated ON playback_state(updated_at DESC)")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_torrents_added ON torrents(added_at DESC)")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_played ON playback_history(played_at DESC)")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_hash_file ON playback_history(info_hash, file_index)")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_recent_searches_time ON recent_searches(searched_at DESC)")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_bookmarks_hash ON bookmarks(info_hash, file_index)")
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    if (oldVersion < 2) {
      runCatching {
        db.execSQL("ALTER TABLE torrents ADD COLUMN cover_url TEXT")
      }
    }
    if (oldVersion < 3) {
      runCatching {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_playback_updated ON playback_state(updated_at DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_torrents_added ON torrents(added_at DESC)")
      }
    }
    if (oldVersion < 4) {
      runCatching {
        db.execSQL(
          """
          CREATE TABLE IF NOT EXISTS playback_history (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            info_hash TEXT NOT NULL,
            file_index INTEGER NOT NULL,
            file_name TEXT NOT NULL,
            position_ms INTEGER NOT NULL,
            duration_ms INTEGER NOT NULL,
            played_at INTEGER NOT NULL
          )
          """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_played ON playback_history(played_at DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_hash_file ON playback_history(info_hash, file_index)")

        db.execSQL(
          """
          CREATE TABLE IF NOT EXISTS page_text_cache (
            info_hash TEXT NOT NULL,
            file_index INTEGER NOT NULL,
            page_index INTEGER NOT NULL,
            start_time_ms INTEGER NOT NULL,
            end_time_ms INTEGER NOT NULL,
            lines_json TEXT NOT NULL,
            PRIMARY KEY (info_hash, file_index, page_index)
          )
          """.trimIndent()
        )
      }
    }
    if (oldVersion < 5) {
      runCatching {
        db.execSQL(
          """
          CREATE TABLE IF NOT EXISTS recent_searches (
            query TEXT PRIMARY KEY,
            searched_at INTEGER NOT NULL
          )
          """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_recent_searches_time ON recent_searches(searched_at DESC)")

        db.execSQL(
          """
          CREATE TABLE IF NOT EXISTS bookmarks (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            info_hash TEXT NOT NULL,
            file_index INTEGER NOT NULL,
            chapter_title TEXT NOT NULL,
            position_ms INTEGER NOT NULL,
            note TEXT,
            created_at INTEGER NOT NULL
          )
          """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_bookmarks_hash ON bookmarks(info_hash, file_index)")

        db.execSQL(
          """
          CREATE TABLE IF NOT EXISTS wishlist_books (
            info_hash TEXT PRIMARY KEY,
            title TEXT NOT NULL,
            author TEXT,
            cover_url TEXT,
            magnet_uri TEXT NOT NULL,
            added_at INTEGER NOT NULL
          )
          """.trimIndent()
        )
      }
    }
    if (oldVersion < 6) {
      runCatching {
        db.execSQL(
          """
          CREATE TABLE IF NOT EXISTS companion_books (
            info_hash TEXT PRIMARY KEY,
            title TEXT NOT NULL,
            author TEXT,
            file_name TEXT,
            total_chapters INTEGER NOT NULL,
            created_at INTEGER NOT NULL
          )
          """.trimIndent()
        )

        db.execSQL(
          """
          CREATE TABLE IF NOT EXISTS companion_chapters (
            info_hash TEXT NOT NULL,
            chapter_index INTEGER NOT NULL,
            title TEXT NOT NULL,
            content TEXT NOT NULL,
            word_count INTEGER NOT NULL,
            mapped_file_index INTEGER,
            PRIMARY KEY (info_hash, chapter_index)
          )
          """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_comp_chap_hash_file ON companion_chapters(info_hash, mapped_file_index)")
      }
    }
    if (oldVersion < 7) {
      runCatching {
        db.execSQL("ALTER TABLE companion_books ADD COLUMN isbn TEXT")
      }
    }
  }

  fun saveTorrent(torrent: SavedTorrent) {
    try {
      val cv = ContentValues().apply {
        put("info_hash", torrent.infoHash)
        put("name", torrent.name)
        put("added_at", torrent.addedAt)
        put("magnet_uri", torrent.magnetUri)
        put("torrent_bytes", torrent.torrentBytes)
        put("cover_url", torrent.coverUrl)
      }
      writableDatabase.insertWithOnConflict("torrents", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    } catch (t: Throwable) {
      Log.e(TAG, "Error saving torrent ${torrent.infoHash}", t)
    }
  }

  fun updateTorrentName(infoHash: String, name: String) {
    try {
      val cv = ContentValues().apply { put("name", name) }
      writableDatabase.update("torrents", cv, "info_hash = ?", arrayOf(infoHash))
    } catch (t: Throwable) {
      Log.e(TAG, "Error updating torrent name for $infoHash", t)
    }
  }

  fun updateTorrentCover(infoHash: String, coverUrl: String) {
    try {
      val cv = ContentValues().apply { put("cover_url", coverUrl) }
      writableDatabase.update("torrents", cv, "info_hash = ?", arrayOf(infoHash))
    } catch (t: Throwable) {
      Log.e(TAG, "Error updating torrent cover for $infoHash", t)
    }
  }

  fun deleteTorrent(infoHash: String) {
    val db = writableDatabase
    db.beginTransaction()
    try {
      db.delete("torrents", "info_hash = ?", arrayOf(infoHash))
      db.delete("playback_state", "info_hash = ?", arrayOf(infoHash))
      db.delete("file_priorities", "info_hash = ?", arrayOf(infoHash))
      db.delete("bookmarks", "info_hash = ?", arrayOf(infoHash))
      db.delete("playback_history", "info_hash = ?", arrayOf(infoHash))
      db.delete("page_text_cache", "info_hash = ?", arrayOf(infoHash))
      db.delete("wishlist_books", "info_hash = ?", arrayOf(infoHash))
      db.delete("companion_books", "info_hash = ?", arrayOf(infoHash))
      db.delete("companion_chapters", "info_hash = ?", arrayOf(infoHash))
      db.setTransactionSuccessful()
    } catch (t: Throwable) {
      Log.e(TAG, "Error deleting torrent $infoHash", t)
    } finally {
      db.endTransaction()
    }
  }

  fun deletePageText(infoHash: String, fileIndex: Int? = null) {
    try {
      val db = writableDatabase
      if (fileIndex != null) {
        db.delete("page_text_cache", "info_hash = ? AND file_index = ?", arrayOf(infoHash, fileIndex.toString()))
      } else {
        db.delete("page_text_cache", "info_hash = ?", arrayOf(infoHash))
      }
    } catch (t: Throwable) {
      Log.e(TAG, "Error deleting page text for $infoHash", t)
    }
  }

  fun getTorrent(infoHash: String): SavedTorrent? {
    try {
      readableDatabase.query(
        "torrents",
        arrayOf("info_hash", "name", "added_at", "magnet_uri", "torrent_bytes", "cover_url"),
        "info_hash = ?",
        arrayOf(infoHash),
        null,
        null,
        null
      ).use { cursor ->
        if (cursor.moveToFirst()) {
          val idxHash = cursor.getColumnIndexOrThrow("info_hash")
          val idxName = cursor.getColumnIndexOrThrow("name")
          val idxAdded = cursor.getColumnIndexOrThrow("added_at")
          val idxMagnet = cursor.getColumnIndexOrThrow("magnet_uri")
          val idxBytes = cursor.getColumnIndexOrThrow("torrent_bytes")
          val idxCover = cursor.getColumnIndex("cover_url")
          return SavedTorrent(
            infoHash = cursor.getString(idxHash),
            name = cursor.getString(idxName),
            addedAt = cursor.getLong(idxAdded),
            magnetUri = cursor.getString(idxMagnet),
            torrentBytes = cursor.getBlob(idxBytes),
            coverUrl = if (idxCover >= 0 && !cursor.isNull(idxCover)) cursor.getString(idxCover) else null,
          )
        }
      }
    } catch (t: Throwable) {
      Log.e(TAG, "Error loading torrent $infoHash", t)
    }
    return null
  }

  fun getAllTorrents(): List<SavedTorrent> {
    val result = ArrayList<SavedTorrent>()
    try {
      readableDatabase.query(
        "torrents",
        arrayOf("info_hash", "name", "added_at", "magnet_uri", "torrent_bytes", "cover_url"),
        null,
        null,
        null,
        null,
        "added_at DESC"
      ).use { cursor ->
        val idxHash = cursor.getColumnIndexOrThrow("info_hash")
        val idxName = cursor.getColumnIndexOrThrow("name")
        val idxAdded = cursor.getColumnIndexOrThrow("added_at")
        val idxMagnet = cursor.getColumnIndexOrThrow("magnet_uri")
        val idxBytes = cursor.getColumnIndexOrThrow("torrent_bytes")
        val idxCover = cursor.getColumnIndex("cover_url")

        while (cursor.moveToNext()) {
          result.add(
            SavedTorrent(
              infoHash = cursor.getString(idxHash),
              name = cursor.getString(idxName),
              addedAt = cursor.getLong(idxAdded),
              magnetUri = cursor.getString(idxMagnet),
              torrentBytes = cursor.getBlob(idxBytes),
              coverUrl = if (idxCover >= 0 && !cursor.isNull(idxCover)) cursor.getString(idxCover) else null,
            )
          )
        }
      }
    } catch (t: Throwable) {
      Log.e(TAG, "Error loading torrents", t)
    }
    return result
  }

  fun savePlaybackPosition(infoHash: String, fileIndex: Int, positionMs: Long, durationMs: Long) {
    if (positionMs <= 0L && durationMs <= 0L) return
    try {
      val cv = ContentValues().apply {
        put("info_hash", infoHash)
        put("file_index", fileIndex)
        put("position_ms", positionMs)
        put("duration_ms", durationMs)
        put("updated_at", System.currentTimeMillis())
      }
      writableDatabase.insertWithOnConflict("playback_state", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    } catch (t: Throwable) {
      Log.e(TAG, "Error saving playback position for $infoHash/$fileIndex", t)
    }
  }

  fun getPlaybackPosition(infoHash: String, fileIndex: Int): Long {
    try {
      readableDatabase.query(
        "playback_state",
        arrayOf("position_ms"),
        "info_hash = ? AND file_index = ?",
        arrayOf(infoHash, fileIndex.toString()),
        null,
        null,
        null
      ).use { cursor ->
        if (cursor.moveToFirst()) {
          return cursor.getLong(0)
        }
      }
    } catch (t: Throwable) {
      Log.e(TAG, "Error getting playback position for $infoHash/$fileIndex", t)
    }
    return 0L
  }

  fun getPlaybackDuration(infoHash: String, fileIndex: Int): Long {
    try {
      readableDatabase.query(
        "playback_state",
        arrayOf("duration_ms"),
        "info_hash = ? AND file_index = ?",
        arrayOf(infoHash, fileIndex.toString()),
        null,
        null,
        null
      ).use { cursor ->
        if (cursor.moveToFirst()) {
          return cursor.getLong(0)
        }
      }
    } catch (t: Throwable) {
      Log.e(TAG, "Error getting playback duration for $infoHash/$fileIndex", t)
    }
    return 0L
  }

  fun getAllPlaybackPositions(infoHash: String): Map<Int, Long> {
    val map = HashMap<Int, Long>()
    try {
      readableDatabase.query(
        "playback_state",
        arrayOf("file_index", "position_ms"),
        "info_hash = ?",
        arrayOf(infoHash),
        null,
        null,
        null
      ).use { cursor ->
        while (cursor.moveToNext()) {
          map[cursor.getInt(0)] = cursor.getLong(1)
        }
      }
    } catch (t: Throwable) {
      Log.e(TAG, "Error getting playback positions for $infoHash", t)
    }
    return map
  }

  fun saveFileWanted(infoHash: String, fileIndex: Int, wanted: Boolean) {
    try {
      val cv = ContentValues().apply {
        put("info_hash", infoHash)
        put("file_index", fileIndex)
        put("wanted", if (wanted) 1 else 0)
      }
      writableDatabase.insertWithOnConflict("file_priorities", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    } catch (t: Throwable) {
      Log.e(TAG, "Error saving file priority for $infoHash/$fileIndex", t)
    }
  }

  fun getFilePriorities(infoHash: String): Map<Int, Boolean> {
    val map = HashMap<Int, Boolean>()
    try {
      readableDatabase.query(
        "file_priorities",
        arrayOf("file_index", "wanted"),
        "info_hash = ?",
        arrayOf(infoHash),
        null,
        null,
        null
      ).use { cursor ->
        while (cursor.moveToNext()) {
          map[cursor.getInt(0)] = cursor.getInt(1) == 1
        }
      }
    } catch (t: Throwable) {
      Log.e(TAG, "Error getting file priorities for $infoHash", t)
    }
    return map
  }

  fun isFileWanted(infoHash: String, fileIndex: Int): Boolean {
    try {
      readableDatabase.query(
        "file_priorities",
        arrayOf("wanted"),
        "info_hash = ? AND file_index = ?",
        arrayOf(infoHash, fileIndex.toString()),
        null,
        null,
        null
      ).use { cursor ->
        if (cursor.moveToFirst()) {
          return cursor.getInt(0) == 1
        }
      }
    } catch (t: Throwable) {
      Log.e(TAG, "Error checking file priority for $infoHash/$fileIndex", t)
    }
    return false
  }

  fun getListeningHistory(limit: Int = 10): List<ListeningHistoryItem> {
    val list = ArrayList<ListeningHistoryItem>()
    try {
      val query = """
        SELECT t.info_hash, t.name, t.cover_url, p.file_index, p.position_ms, p.duration_ms, p.updated_at
        FROM playback_state p
        JOIN torrents t ON t.info_hash = p.info_hash
        ORDER BY p.updated_at DESC
        LIMIT ?
      """.trimIndent()
      readableDatabase.rawQuery(query, arrayOf(limit.toString())).use { cursor ->
        val seen = HashSet<String>()
        while (cursor.moveToNext()) {
          val hash = cursor.getString(0)
          if (!seen.add(hash)) continue
          list.add(
            ListeningHistoryItem(
              infoHash = hash,
              bookTitle = cursor.getString(1),
              coverUrl = if (!cursor.isNull(2)) cursor.getString(2) else null,
              fileIndex = cursor.getInt(3),
              positionMs = cursor.getLong(4),
              durationMs = cursor.getLong(5),
              lastPlayedAt = cursor.getLong(6),
            )
          )
        }
      }
    } catch (t: Throwable) {
      Log.e(TAG, "Error getting listening history", t)
    }
    return list
  }

  fun getLatestPlayedBook(): ListeningHistoryItem? = getListeningHistory(1).firstOrNull()

  // ---------------------------------------------------------------------------------------------
  // Full Chapter-Level Listening History & Date Grouping
  // ---------------------------------------------------------------------------------------------

  fun recordPlaybackHistory(
    infoHash: String,
    fileIndex: Int,
    fileName: String,
    positionMs: Long,
    durationMs: Long,
  ) {
    if (positionMs <= 0L && durationMs <= 0L) return
    try {
      val cv = ContentValues().apply {
        put("info_hash", infoHash)
        put("file_index", fileIndex)
        put("file_name", fileName)
        put("position_ms", positionMs)
        put("duration_ms", durationMs)
        put("played_at", System.currentTimeMillis())
      }
      val updated = writableDatabase.update(
        "playback_history",
        cv,
        "info_hash = ? AND file_index = ?",
        arrayOf(infoHash, fileIndex.toString())
      )
      if (updated == 0) {
        writableDatabase.insert("playback_history", null, cv)
      }
    } catch (t: Throwable) {
      Log.e(TAG, "Error recording playback history", t)
    }
  }

  fun getOrganizedHistory(): Map<HistoryDateGroup, List<HistoryEntry>> {
    val result = LinkedHashMap<HistoryDateGroup, MutableList<HistoryEntry>>()
    for (group in HistoryDateGroup.entries) {
      result[group] = ArrayList()
    }

    val now = System.currentTimeMillis()
    val calNow = Calendar.getInstance().apply { timeInMillis = now }
    val calEntry = Calendar.getInstance()

    try {
      val query = """
        SELECT h.id, h.info_hash, t.name, t.cover_url, h.file_index, h.file_name, h.position_ms, h.duration_ms, h.played_at
        FROM playback_history h
        JOIN torrents t ON t.info_hash = h.info_hash
        ORDER BY h.played_at DESC
        LIMIT 100
      """.trimIndent()

      readableDatabase.rawQuery(query, null).use { cursor ->
        while (cursor.moveToNext()) {
          val playedAt = cursor.getLong(8)
          calEntry.timeInMillis = playedAt

          val isSameDay = calNow.get(Calendar.YEAR) == calEntry.get(Calendar.YEAR) &&
            calNow.get(Calendar.DAY_OF_YEAR) == calEntry.get(Calendar.DAY_OF_YEAR)
          val diffDays = (now - playedAt) / (24 * 60 * 60 * 1000L)

          val group = when {
            isSameDay -> HistoryDateGroup.TODAY
            diffDays <= 1 || (calNow.get(Calendar.YEAR) == calEntry.get(Calendar.YEAR) &&
              calNow.get(Calendar.DAY_OF_YEAR) - calEntry.get(Calendar.DAY_OF_YEAR) == 1) -> HistoryDateGroup.YESTERDAY
            diffDays < 7 -> HistoryDateGroup.THIS_WEEK
            else -> HistoryDateGroup.EARLIER
          }

          val entry = HistoryEntry(
            id = cursor.getLong(0),
            infoHash = cursor.getString(1),
            bookTitle = cursor.getString(2),
            coverUrl = if (!cursor.isNull(3)) cursor.getString(3) else null,
            fileIndex = cursor.getInt(4),
            fileName = cursor.getString(5),
            positionMs = cursor.getLong(6),
            durationMs = cursor.getLong(7),
            playedAt = playedAt,
          )
          result[group]?.add(entry)
        }
      }
    } catch (t: Throwable) {
      Log.e(TAG, "Error querying organized history", t)
    }

    return result.filterValues { it.isNotEmpty() }
  }

  fun deleteHistoryEntry(id: Long) {
    try {
      writableDatabase.delete("playback_history", "id = ?", arrayOf(id.toString()))
    } catch (t: Throwable) {
      Log.e(TAG, "Error deleting history entry $id", t)
    }
  }

  fun clearAllHistory() {
    try {
      writableDatabase.delete("playback_history", null, null)
    } catch (t: Throwable) {
      Log.e(TAG, "Error clearing history", t)
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Sliding-Page Text Cache
  // ---------------------------------------------------------------------------------------------

  fun getPageText(infoHash: String, fileIndex: Int, pageIndex: Int): String? {
    try {
      readableDatabase.query(
        "page_text_cache",
        arrayOf("lines_json"),
        "info_hash = ? AND file_index = ? AND page_index = ?",
        arrayOf(infoHash, fileIndex.toString(), pageIndex.toString()),
        null,
        null,
        null
      ).use { cursor ->
        if (cursor.moveToFirst()) {
          return cursor.getString(0)
        }
      }
    } catch (t: Throwable) {
      Log.e(TAG, "Error getting cached page text $infoHash/$fileIndex/p$pageIndex", t)
    }
    return null
  }

  fun savePageText(
    infoHash: String,
    fileIndex: Int,
    pageIndex: Int,
    startTimeMs: Long,
    endTimeMs: Long,
    linesJson: String,
  ) {
    try {
      val cv = ContentValues().apply {
        put("info_hash", infoHash)
        put("file_index", fileIndex)
        put("page_index", pageIndex)
        put("start_time_ms", startTimeMs)
        put("end_time_ms", endTimeMs)
        put("lines_json", linesJson)
      }
      writableDatabase.insertWithOnConflict("page_text_cache", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    } catch (t: Throwable) {
      Log.e(TAG, "Error saving page text $infoHash/$fileIndex/p$pageIndex", t)
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Recent Searches
  // ---------------------------------------------------------------------------------------------

  fun saveRecentSearch(query: String) {
    val clean = query.trim()
    if (clean.isBlank()) return
    try {
      val cv = ContentValues().apply {
        put("query", clean)
        put("searched_at", System.currentTimeMillis())
      }
      writableDatabase.insertWithOnConflict("recent_searches", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    } catch (t: Throwable) {
      Log.e(TAG, "Error saving recent search $clean", t)
    }
  }

  fun getRecentSearches(limit: Int = 8): List<String> {
    val list = ArrayList<String>()
    try {
      readableDatabase.query(
        "recent_searches",
        arrayOf("query"),
        null,
        null,
        null,
        null,
        "searched_at DESC",
        limit.toString()
      ).use { cursor ->
        while (cursor.moveToNext()) {
          list.add(cursor.getString(0))
        }
      }
    } catch (t: Throwable) {
      Log.e(TAG, "Error getting recent searches", t)
    }
    return list
  }

  fun deleteRecentSearch(query: String) {
    try {
      writableDatabase.delete("recent_searches", "query = ?", arrayOf(query.trim()))
    } catch (t: Throwable) {
      Log.e(TAG, "Error deleting recent search $query", t)
    }
  }

  fun clearRecentSearches() {
    try {
      writableDatabase.delete("recent_searches", null, null)
    } catch (t: Throwable) {
      Log.e(TAG, "Error clearing recent searches", t)
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Bookmarks
  // ---------------------------------------------------------------------------------------------

  fun addBookmark(bookmark: BookmarkEntry): Long {
    try {
      val cv = ContentValues().apply {
        put("info_hash", bookmark.infoHash)
        put("file_index", bookmark.fileIndex)
        put("chapter_title", bookmark.chapterTitle)
        put("position_ms", bookmark.positionMs)
        put("note", bookmark.note)
        put("created_at", bookmark.createdAt)
      }
      return writableDatabase.insert("bookmarks", null, cv)
    } catch (t: Throwable) {
      Log.e(TAG, "Error adding bookmark", t)
      return -1L
    }
  }

  fun getBookmarks(infoHash: String): List<BookmarkEntry> {
    val list = ArrayList<BookmarkEntry>()
    try {
      readableDatabase.query(
        "bookmarks",
        arrayOf("id", "info_hash", "file_index", "chapter_title", "position_ms", "note", "created_at"),
        "info_hash = ?",
        arrayOf(infoHash),
        null,
        null,
        "file_index ASC, position_ms ASC"
      ).use { cursor ->
        while (cursor.moveToNext()) {
          list.add(
            BookmarkEntry(
              id = cursor.getLong(0),
              infoHash = cursor.getString(1),
              fileIndex = cursor.getInt(2),
              chapterTitle = cursor.getString(3),
              positionMs = cursor.getLong(4),
              note = if (cursor.isNull(5)) null else cursor.getString(5),
              createdAt = cursor.getLong(6),
            )
          )
        }
      }
    } catch (t: Throwable) {
      Log.e(TAG, "Error getting bookmarks for $infoHash", t)
    }
    return list
  }

  fun deleteBookmark(id: Long) {
    try {
      writableDatabase.delete("bookmarks", "id = ?", arrayOf(id.toString()))
    } catch (t: Throwable) {
      Log.e(TAG, "Error deleting bookmark $id", t)
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Wishlist
  // ---------------------------------------------------------------------------------------------

  fun addToWishlist(book: WishlistBook) {
    try {
      val cv = ContentValues().apply {
        put("info_hash", book.infoHash)
        put("title", book.title)
        put("author", book.author)
        put("cover_url", book.coverUrl)
        put("magnet_uri", book.magnetUri)
        put("added_at", book.addedAt)
      }
      writableDatabase.insertWithOnConflict("wishlist_books", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    } catch (t: Throwable) {
      Log.e(TAG, "Error adding to wishlist", t)
    }
  }

  fun getWishlist(): List<WishlistBook> {
    val list = ArrayList<WishlistBook>()
    try {
      readableDatabase.query(
        "wishlist_books",
        arrayOf("info_hash", "title", "author", "cover_url", "magnet_uri", "added_at"),
        null,
        null,
        null,
        null,
        "added_at DESC"
      ).use { cursor ->
        while (cursor.moveToNext()) {
          list.add(
            WishlistBook(
              infoHash = cursor.getString(0),
              title = cursor.getString(1),
              author = cursor.getString(2),
              coverUrl = if (cursor.isNull(3)) null else cursor.getString(3),
              magnetUri = cursor.getString(4),
              addedAt = cursor.getLong(5),
            )
          )
        }
      }
    } catch (t: Throwable) {
      Log.e(TAG, "Error getting wishlist", t)
    }
    return list
  }

  fun removeFromWishlist(infoHash: String) {
    try {
      writableDatabase.delete("wishlist_books", "info_hash = ?", arrayOf(infoHash))
    } catch (t: Throwable) {
      Log.e(TAG, "Error removing from wishlist $infoHash", t)
    }
  }

  fun isInWishlist(infoHash: String): Boolean {
    try {
      readableDatabase.query(
        "wishlist_books",
        arrayOf("info_hash"),
        "info_hash = ?",
        arrayOf(infoHash),
        null,
        null,
        null
      ).use { cursor ->
        return cursor.moveToFirst()
      }
    } catch (t: Throwable) {
      Log.e(TAG, "Error checking wishlist $infoHash", t)
    }
    return false
  }

  // ---------------------------------------------------------------------------------------------
  // Companion Book & Synced Chapter Persistence
  // ---------------------------------------------------------------------------------------------

  fun saveCompanionBook(
    infoHash: String,
    book: EpubBook,
    fileName: String,
    trackToChapterMap: Map<Int, EpubChapter>,
  ) {
    val db = writableDatabase
    db.beginTransaction()
    try {
      // Clear existing companion data for this torrent
      db.delete("companion_books", "info_hash = ?", arrayOf(infoHash))
      db.delete("companion_chapters", "info_hash = ?", arrayOf(infoHash))

      val now = System.currentTimeMillis()
      val bookCv = ContentValues().apply {
        put("info_hash", infoHash)
        put("title", book.title)
        put("author", book.author)
        put("file_name", fileName)
        put("total_chapters", book.chapters.size)
        put("created_at", now)
        if (book.isbn != null) put("isbn", book.isbn) else putNull("isbn")
      }
      db.insertWithOnConflict("companion_books", null, bookCv, SQLiteDatabase.CONFLICT_REPLACE)

      // Inverse map: chapter index -> mapped fileIndex
      val chapterToFileIndex = HashMap<Int, Int>()
      for ((fIndex, chapter) in trackToChapterMap) {
        chapterToFileIndex[chapter.index] = fIndex
      }

      for (chapter in book.chapters) {
        val mappedFile = chapterToFileIndex[chapter.index]
        val chapCv = ContentValues().apply {
          put("info_hash", infoHash)
          put("chapter_index", chapter.index)
          put("title", chapter.title)
          put("content", chapter.text)
          put("word_count", chapter.wordCount)
          if (mappedFile != null) put("mapped_file_index", mappedFile) else putNull("mapped_file_index")
        }
        db.insertWithOnConflict("companion_chapters", null, chapCv, SQLiteDatabase.CONFLICT_REPLACE)
      }

      db.setTransactionSuccessful()
    } catch (t: Throwable) {
      Log.e(TAG, "Error saving companion book for $infoHash", t)
    } finally {
      db.endTransaction()
    }
  }

  fun getCompanionBook(infoHash: String): CompanionBookSummary? {
    try {
      readableDatabase.query(
        "companion_books",
        arrayOf("info_hash", "title", "author", "file_name", "total_chapters", "created_at", "isbn"),
        "info_hash = ?",
        arrayOf(infoHash),
        null,
        null,
        null
      ).use { cursor ->
        if (cursor.moveToFirst()) {
          return CompanionBookSummary(
            infoHash = cursor.getString(0),
            title = cursor.getString(1),
            author = cursor.getString(2) ?: "Unknown Author",
            fileName = cursor.getString(3) ?: "book.epub",
            totalChapters = cursor.getInt(4),
            createdAt = cursor.getLong(5),
            isbn = if (cursor.columnCount > 6) cursor.getString(6) else null,
          )
        }
      }
    } catch (t: Throwable) {
      Log.e(TAG, "Error getting companion book $infoHash", t)
    }
    return null
  }

  fun getCompanionChapterForTrack(infoHash: String, fileIndex: Int): EpubChapter? {
    try {
      // 1. Direct mapped file index lookup
      readableDatabase.query(
        "companion_chapters",
        arrayOf("chapter_index", "title", "content", "word_count"),
        "info_hash = ? AND mapped_file_index = ?",
        arrayOf(infoHash, fileIndex.toString()),
        null,
        null,
        "chapter_index ASC",
        "1"
      ).use { cursor ->
        if (cursor.moveToFirst()) {
          val idx = cursor.getInt(0)
          return EpubChapter(
            index = idx,
            id = "db_ch_$idx",
            href = "ch_$idx.xhtml",
            title = cursor.getString(1),
            text = cursor.getString(2),
            wordCount = cursor.getInt(3),
          )
        }
      }

      // 2. Fallback: match by natural 1-based index (fileIndex + 1)
      readableDatabase.query(
        "companion_chapters",
        arrayOf("chapter_index", "title", "content", "word_count"),
        "info_hash = ? AND chapter_index = ?",
        arrayOf(infoHash, (fileIndex + 1).toString()),
        null,
        null,
        null
      ).use { cursor ->
        if (cursor.moveToFirst()) {
          val idx = cursor.getInt(0)
          return EpubChapter(
            index = idx,
            id = "db_ch_$idx",
            href = "ch_$idx.xhtml",
            title = cursor.getString(1),
            text = cursor.getString(2),
            wordCount = cursor.getInt(3),
          )
        }
      }
    } catch (t: Throwable) {
      Log.e(TAG, "Error getting companion chapter for $infoHash track $fileIndex", t)
    }
    return null
  }

  fun getAllCompanionChapters(infoHash: String): List<EpubChapter> {
    val list = ArrayList<EpubChapter>()
    try {
      readableDatabase.query(
        "companion_chapters",
        arrayOf("chapter_index", "title", "content", "word_count"),
        "info_hash = ?",
        arrayOf(infoHash),
        null,
        null,
        "chapter_index ASC"
      ).use { cursor ->
        while (cursor.moveToNext()) {
          val idx = cursor.getInt(0)
          list.add(
            EpubChapter(
              index = idx,
              id = "db_ch_$idx",
              href = "ch_$idx.xhtml",
              title = cursor.getString(1),
              text = cursor.getString(2),
              wordCount = cursor.getInt(3),
            )
          )
        }
      }
    } catch (t: Throwable) {
      Log.e(TAG, "Error getting all companion chapters $infoHash", t)
    }
    return list
  }

  fun updateChapterTrackMapping(infoHash: String, chapterIndex: Int, fileIndex: Int) {
    try {
      val cv = ContentValues().apply {
        put("mapped_file_index", fileIndex)
      }
      writableDatabase.update(
        "companion_chapters",
        cv,
        "info_hash = ? AND chapter_index = ?",
        arrayOf(infoHash, chapterIndex.toString())
      )
    } catch (t: Throwable) {
      Log.e(TAG, "Error updating chapter mapping for $infoHash ch $chapterIndex", t)
    }
  }

  fun deleteCompanionBook(infoHash: String) {
    val db = writableDatabase
    db.beginTransaction()
    try {
      db.delete("companion_books", "info_hash = ?", arrayOf(infoHash))
      db.delete("companion_chapters", "info_hash = ?", arrayOf(infoHash))
      db.delete("page_text_cache", "info_hash = ?", arrayOf(infoHash))
      db.setTransactionSuccessful()
    } catch (t: Throwable) {
      Log.e(TAG, "Error deleting companion book $infoHash", t)
    } finally {
      db.endTransaction()
    }
  }

  companion object {
    private const val TAG = "RivuletDB"
    private const val DATABASE_NAME = "rivulet.db"
    private const val DATABASE_VERSION = 7
  }
}
