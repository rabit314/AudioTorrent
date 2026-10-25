package com.example.rivulet.data.lyrics

enum class SyncCorrectionPhase { IDLE, SELECTING, MATCHING, APPLIED, ERROR }

data class SyncTarget(val track: ReadAlongTrack, val bookId: String, val source: TextSyncSource, val sentenceId: Long)

data class SyncCorrectionState(
  val phase: SyncCorrectionPhase = SyncCorrectionPhase.IDLE,
  val target: SyncTarget? = null,
  val message: String? = null,
  val needsModel: Boolean = false,
  val canRetry: Boolean = false,
  val event: Long = 0,
) {
  val isEditing: Boolean get() = phase in setOf(SyncCorrectionPhase.SELECTING, SyncCorrectionPhase.MATCHING, SyncCorrectionPhase.ERROR)
}

data class SyncReference(val target: SyncTarget, val positionMs: Long, val durationMs: Long) {
  val windowStartMs: Long get() = (positionMs - 5_000).coerceAtLeast(0)
  val windowDurationMs: Long get() = minOf(30_000, durationMs - windowStartMs).coerceAtLeast(0)
}

fun ReadAlongState.syncTarget(sentenceId: Long): SyncTarget? {
  val currentTrack = track ?: return null
  val currentBook = bookId ?: return null
  val currentSource = source ?: return null
  return if (lines.any { it.id == sentenceId }) SyncTarget(currentTrack, currentBook, currentSource, sentenceId) else null
}

fun ReadAlongState.accepts(target: SyncTarget): Boolean = syncTarget(target.sentenceId) == target
