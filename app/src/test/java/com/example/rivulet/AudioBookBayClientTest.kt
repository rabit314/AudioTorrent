package com.example.rivulet

import com.example.rivulet.data.audiobookbay.AudioBookBayClient
import com.example.rivulet.data.audiobookbay.AudioBookItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioBookBayClientTest {

  private val sampleListHtml =
    """
    <div class="post">
      <div class="postTitle"><h2><a href="/abss/victoria-station-season-1-steve-chambers/" rel="bookmark">Victoria Station season 1 - Steve Chambers</a></h2></div>
      <div class="postInfo">Category: Historical Fiction&nbsp; Radio Productions&nbsp; <br />Language: English<span style="margin-left:100px;">Keywords: Fun&nbsp; </span><br /></div>
      <div class="postContent">
        <div class="center">
          <p class="center"><a href="https://audiobookbay.lu/abss/victoria-station-season-1-steve-chambers/"><img src="https://ichef.bbci.co.uk/images/ic/896x504/p038hnqf.jpg" alt="Steve Chambers" width="250" /></a></p>
        </div>
        <p style='text-align:center;'>Posted: 23 Oct 2026<br />Format: <span style='color:#a00;'>M4B</span> / Bitrate: <span style='color:#a00;'>?</span><br />File Size: <span style='color:#00f;'>193.29</span> MBs</p>
      </div>
    </div>
    """.trimIndent()

  private val sampleDetailHtml =
    """
    <html>
      <body>
        <div class="postTitle"><h2>Victoria Station season 1 - Steve Chambers</h2></div>
        <div class="postContent">
          <p>A gripping tale set in London.</p>
          <table class="torrent_info">
            <tr>
              <td>Info Hash:</td>
              <td>e3b0c44298fc1c149afbf4c8996fb92427ae41e4</td>
            </tr>
          </table>
          <p>Tracker: udp://tracker.opentrackr.org:1337/announce</p>
        </div>
      </body>
    </html>
    """.trimIndent()

  @Test
  fun testParseListHtml() {
    val client = AudioBookBayClient()
    val items = client.parseListHtml(sampleListHtml)

    assertEquals(1, items.size)
    val item = items[0]

    assertEquals("Victoria Station season 1 - Steve Chambers", item.title)
    assertEquals("Steve Chambers", item.author)
    assertEquals("https://audiobookbay.lu/abss/victoria-station-season-1-steve-chambers/", item.detailUrl)
    assertEquals("https://ichef.bbci.co.uk/images/ic/896x504/p038hnqf.jpg", item.coverUrl)
    assertEquals("M4B", item.format)
    assertEquals("193.29 MBs", item.fileSize)
    assertEquals("English", item.language)
  }

  @Test
  fun testParseDetailHtmlBuildsMagnet() {
    val client = AudioBookBayClient()
    val item = AudioBookItem(
      title = "Victoria Station season 1 - Steve Chambers",
      detailUrl = "https://audiobookbay.lu/abss/victoria-station-season-1-steve-chambers/",
      coverUrl = "https://ichef.bbci.co.uk/images/ic/896x504/p038hnqf.jpg",
    )
    val detail = client.parseDetailHtml(sampleDetailHtml, item)

    assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4", detail.infoHash)
    assertTrue("Magnet URI must start with magnet:?xt=urn:btih:", detail.magnetUri.startsWith("magnet:?xt=urn:btih:e3b0c44298fc1c149afbf4c8996fb92427ae41e4"))
    assertTrue("Magnet URI must contain encoded dn parameter", detail.magnetUri.contains("dn=Victoria%20Station"))
    assertTrue("Magnet URI must contain tracker", detail.magnetUri.contains("tracker.opentrackr.org"))
    assertEquals("https://ichef.bbci.co.uk/images/ic/896x504/p038hnqf.jpg", detail.coverUrl)
  }

  @Test
  fun testCategoryToSlug() {
    val client = AudioBookBayClient()
    assertEquals("sci-fi", client.categoryToSlug("Sci-Fi"))
    assertEquals("fantasy", client.categoryToSlug("Fantasy"))
    assertEquals("mystery", client.categoryToSlug("Mystery"))
    assertEquals("thriller", client.categoryToSlug("Thriller"))
    assertEquals("history", client.categoryToSlug("History"))
    assertEquals("general-fiction", client.categoryToSlug("Fiction"))
    assertEquals("general-non-fiction", client.categoryToSlug("Non-Fiction"))
    assertEquals("action", client.categoryToSlug("Action"))
  }

  @Test
  fun testIsLikelyAudioBook_FiltersVideoReleases() {
    val client = AudioBookBayClient()

    // TV episodes and Web-DLs reported by user
    assertFalse(client.isLikelyAudioBook("VisionQuest S01E05 Firewall 1080p WEB-DL", "MKV", "TV Shows"))
    assertFalse(client.isLikelyAudioBook("Tulsa King S04E07 Blind Rooster 1080p", "MP4", ""))
    assertFalse(client.isLikelyAudioBook("Breaking Bad S05E16 720p HDTV x264", "AVI", "Television"))
    assertFalse(client.isLikelyAudioBook("Oppenheimer 2023 2160p UHD Remux HEVC", "MKV", "Movies"))
    assertFalse(client.isLikelyAudioBook("Attack on Titan S04 1080p WEBRip x265", "MKV", "Anime"))

    // Non-audio format indicators
    assertFalse(client.isLikelyAudioBook("Some Title", "MKV", "Audio Books"))
    assertFalse(client.isLikelyAudioBook("Some Title", "AVI", "Audio Books"))
    assertFalse(client.isLikelyAudioBook("Some Book", "EPUB", "E-Books"))
    assertFalse(client.isLikelyAudioBook("Some Graphic Novel.cbr", "CBR", "Comics"))
  }

  @Test
  fun testIsLikelyAudioBook_AcceptsLegitimateAudiobooks() {
    val client = AudioBookBayClient()

    assertTrue(client.isLikelyAudioBook("Victoria Station season 1 - Steve Chambers", "M4B", "Historical Fiction"))
    assertTrue(client.isLikelyAudioBook("Dune - Frank Herbert", "MP3", "Sci-Fi"))
    assertTrue(client.isLikelyAudioBook("The Lord of the Rings - J.R.R. Tolkien", "M4B", "Fantasy"))
    assertTrue(client.isLikelyAudioBook("1984 - George Orwell", "FLAC", "Fiction"))
    assertTrue(client.isLikelyAudioBook("Project Hail Mary - Andy Weir (Unabridged)", "AAC", "Sci-Fi"))
    assertTrue(client.isLikelyAudioBook("Doctor Who: Audio Drama - Episode 1", "MP3", "Radio Productions"))
  }

  @Test
  fun testParseListHtml_FiltersOutVideoTorrents() {
    val client = AudioBookBayClient()
    val mixedHtml = """
      <div class="post">
        <div class="postTitle"><h2><a href="/abss/victoria-station-season-1-steve-chambers/">Victoria Station season 1 - Steve Chambers</a></h2></div>
        <div class="postInfo">Category: Historical Fiction<br />Language: English<br /></div>
        <div class="postContent">
          <p>Posted: 23 Oct 2026<br />Format: M4B<br />File Size: 193 MBs</p>
        </div>
      </div>
      <div class="post">
        <div class="postTitle"><h2><a href="/abss/visionquest-s01e05-firewall-1080p/">VisionQuest S01E05 Firewall 1080p WEB-DL</a></h2></div>
        <div class="postInfo">Category: TV Shows<br />Language: English<br /></div>
        <div class="postContent">
          <p>Posted: 23 Oct 2026<br />Format: MKV<br />File Size: 1.2 GBs</p>
        </div>
      </div>
      <div class="post">
        <div class="postTitle"><h2><a href="/abss/tulsa-king-s04e07-blind-rooster-1080p/">Tulsa King S04E07 Blind Rooster 1080p</a></h2></div>
        <div class="postInfo">Category: Video<br />Language: English<br /></div>
        <div class="postContent">
          <p>Posted: 23 Oct 2026<br />Format: MP4 Video<br />File Size: 950 MBs</p>
        </div>
      </div>
      <div class="post">
        <div class="postTitle"><h2><a href="/abss/dune-frank-herbert/">Dune - Frank Herbert</a></h2></div>
        <div class="postInfo">Category: Sci-Fi<br />Language: English<br /></div>
        <div class="postContent">
          <p>Posted: 23 Oct 2026<br />Format: MP3<br />File Size: 650 MBs</p>
        </div>
      </div>
    """.trimIndent()

    val items = client.parseListHtml(mixedHtml)

    // Should only contain Victoria Station and Dune
    assertEquals(2, items.size)
    assertEquals("Victoria Station season 1 - Steve Chambers", items[0].title)
    assertEquals("Dune - Frank Herbert", items[1].title)
  }

  @Test
  fun testCategorySlugs_AreAllNonEmpty() {
    val client = AudioBookBayClient()
    val genres = listOf("Sci-Fi", "Fantasy", "Fiction", "Mystery", "Thriller", "Horror", "History", "Crime", "Romance", "Non-Fiction", "Biography", "Action", "Adventure", "Business", "Teen / YA")
    for (genre in genres) {
      val slug = client.categoryToSlug(genre)
      assertTrue("Slug for $genre must not be empty", slug.isNotBlank())
      assertFalse("Slug for $genre must not contain uppercase", slug.any { it.isUpperCase() })
      assertFalse("Slug for $genre must not contain spaces", slug.contains(" "))
    }
  }
}
