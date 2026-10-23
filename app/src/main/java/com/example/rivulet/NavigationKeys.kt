package com.example.rivulet

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object Main : NavKey

@Serializable data object AudioBookBay : NavKey

@Serializable data class TorrentDetail(val infoHash: String) : NavKey

@Serializable data object Player : NavKey

@Serializable data object History : NavKey
