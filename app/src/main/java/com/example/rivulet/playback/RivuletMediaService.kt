package com.example.rivulet.playback

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
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.rivulet.MainActivity
import com.example.rivulet.R
import com.example.rivulet.RivuletApp

@OptIn(UnstableApi::class)
class RivuletMediaService : MediaSessionService() {

  private var mediaSession: MediaSession? = null

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
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    super.onStartCommand(intent, flags, startId)

    // Satisfy Android foreground service startup requirement immediately
    val notification =
      NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("AudioTorrent")
        .setContentText("Audio playback")
        .setOngoing(true)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

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
    mediaSession?.run {
      release()
      mediaSession = null
    }
    super.onDestroy()
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
  }
}
