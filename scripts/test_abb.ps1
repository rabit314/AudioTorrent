$wc = New-Object System.Net.WebClient
$wc.Headers.Add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
$detailHtml = $wc.DownloadString("https://audiobookbay.lu/abss/victoria-station-season-1-steve-chambers/")

Write-Output "Detail page length: $($detailHtml.Length)"

# Look for Info Hash or magnet
if ($detailHtml -match '(?i)info\s*hash') {
    Write-Output "Found 'Info Hash' in detail page!"
}

# Find snippet around info hash
$idx = $detailHtml.IndexOf("Info Hash")
if ($idx -lt 0) { $idx = $detailHtml.IndexOf("info_hash") }
if ($idx -lt 0) { $idx = $detailHtml.IndexOf("hash") }

if ($idx -ge 0) {
    $start = [Math]::Max(0, $idx - 100)
    Write-Output "Snippet around hash:"
    Write-Output $detailHtml.Substring($start, [Math]::Min(800, $detailHtml.Length - $start))
}

# Look for tracker
$tIdx = $detailHtml.IndexOf("udp://")
if ($tIdx -ge 0) {
    Write-Output "Found udp tracker:"
    Write-Output $detailHtml.Substring([Math]::Max(0, $tIdx - 50), 300)
}
