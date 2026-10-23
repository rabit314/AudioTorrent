package com.example.rivulet

import com.example.rivulet.data.audiobookbay.AudioBookBayClient
import com.example.rivulet.data.audiobookbay.AudioBookItem
import org.junit.Assert.assertEquals
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
    assertEquals("fiction", client.categoryToSlug("Fiction"))
    assertEquals("non-fiction", client.categoryToSlug("Non-Fiction"))
    assertEquals("action-adventure", client.categoryToSlug("Action"))
  }
}
