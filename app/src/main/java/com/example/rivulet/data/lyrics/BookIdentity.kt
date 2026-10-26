package com.example.rivulet.data.lyrics

data class BookIdentity(val title: String, val author: String = "") {
  val query: String get() = listOf(title, author).filter(String::isNotBlank).joinToString(" ")
}

/** Book identity is independent of the chapter label and of a torrent's root folder. */
object BookIdentityResolver {
  private val extension = Regex("\\.(m4b|mp3|m4a|flac|opus|ogg|aac|wav|wma|aiff|alac)$", RegexOption.IGNORE_CASE)
  private val tags = Regex("[\\[(](?:audiobook|unabridged|abridged|retail|flac|mp3|m4b|\\d+\\s*kbps|vbr)[\\])]", RegexOption.IGNORE_CASE)
  private val leadingChapter = Regex("^(?:chapter|track|part|disc|cd|section)\\s*\\d+\\b.*", RegexOption.IGNORE_CASE)
  private val numberedPrefix = Regex("^(?:\\[\\d{1,3}]|\\(\\d{1,3}\\)|\\d{1,3}\\s*[-_]\\s*|\\d{1,3}\\.\\s+)")
  private val trailingChapter = Regex("[\\s._-]+(?:part|chapter|track|disc|cd)\\s*\\d+(?:\\s*of\\s*\\d+)?$|\\s+-\\s+\\d{1,3}$", RegexOption.IGNORE_CASE)
  private val article = Regex("^(the|a|an)\\s+", RegexOption.IGNORE_CASE)

  fun clean(raw: String?): String = raw.orEmpty().trim().replace('_', ' ').replace(extension, "")
    .replace(tags, "").replace(Regex("\\s+"), " ").trim()

  private fun stem(raw: String): String = clean(raw.substringAfterLast('/').substringAfterLast('\\'))
    .replace(numberedPrefix, "").trim().replace(trailingChapter, "").trim()

  private fun generic(raw: String) = raw.isBlank() ||
    (!raw.matches(Regex("\\d{4,}")) && BookTitleCleaner.isGenericTrackName(raw)) || leadingChapter.matches(raw)
  private fun same(a: String, b: String) = ReadAlongText.tokens(a) == ReadAlongText.tokens(b)
  private fun person(raw: String): Boolean {
    val words = raw.split(' ')
    return words.size in 2..4 && !article.containsMatchIn(raw) &&
      words.all { it.firstOrNull()?.isUpperCase() == true && it.all { c -> c.isLetter() || c in "-.'’" } }
  }

  /** Split only with corroboration or a clearly title-like counterpart; retain ambiguous names. */
  private fun split(raw: String, knownAuthor: String = "", titleHint: String = ""): BookIdentity {
    val parts = raw.split(Regex("\\s+[-–—]\\s+"))
    if (parts.size != 2) return BookIdentity(raw)
    val (a, b) = parts
    return when {
      knownAuthor.isNotBlank() && same(a, knownAuthor) -> BookIdentity(b, a)
      knownAuthor.isNotBlank() && same(b, knownAuthor) -> BookIdentity(a, b)
      titleHint.isNotBlank() && same(a, titleHint) -> BookIdentity(a, b)
      titleHint.isNotBlank() && same(b, titleHint) -> BookIdentity(b, a)
      person(a) && !person(b) -> BookIdentity(b, a)
      person(b) && !person(a) -> BookIdentity(a, b)
      else -> BookIdentity(raw)
    }
  }

  fun resolve(fileName: String?, directory: String? = null, container: String? = null, catalogTitle: String? = null, chapterTrack: Boolean = false): BookIdentity {
    val file = stem(fileName.orEmpty())
    val folder = stem(directory.orEmpty())
    val root = clean(container)
    // The catalog convention is Title - Author. File/folder corroboration also handles old labels.
    val catalog = clean(catalogTitle)
    val catalogParts = catalog.split(Regex("\\s+[-–—]\\s+"))
    val known = if (catalogParts.size == 2) BookIdentity(catalogParts[0], catalogParts[1]) else BookIdentity(catalog)
    val authorHint = known.author.ifBlank {
      root.takeIf { person(it) && (it.split(' ').size >= 3 || file.split(Regex("\\s+[-–—]\\s+")).any { part -> same(part, it) }) }.orEmpty()
    }
    val fromFile = split(file, authorHint, known.title)
    val fromFolder = split(folder, authorHint, known.title)
    val fromRoot = split(root, authorHint, known.title)
    val namedBookPart = trailingChapter.find(clean(fileName))?.value?.any(Char::isLetter) == true && !generic(file)
    val labels = "$root $catalog"
    val omnibus = Regex("\\b(omnibus|collection|trilogy|complete)\\b|\\bbooks\\s+\\d", RegexOption.IGNORE_CASE).containsMatchIn(labels) &&
      !Regex("\\bbook\\s+\\d+\\b", RegexOption.IGNORE_CASE).containsMatchIn(catalog)
    val specificFolder = !generic(folder) && !same(folder, root) && !same(folder, authorHint) &&
      !Regex("^(audiobooks?|mp3|m4b|cd\\s*\\d+|disc\\s*\\d+)$", RegexOption.IGNORE_CASE).matches(folder)
    val chosen = when {
      // A named book split into parts can live in a series folder (e.g. Thomas Fool).
      namedBookPart -> fromFile
      // Named chapters are not standalone book titles. A containing omnibus book folder wins.
      specificFolder && (generic(file) || omnibus || chapterTrack) -> fromFolder
      known.title.isNotBlank() && !omnibus -> known
      chapterTrack && !omnibus && !generic(root) && !same(root, authorHint) -> fromRoot
      !generic(file) -> fromFile
      specificFolder -> fromFolder
      !generic(root) -> fromRoot
      else -> BookIdentity(known.title.ifBlank { "Audiobook" })
    }
    return chosen.copy(author = chosen.author.ifBlank { authorHint }.takeUnless { same(it, chosen.title) }.orEmpty())
  }
}

/** One deterministic order for both native HTTP and browser-rendered results. */
object BookSearch {
  fun rank(editions: List<BookEdition>, query: String, title: String = query, author: String = ""): List<BookEdition> {
    fun tokens(text: String) = ReadAlongText.tokens(text).toSet()
    val wanted = tokens(title)
    val authorTokens = tokens(author)
    return editions.distinctBy { it.url.substringBefore('#').trimEnd('/') }.sortedByDescending { edition ->
      val found = tokens(edition.title)
      val overlap = wanted.intersect(found).size
      (if (found == wanted) 1000 else 0) + (if (wanted.isNotEmpty() && found.containsAll(wanted)) 300 else 0) +
        (if (wanted.isEmpty()) 0 else 200 * overlap / wanted.size) - (found - wanted).size.coerceAtMost(30) +
        authorTokens.intersect(tokens(edition.author)).size * 20 + (if (edition.language.contains("english", true)) 5 else 0)
    }
  }

  fun matchesTitle(edition: BookEdition, title: String): Boolean {
    val wanted = ReadAlongText.tokens(title).toSet()
    return wanted.isNotEmpty() && ReadAlongText.tokens(edition.title).toSet().containsAll(wanted)
  }
}
