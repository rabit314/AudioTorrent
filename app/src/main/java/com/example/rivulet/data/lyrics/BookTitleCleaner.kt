package com.example.rivulet.data.lyrics

object BookTitleCleaner {

  private val AUDIO_EXTENSIONS_REGEX = Regex(
    "\\.(?:m4b|mp3|m4a|flac|opus|ogg|aac|wav|wma|aiff|alac)$",
    RegexOption.IGNORE_CASE
  )

  private val LEADING_TRACK_PREFIXES = listOf(
    Regex("^\\[\\s*\\d+\\s*\\]\\s*"), // [01]
    Regex("^\\(\\s*\\d+\\s*\\)\\s*"), // (01)
    Regex("^\\d{1,3}[\\s\\.\\-_]+\\s*"), // 01 - , 01. , 01_
    Regex("^(?:part|cd|disc|track|ch|chapter)\\s*\\d{1,3}[\\s\\.\\-_]*", RegexOption.IGNORE_CASE), // Disc 1 - , Track 01 -
  )

  private val TAG_SUFFIXES = listOf(
    Regex("[\\[\\(](?:audiobook|unabridged|abridged|retail|flac|mp3|m4b|320kbps|64kbps|vbr)[\\]\\)]", RegexOption.IGNORE_CASE),
    Regex("[\\[\\(]the\\s+omnibus[\\]\\)]", RegexOption.IGNORE_CASE),
    Regex("\\s+omnibus$", RegexOption.IGNORE_CASE),
  )

  private val GENERIC_CHAPTER_REGEX = Regex(
    "^(?:chapter|track|part|cd|disc|section)?\\s*\\d*(?:\\s*(?:of|-|–|\\/)\\s*\\d+)?$",
    RegexOption.IGNORE_CASE
  )

  /**
   * Cleans an audio filename or track title into a readable title.
   */
  fun cleanFileNameOrTrack(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    var s = raw.trim()

    // Take filename after last separator if full path
    if (s.contains('/')) s = s.substringAfterLast('/')
    if (s.contains('\\')) s = s.substringAfterLast('\\')

    // Strip audio extension
    s = s.replace(AUDIO_EXTENSIONS_REGEX, "")

    // Replace underscores with spaces
    s = s.replace('_', ' ')

    // Strip common tags like [Audiobook], (Unabridged)
    for (tagRegex in TAG_SUFFIXES) {
      s = s.replace(tagRegex, "")
    }

    // Strip leading track prefixes like [01], 01 -
    var stripped = true
    while (stripped) {
      stripped = false
      for (regex in LEADING_TRACK_PREFIXES) {
        val next = s.replace(regex, "")
        if (next != s) {
          s = next
          stripped = true
        }
      }
    }

    // If it has "Author - Title", see if we can extract the title
    if (s.contains(" - ")) {
      val parts = s.split(" - ")
      if (parts.size == 2 && parts[1].isNotBlank()) {
        val candidateTitle = parts[1].trim()
        if (!isGenericTrackName(candidateTitle)) {
          s = candidateTitle
        }
      }
    }

    return s.replace(Regex("\\s+"), " ").trim()
  }

  /**
   * Checks whether the title is merely a generic track/chapter identifier
   * (e.g. "Chapter 1", "01", "Track 02", "Part 1").
   */
  fun isGenericTrackName(title: String): Boolean {
    val clean = title.trim()
    if (clean.isBlank()) return true
    if (clean.matches(GENERIC_CHAPTER_REGEX)) return true
    // If it's purely digits or Roman numerals like "III", "IV"
    if (clean.matches(Regex("^[0-9]+$"))) return true
    if (clean.matches(Regex("^(?i)[ivxlcdm]+$")) && clean.length <= 4) return true
    return false
  }

  /**
   * Cleans a torrent or container name (e.g., omnibus or series name).
   */
  fun cleanContainerTitle(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    var s = raw.trim().replace('_', ' ')

    // Strip audio extension if present
    s = s.replace(AUDIO_EXTENSIONS_REGEX, "")

    for (tagRegex in TAG_SUFFIXES) {
      s = s.replace(tagRegex, "")
    }

    // If format is "Author - Title", extract Title
    if (s.contains(" - ")) {
      val parts = s.split(" - ")
      if (parts.size == 2 && parts[1].isNotBlank()) {
        s = parts[1].trim()
      }
    }

    return s.replace(Regex("\\s+"), " ").trim()
  }

  /**
   * Intelligently resolves the true book title from the current track name and container/torrent name.
   *
   * Example 1: track = "[01] Soul Hunter.m4b", container = "Night Lords"
   *   -> Returns "Soul Hunter" (identifies specific book inside omnibus).
   * Example 2: track = "Chapter 01.mp3", container = "Fahrenheit 451"
   *   -> Returns "Fahrenheit 451" (identifies book title when tracks are generic chapters).
   * Example 3: track = "Ray Bradbury - Fahrenheit 451.m4b", container = "Ray Bradbury"
   *   -> Returns "Fahrenheit 451".
   */
  fun resolveCleanBookTitle(trackTitle: String?, containerTitle: String?): String {
    val cleanedTrack = cleanFileNameOrTrack(trackTitle)
    val cleanedContainer = cleanContainerTitle(containerTitle)

    // If the track title contains a specific non-generic book name (e.g. "Soul Hunter"), prioritize it!
    if (cleanedTrack.isNotBlank() && !isGenericTrackName(cleanedTrack)) {
      return cleanedTrack
    }

    // Otherwise fall back to the container/torrent title (e.g. "Fahrenheit 451")
    if (cleanedContainer.isNotBlank()) {
      return cleanedContainer
    }

    if (cleanedTrack.isNotBlank()) {
      return cleanedTrack
    }

    return "Audiobook"
  }
}
