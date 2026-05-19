package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.SearchClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.EchoMediaItem.Companion.toMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.StreamableAudio
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Bandcamp extension for Echo music player.
 *
 * Since Bandcamp has no public API, this extension works by:
 *  1. Scraping the HTML search results page at bandcamp.com/search
 *  2. Extracting embedded TralbumData / data-tralbum JSON from album/track pages
 *  3. Serving the mp3-128 preview stream URLs from Bandcamp's CDN
 *
 * Note: Only tracks that Bandcamp allows to stream (previews) will be playable.
 * Purchased/private tracks won't be accessible.
 */
class BandcampExtension : ExtensionClient, SearchClient, TrackClient, AlbumClient, ArtistClient {

    // ─── HTTP ───────────────────────────────────────────────────────────────

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/112 Mobile Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            chain.proceed(request)
        }
        .build()

    private fun get(url: String): String {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}: $url")
            return response.body?.string() ?: ""
        }
    }

    // ─── ExtensionClient ────────────────────────────────────────────────────

    override val settingItems: List<Setting> = emptyList()

    override fun setSettings(settings: Settings) {
        // no user-configurable settings yet
    }

    // ─── SearchClient ───────────────────────────────────────────────────────

    override fun searchFeed(query: String, tab: String?): PagedData<EchoMediaItem> {
        return PagedData.Single {
            when (tab) {
                "Tracks" -> searchTracks(query).map { it.toMediaItem() }
                "Albums" -> searchAlbums(query).map { it.toMediaItem() }
                "Artists" -> searchArtists(query).map { it.toMediaItem() }
                else -> {
                    val results = mutableListOf<EchoMediaItem>()
                    results += searchTracks(query).take(5).map { it.toMediaItem() }
                    results += searchAlbums(query).take(5).map { it.toMediaItem() }
                    results
                }
            }
        }
    }

    override suspend fun searchTabs(query: String): List<String> =
        listOf("All", "Tracks", "Albums", "Artists")

    // ─── Internal search helpers ─────────────────────────────────────────────

    private fun searchTracks(query: String): List<Track> {
        val html = get("https://bandcamp.com/search?q=${encode(query)}&item_type=t")
        return parseSearchResultsAsItems(html, "track").mapNotNull { item ->
            item.asTrack()
        }
    }

    private fun searchAlbums(query: String): List<Album> {
        val html = get("https://bandcamp.com/search?q=${encode(query)}&item_type=a")
        return parseSearchResultsAsItems(html, "album").mapNotNull { item ->
            item.asAlbum()
        }
    }

    private fun searchArtists(query: String): List<Artist> {
        val html = get("https://bandcamp.com/search?q=${encode(query)}&item_type=b")
        return parseSearchResultsAsItems(html, "band").mapNotNull { item ->
            item.asArtist()
        }
    }

    // ─── HTML parsing ────────────────────────────────────────────────────────

    private data class RawItem(
        val type: String,
        val title: String,
        val artist: String,
        val albumTitle: String,
        val url: String,
        val imageUrl: String?
    )

    private fun parseSearchResultsAsItems(html: String, filterType: String?): List<RawItem> {
        val results = mutableListOf<RawItem>()
        // Each search result lives in a <li class="searchresult ..."> block
        val itemPattern = Regex(
            """<li[^>]*class="[^"]*searchresult[^"]*"[^>]*>(.*?)</li>""",
            RegexOption.DOT_MATCHES_ALL
        )
        val typePattern = Regex("""<div[^>]*class="[^"]*itemtype[^"]*"[^>]*>\s*(\w+)\s*</div>""")
        val titlePattern = Regex("""<div[^>]*class="[^"]*heading[^"]*"[^>]*>.*?<a[^>]*href="([^"]+)"[^>]*>\s*([^<]+?)\s*</a>""", RegexOption.DOT_MATCHES_ALL)
        val subheadPattern = Regex("""<div[^>]*class="[^"]*subhead[^"]*"[^>]*>\s*(.*?)\s*</div>""", RegexOption.DOT_MATCHES_ALL)
        val imagePattern = Regex("""<img[^>]*src="([^"]+)"[^>]*class="[^"]*art[^"]*"""")
        val imagePattern2 = Regex("""<div[^>]*class="[^"]*art[^"]*"[^>]*>.*?<img[^>]*src="([^"]+)"""", RegexOption.DOT_MATCHES_ALL)

        for (match in itemPattern.findAll(html)) {
            val block = match.groupValues[1]

            val type = typePattern.find(block)?.groupValues?.get(1)?.lowercase() ?: continue
            if (filterType != null && type != filterType) continue

            val titleMatch = titlePattern.find(block) ?: continue
            val url = titleMatch.groupValues[1].trim()
            val title = titleMatch.groupValues[2].trim().unescapeHtml()

            val subhead = subheadPattern.find(block)?.groupValues?.get(1)
                ?.replace(Regex("<[^>]+>"), "")?.trim()?.unescapeHtml() ?: ""

            val imageUrl = (imagePattern.find(block) ?: imagePattern2.find(block))
                ?.groupValues?.get(1)?.replace("_7.", "_9.")  // upscale thumbnail

            val (artist, album) = when (type) {
                "track" -> {
                    // subhead is typically "by Artist\nfrom Album"
                    val parts = subhead.split("\n").map { it.trim().removePrefix("by").removePrefix("from").trim() }
                    Pair(parts.getOrNull(0) ?: "", parts.getOrNull(1) ?: "")
                }
                "album" -> Pair(subhead.removePrefix("by").trim(), "")
                "band" -> Pair(subhead, "")
                else -> Pair(subhead, "")
            }

            results += RawItem(
                type = type,
                title = title,
                artist = artist,
                albumTitle = album,
                url = if (url.startsWith("http")) url else "https://bandcamp.com$url",
                imageUrl = imageUrl
            )
        }
        return results
    }

    private fun RawItem.asTrack() = Track(
        id = url,
        title = title,
        artists = if (artist.isNotBlank()) listOf(Artist(id = artist, name = artist)) else emptyList(),
        album = if (albumTitle.isNotBlank()) Album(id = url, title = albumTitle) else null,
        cover = imageUrl?.toImageHolder(),
        extras = mapOf("url" to url, "type" to "track")
    )

    private fun RawItem.asAlbum() = Album(
        id = url,
        title = title,
        artists = if (artist.isNotBlank()) listOf(Artist(id = artist, name = artist)) else emptyList(),
        cover = imageUrl?.toImageHolder(),
        extras = mapOf("url" to url)
    )

    private fun RawItem.asArtist() = Artist(
        id = url,
        name = title,
        cover = imageUrl?.toImageHolder(),
        extras = mapOf("url" to url)
    )

    // ─── TrackClient ─────────────────────────────────────────────────────────

    /**
     * Load full track details. Fetches the Bandcamp track page and extracts
     * TralbumData to get the real mp3 stream URL and duration.
     */
    override suspend fun loadTrack(track: Track): Track {
        val url = track.extras["url"] ?: track.id
        val html = get(url)
        val tralbum = extractTralbumData(html) ?: return track

        val trackInfo = tralbum.optJSONArray("trackinfo")?.let {
            if (it.length() > 0) it.getJSONObject(0) else null
        } ?: return track

        val streamUrl = trackInfo.optJSONObject("file")?.optString("mp3-128")
        val duration = trackInfo.optDouble("duration", 0.0).toLong() * 1000

        val artId = tralbum.optString("art_id")
        val coverUrl = if (artId.isNotBlank()) "https://f4.bcbits.com/img/a${artId}_9.jpg" else track.cover?.url

        return track.copy(
            duration = if (duration > 0) duration else track.duration,
            cover = coverUrl?.toImageHolder() ?: track.cover,
            streamables = if (streamUrl != null && streamUrl.isNotBlank()) {
                listOf(Streamable.server(streamUrl, 0))
            } else emptyList(),
            extras = track.extras + mapOf(
                "streamUrl" to (streamUrl ?: ""),
                "albumTitle" to (tralbum.optString("current")
                    .let { tralbum.optJSONObject("current")?.optString("title") ?: "" })
            )
        )
    }

    override suspend fun getStreamableAudio(streamable: Streamable): StreamableAudio {
        return StreamableAudio.StreamableUrl(
            request = Streamable.Http(
                url = streamable.id,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36",
                    "Referer" to "https://bandcamp.com/"
                )
            )
        )
    }

    // ─── AlbumClient ─────────────────────────────────────────────────────────

    override suspend fun loadAlbum(album: Album): Album {
        val url = album.extras["url"] ?: album.id
        val html = get(url)
        val tralbum = extractTralbumData(html) ?: return album

        val current = tralbum.optJSONObject("current") ?: JSONObject()
        val artId = tralbum.optString("art_id")
        val coverUrl = if (artId.isNotBlank()) "https://f4.bcbits.com/img/a${artId}_9.jpg" else null
        val artistName = tralbum.optString("artist").ifBlank { current.optString("artist") }
        val releaseDate = current.optString("release_date")

        val tracks = tralbum.optJSONArray("trackinfo")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val t = arr.getJSONObject(i)
                val streamUrl = t.optJSONObject("file")?.optString("mp3-128")
                val trackUrl = tralbum.optString("url").let { base ->
                    val slug = t.optString("title_link")
                    if (slug.isNotBlank() && base.isNotBlank()) "$base$slug" else url
                }
                Track(
                    id = trackUrl,
                    title = t.optString("title"),
                    artists = if (artistName.isNotBlank()) listOf(Artist(id = artistName, name = artistName)) else emptyList(),
                    duration = (t.optDouble("duration", 0.0).toLong() * 1000).takeIf { it > 0 },
                    cover = coverUrl?.toImageHolder(),
                    streamables = if (!streamUrl.isNullOrBlank()) listOf(Streamable.server(streamUrl, 0)) else emptyList(),
                    extras = mapOf("url" to trackUrl, "streamUrl" to (streamUrl ?: ""))
                )
            }
        } ?: emptyList()

        return album.copy(
            title = current.optString("title").ifBlank { album.title },
            artists = if (artistName.isNotBlank()) listOf(Artist(id = artistName, name = artistName)) else album.artists,
            cover = coverUrl?.toImageHolder() ?: album.cover,
            releaseDate = releaseDate.ifBlank { null },
            tracks = PagedData.Single { tracks },
            extras = album.extras
        )
    }

    override fun getShelves(album: Album): PagedData<Feed.Shelf> = PagedData.Single { emptyList() }

    // ─── ArtistClient ────────────────────────────────────────────────────────

    override suspend fun loadArtist(artist: Artist): Artist {
        val url = artist.extras["url"] ?: artist.id
        val html = get(url)

        // Extract artist bio
        val bioPattern = Regex("""<meta\s+name="Description"\s+content="([^"]+)"""")
        val bio = bioPattern.find(html)?.groupValues?.get(1)?.unescapeHtml()

        // Extract artist image
        val imgPattern = Regex("""<img[^>]*id="band-photo"[^>]*src="([^"]+)"""")
        val imgUrl = imgPattern.find(html)?.groupValues?.get(1)

        return artist.copy(
            description = bio,
            cover = imgUrl?.toImageHolder() ?: artist.cover
        )
    }

    override fun getArtistFeed(artist: Artist): PagedData<Feed.Shelf> {
        return PagedData.Single {
            val url = artist.extras["url"] ?: artist.id
            val html = get(url)
            val albums = parseArtistAlbums(html, url)
            if (albums.isEmpty()) emptyList()
            else listOf(Feed.Shelf.ItemShelf(
                id = "discography",
                title = "Discography",
                list = PagedData.Single { albums.map { it.toMediaItem() } }
            ))
        }
    }

    private fun parseArtistAlbums(html: String, baseUrl: String): List<Album> {
        val results = mutableListOf<Album>()
        val itemPattern = Regex(
            """<li[^>]*class="[^"]*music-grid-item[^"]*"[^>]*>(.*?)</li>""",
            RegexOption.DOT_MATCHES_ALL
        )
        val hrefPattern = Regex("""<a[^>]*href="(/(?:album|track)/[^"]+)"""")
        val titlePattern = Regex("""<p[^>]*class="[^"]*title[^"]*"[^>]*>\s*([^<]+?)\s*</p>""")
        val imgPattern = Regex("""<img[^>]*src="([^"]+)"""")

        val artistBase = baseUrl.trimEnd('/')

        for (match in itemPattern.findAll(html)) {
            val block = match.groupValues[1]
            val href = hrefPattern.find(block)?.groupValues?.get(1) ?: continue
            val title = titlePattern.find(block)?.groupValues?.get(1)?.unescapeHtml() ?: continue
            val img = imgPattern.find(block)?.groupValues?.get(1)?.replace("_7.", "_9.")
            val albumUrl = "$artistBase$href"
            results += Album(
                id = albumUrl,
                title = title,
                cover = img?.toImageHolder(),
                extras = mapOf("url" to albumUrl)
            )
        }
        return results
    }

    // ─── HomeFeedClient (optional) ─────────────────────────────────────────
    // Not implemented — Bandcamp's discover page requires JS rendering.

    // ─── Utility ─────────────────────────────────────────────────────────────

    /**
     * Extract the TralbumData JSON embedded in a Bandcamp page.
     * Bandcamp stores it as:   data-tralbum="{ ... }"   on the <div id="tralbumCollect"> element,
     * or as an inline script:  var TralbumData = { ... }
     */
    private fun extractTralbumData(html: String): JSONObject? {
        // Method 1: data-tralbum attribute
        val attrPattern = Regex("""data-tralbum="(\{.*?})"(?:\s|>)""", RegexOption.DOT_MATCHES_ALL)
        attrPattern.find(html)?.groupValues?.get(1)?.let { raw ->
            runCatching { return JSONObject(raw.unescapeHtml()) }
        }

        // Method 2: inline script  var TralbumData = { ... };
        val scriptPattern = Regex(
            """var\s+TralbumData\s*=\s*(\{.*?});\s*(?://|var\s|</script)""",
            RegexOption.DOT_MATCHES_ALL
        )
        scriptPattern.find(html)?.groupValues?.get(1)?.let { raw ->
            runCatching { return JSONObject(raw) }
        }

        // Method 3: data-embed attribute (some pages)
        val embedPattern = Regex("""data-embed="(\{.*?})"(?:\s|>)""", RegexOption.DOT_MATCHES_ALL)
        embedPattern.find(html)?.groupValues?.get(1)?.let { raw ->
            runCatching { return JSONObject(raw.unescapeHtml()) }
        }

        return null
    }

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun String.unescapeHtml(): String = this
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&#x27;", "'")
        .replace("&nbsp;", " ")

    private val String.url get() = this

    // Helper to get url from ImageHolder (for cover duplication avoidance)
    private val dev.brahmkshatriya.echo.common.models.ImageHolder?.url
        get() = (this as? dev.brahmkshatriya.echo.common.models.ImageHolder.UrlRequestImageHolder)?.request?.url
}
