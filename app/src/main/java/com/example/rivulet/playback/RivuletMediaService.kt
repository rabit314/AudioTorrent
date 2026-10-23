package com.example.rivulet.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaStyleNotificationHelper
import com.example.rivulet.MainActivity
import com.example.rivulet.R
import com.example.rivulet.RivuletApp
import com.example.rivulet.data.lyrics.BookTitleCleaner

@OptIn(UnstableApi::class)
class RivuletMediaService : MediaSessionService() {

  private var mediaSession: MediaSession? = null
  private var playerListener: Player.Listener? = null

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()

    val app = application as? RivuletApp ?: return
    val playback = app.container.playback
    val rawPlayer = playback.player

    val sessionActivityIntent = Intent(this, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    val sessionActivity = PendingIntent.getActivity(
      this,
      0,
      sessionActivityIntent,
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    val forwardingPlayer = object : ForwardingPlayer(rawPlayer) {
      override fun getAvailableCommands(): Player.Commands {
        return super.getAvailableCommands().buildUpon()
          .add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
          .add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
          .add(COMMAND_SEEK_TO_NEXT)
          .add(COMMAND_SEEK_TO_PREVIOUS)
          .build()
      }

      override fun isCommandAvailable(command: Int): Boolean {
        return when (command) {
          COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, COMMAND_SEEK_TO_NEXT -> playback.hasNext()
          COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, COMMAND_SEEK_TO_PREVIOUS -> playback.hasPrevious()
          else -> super.isCommandAvailable(command)
        }
      }

      override fun seekToNextMediaItem() {
        playback.playNext()
      }

      override fun seekToNext() {
        playback.playNext()
      }

      override fun seekToPreviousMediaItem() {
        playback.playPrevious()
      }

      override fun seekToPrevious() {
        playback.playPrevious()
      }

      override fun hasNextMediaItem(): Boolean = playback.hasNext()
      override fun hasPreviousMediaItem(): Boolean = playback.hasPrevious()
    }

    mediaSession =
      MediaSession.Builder(this, forwardingPlayer)
        .setSessionActivity(sessionActivity)
        .build()

    // Listen for player state changes to keep the notification play/pause button in sync
    playerListener = object : Player.Listener {
      override fun onIsPlayingChanged(isPlaying: Boolean) {
        updateNotification()
      }

      override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
        updateNotification()
      }

      override fun onPlaybackStateChanged(playbackState: Int) {
        updateNotification()
      }
    }
    rawPlayer.addListener(playerListener!!)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    super.onStartCommand(intent, flags, startId)

    val app = application as? RivuletApp
    val playback = app?.container?.playback

    when (intent?.action) {
      ACTION_PLAY_PAUSE -> {
        playback?.togglePlayPause()
      }
      ACTION_PLAY -> {
        playback?.resume()
      }
      ACTION_PAUSE -> {
        playback?.pause()
      }
      ACTION_STOP -> {
        playback?.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return START_NOT_STICKY
      }
    }

    // Build notification with interactive Play/Pause action and start foreground
    val notification = buildNotification()
    runCatching {
      val type =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
          0
        }
      ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    return START_STICKY
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
    return mediaSession
  }

  override fun onDestroy() {
    val app = application as? RivuletApp
    playerListener?.let { listener ->
      app?.container?.playback?.player?.removeListener(listener)
      playerListener = null
    }
    mediaSession?.run {
      release()
      mediaSession = null
    }
    super.onDestroy()
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    val app = application as? RivuletApp
    val playback = app?.container?.playback
    if (playback?.player?.isPlaying != true) {
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
    }
    super.onTaskRemoved(rootIntent)
  }

  private fun buildNotification(): Notification {
    val app = application as? RivuletApp
    val playback = app?.container?.playback
    val rawPlayer = playback?.player

    val isPlaying = rawPlayer?.isPlaying == true
    val metadata = rawPlayer?.mediaMetadata

    val state = playback?.state?.value
    val rawTitle = metadata?.title?.toString()?.takeIf { it.isNotBlank() }
      ?: state?.title?.takeIf { it.isNotBlank() }
      ?: "AudioTorrent"
    val displayTitle = BookTitleCleaner.cleanFileNameOrTrack(rawTitle).ifBlank { rawTitle }

    val rawSubtitle = metadata?.artist?.toString()?.takeIf { it.isNotBlank() }
      ?: state?.subtitle?.takeIf { it.isNotBlank() }
      ?: if (isPlaying) "Playing" else "Paused"
    val displaySubtitle = BookTitleCleaner.cleanContainerTitle(rawSubtitle).ifBlank { rawSubtitle }

    val sessionActivityIntent = Intent(this, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    val sessionActivity = PendingIntent.getActivity(
      this,
      0,
      sessionActivityIntent,
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    // Play/Pause Action PendingIntent
    val playPauseIntent = Intent(this, RivuletMediaService::class.java).apply {
      action = ACTION_PLAY_PAUSE
    }
    val playPausePendingIntent = PendingIntent.getService(
      this,
      100,
      playPauseIntent,
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    val playPauseAction = if (isPlaying) {
      NotificationCompat.Action.Builder(
        R.drawable.ic_pause,
        "Pause",
        playPausePendingIntent,
      ).build()
    } else {
      NotificationCompat.Action.Builder(
        R.drawable.ic_play_arrow,
        "Play",
        playPausePendingIntent,
      ).build()
    }

    val builder = NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle(displayTitle)
      .setContentText(displaySubtitle)
      .setContentIntent(sessionActivity)
      .setOngoing(isPlaying)
      .setSilent(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .addAction(playPauseAction)

    mediaSession?.let { session ->
      builder.setStyle(
        MediaStyleNotificationHelper.MediaStyle(session)
          .setShowActionsInCompactView(0)
      )
    }

    return builder.build()
  }

  private fun updateNotification() {
    val notification = buildNotification()
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    manager?.notify(NOTIFICATION_ID, notification)
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel =
        NotificationChannel(
          CHANNEL_ID,
          "Audio Playback",
          NotificationManager.IMPORTANCE_LOW,
        ).apply {
          description = "Background audio streaming playback notification"
          setShowBadge(false)
        }
      val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
      manager?.createNotificationChannel(channel)
    }
  }

  companion object {
    const val CHANNEL_ID = "rivulet_audio_playback"
    const val NOTIFICATION_ID = 1001

    const val ACTION_PLAY_PAUSE = "com.example.rivulet.ACTION_PLAY_PAUSE"
    const val ACTION_PLAY = "com.example.rivulet.ACTION_PLAY"
    const val ACTION_PAUSE = "com.example.rivulet.ACTION_PAUSE"
    const val ACTION_STOP = "com.example.rivulet.ACTION_STOP"
  }
}
