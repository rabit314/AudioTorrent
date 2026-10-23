package com.example.rivulet.data.lyrics

object IsbnValidator {

  private val ISBN_REGEX = Regex("(?i)(?:ISBN(?:-1[03])?:?\\s*)?(97[89][0-9\\-]{10,14}|[0-9\\-]{9,13}[0-9X])")

  /**
   * Validates whether [raw] is a mathematically valid ISBN-10 or ISBN-13 checksum.
   */
  fun isValid(raw: String?): Boolean {
    if (raw.isNullOrBlank()) return false
    val clean = raw.replace(Regex("[^0-9Xx]"), "")
    return when (clean.length) {
      10 -> isValidIsbn10(clean)
      13 -> isValidIsbn13(clean)
      else -> false
    }
  }

  fun isValidIsbn10(clean: String): Boolean {
    if (clean.length != 10) return false
    var sum = 0
    for (i in 0 until 9) {
      val digit = clean[i] - '0'
      if (digit !in 0..9) return false
      sum += digit * (10 - i)
    }
    val last = clean[9]
    val lastVal = if (last == 'X' || last == 'x') 10 else last - '0'
    if (lastVal !in 0..10) return false
    sum += lastVal
    return sum % 11 == 0
  }

  fun isValidIsbn13(clean: String): Boolean {
    if (clean.length != 13 || !clean.all { it.isDigit() }) return false
    var sum = 0
    for (i in 0 until 12) {
      val d = clean[i] - '0'
      sum += if (i % 2 == 0) d else d * 3
    }
    val check = (10 - (sum % 10)) % 10
    return check == (clean[12] - '0')
  }

  /**
   * Extracts the first valid ISBN-10 or ISBN-13 from text/metadata.
   */
  fun extractFirstValidIsbn(text: String): String? {
    if (text.isBlank()) return null
    for (match in ISBN_REGEX.findAll(text)) {
      val candidate = match.groupValues[1]
      val clean = candidate.replace(Regex("[^0-9Xx]"), "")
      if (isValid(clean)) {
        return formatIsbn(clean)
      }
    }
    return null
  }

  fun formatIsbn(clean: String): String {
    val digits = clean.replace(Regex("[^0-9Xx]"), "")
    return when (digits.length) {
      13 -> "${digits.substring(0, 3)}-${digits.substring(3, 4)}-${digits.substring(4, 9)}-${digits.substring(9, 12)}-${digits.substring(12)}"
      10 -> "${digits.substring(0, 1)}-${digits.substring(1, 4)}-${digits.substring(4, 9)}-${digits.substring(9)}"
      else -> digits
    }
  }
}
