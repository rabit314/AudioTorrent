package com.example.rivulet

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.rivulet.ui.audiobookbay.AudioBookBayScreen
import com.example.rivulet.ui.history.HistoryScreen
import com.example.rivulet.ui.library.LibraryScreen
import com.example.rivulet.ui.player.PlayerScreen
import com.example.rivulet.ui.torrent.TorrentScreen

@Composable
fun MainNavigation(container: AppContainer) {
  val backStack = rememberNavBackStack(Main)

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    entryProvider =
      entryProvider {
        entry<Main> {
          LibraryScreen(
            container = container,
            onOpenTorrent = { infoHash -> backStack.add(TorrentDetail(infoHash)) },
            onOpenPlayer = { backStack.add(Player) },
            onOpenAudioBookBay = { backStack.add(AudioBookBay) },
            onOpenHistory = { backStack.add(History) },
          )
        }
        entry<History> {
          HistoryScreen(
            container = container,
            onBack = { backStack.removeLastOrNull() },
            onOpenPlayer = { backStack.add(Player) },
          )
        }
        entry<AudioBookBay> {
          AudioBookBayScreen(
            container = container,
            onBack = { backStack.removeLastOrNull() },
            onOpenTorrent = { infoHash -> backStack.add(TorrentDetail(infoHash)) },
            onOpenPlayer = { backStack.add(Player) },
          )
        }
        entry<TorrentDetail> { key ->
          TorrentScreen(
            container = container,
            infoHash = key.infoHash,
            onBack = { backStack.removeLastOrNull() },
            onPlay = { backStack.add(Player) },
          )
        }
        entry<Player> {
          PlayerScreen(container = container, onBack = { backStack.removeLastOrNull() })
        }
      },
  )
}
