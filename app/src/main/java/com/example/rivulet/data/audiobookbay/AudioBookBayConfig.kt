package com.example.rivulet.data.audiobookbay

object AudioBookBayConfig {
  const val BASE_URL = "https://audiobookbay.lu"

  // Optional AudiobookBay credentials (kept blank for public git distribution)
  const val LOGIN = ""
  const val USERNAME = ""
  const val EMAIL = ""
  const val PASSWORD = ""
  const val UID = ""

  val DEFAULT_TRACKERS = listOf(
    "udp://tracker.opentrackr.org:1337/announce",
    "udp://open.stealth.si:80/announce",
    "udp://tracker.torrent.eu.org:451/announce",
    "udp://explodie.org:6969/announce",
    "udp://tracker.coppersurfer.tk:6969/announce",
    "udp://p4p.arenabg.com:1337/announce",
    "udp://tracker.internetwarriors.net:1337/announce",
    "udp://9.rarbg.to:2920/announce",
    "udp://tracker.openbittorrent.com:6969/announce"
  )
}
