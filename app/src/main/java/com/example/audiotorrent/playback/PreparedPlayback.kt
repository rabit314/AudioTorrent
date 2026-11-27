package com.example.audiotorrent.playback

/** Ownership is handed to the player only after the requesting coroutine is still current. */
class PreparedPlayback(val url: String, val abandon: suspend () -> Unit = {})
