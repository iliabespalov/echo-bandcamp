package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Streamable.Media.Companion.toServerMedia
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class BandcampExtension : ExtensionClient, SearchFeedClient, TrackClient, AlbumClient, ArtistClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()
            )
        }.build()

    private suspend fun httpGet(url: String): String {
        val resp = http.newCall(Request.Builder().url(url).build()).await()
        if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}: $url")
        return resp.body?.string() ?: ""
    }

    override suspend fun getSettingItems(): List<Setting> = emptyList()
    override fun setSettings(settings: Settings) {}

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        val html = httpGet("https://bandcamp.com/search?q=${encode(query)}&item_type=t")
        val tracks = parseResults(html, "t").map { Shelf.Item(it.toTrack()) }
        val shelves: List<Shelf> = tracks.ifEmpty {
            listOf(Shelf.Item(Track(id = "debug", title = "No results for: $query")))
        }
        return PagedData.Single { shelves }.toFeed()
    }

    private data class RawItem(
        val type: String, val title: String, val artist: String,
        val url: String, val imageUrl: String?
    )

    private suspend fun search(query: String, itemType: String) =
        parseResults(httpGet("https://bandcamp.com/search?q=${encode(query)}&item_type=$itemType"), itemType)

    private suspend fun searchTracks(query: String): List<EchoMediaItem> =
        search(query, "t").map { it.toTrack() }

    private suspend fun searchAlbums(query: String): List<EchoMediaItem> =
        search(query, "a").map { it.toAlbum() }

    private suspend fun searchArtists(query: String): List<EchoMediaItem> =
        search(query, "b").map { it.toArtist() }

    private fun parseResults(html: String, filterType: String): List<RawItem> {
        val results = mutableListOf<RawItem>()
        val blockRe = Regex(
            """<li[^>]*class="[^"]*searchresult[^"]*"[^>]*>(.*?)</li>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val typeRe = Regex("""<div[^>]*class="[^"]*itemtype[^"]*"[^>]*>\s*(\w+)""")
        val hrefRe = Regex("""<a[^>]*href="(https://[^"?#]+)""")
        val titleRe = Regex("""<div[^>]*class="[^"]*heading[^"]*"[^>]*>.*?<a[^>]*>\s*([^<\n]+?)\s*</a>""", RegexOption.DOT_MATCHES_ALL)
        val subRe = Regex("""<div[^>]*class="[^"]*subhead[^"]*"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
        val imgRe = Regex("""<img[^>]*src="(https://f4\.bcbits\.com/img/[^"]+)"""")
        val typeMap = mapOf("t" to "track", "a" to "album", "b" to "band")
        val wanted = typeMap[filterType] ?: filterType
        for (m in blockRe.findAll(html)) {
            val block = m.groupValues[1]
            val type = typeRe.find(block)?.groupValues?.get(1)?.trim()?.lowercase() ?: continue
            if (type != wanted) continue
            val url = hrefRe.find(block)?.groupValues?.get(1)?.trim() ?: continue
            val title = titleRe.find(block)?.groupValues?.get(1)?.trim()?.unescapeHtml() ?: continue
            val sub = subRe.find(block)?.groupValues?.get(1)
                ?.replace(Regex("<[^>]+>"), "")
                ?.lines()?.map { it.trim() }?.filter { it.isNotBlank() }
                ?: emptyList()
            val artist = (sub.lastOrNull() ?: "").removePrefix("by").trim().unescapeHtml()
            val img = imgRe.find(block)?.groupValues?.get(1)
            results += RawItem(type, title, artist, url, img)
        }
        return results
    }

    private fun RawItem.toTrack() = Track(
        id = url, title = title,
        artists = listOfNotNull(artist.takeIf { it.isNotBlank() }?.let { Artist(it, it) }),
        cover = imageUrl?.toImageHolder(), extras = mapOf("url" to url)
    )

    private fun RawItem.toAlbum() = Album(
        id = url, title = title,
        artists = listOfNotNull(artist.takeIf { it.isNotBlank() }?.let { Artist(it, it) }),
        cover = imageUrl?.toImageHolder(), extras = mapOf("url" to url)
    )

    private fun RawItem.toArtist() = Artist(
        id = url, name = title,
        cover = imageUrl?.toImageHolder(), extras = mapOf("url" to url)
    )

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        val url = track.extras["url"] ?: track.id
        val tralbum = extractTralbumData(httpGet(url)) ?: return track
        val info = tralbum.optJSONArray("trackinfo")
            ?.takeIf { it.length() > 0 }?.getJSONObject(0) ?: return track
        val streamUrl = info.optJSONObject("file")?.optString("mp3-128")
        val duration = (info.optDouble("duration", 0.0) * 1000).toLong()
        val artId = tralbum.optString("art_id")
        val cover = artId.takeIf { it.isNotBlank() }
            ?.let { "https://f4.bcbits.com/img/a${it}_9.jpg".toImageHolder() } ?: track.cover
        return track.copy(
            duration = duration.takeIf { it > 0 } ?: track.duration,
            cover = cover,
            streamables = if (!streamUrl.isNullOrBlank()) listOf(Streamable.server(streamUrl, 0)) else emptyList(),
            extras = track.extras + mapOf("streamUrl" to (streamUrl ?: ""))
        )
    }

    override suspend fun loadStreamableMedia(streamable: Streamable, isDownload: Boolean): Streamable.Media =
        streamable.id.toServerMedia(headers = mapOf("Referer" to "https://bandcamp.com/"))

    override suspend fun loadFeed(track: Track): Feed<Shelf>? = null

    override suspend fun loadAlbum(album: Album): Album {
        val url = album.extras["url"] ?: album.id
        val tralbum = extractTralbumData(httpGet(url)) ?: return album
        val current = tralbum.optJSONObject("current") ?: JSONObject()
        val artId = tralbum.optString("art_id")
        val cover = artId.takeIf { it.isNotBlank() }
            ?.let { "https://f4.bcbits.com/img/a${it}_9.jpg".toImageHolder() }
        val artistName = tralbum.optString("artist").ifBlank { current.optString("artist") }
        return album.copy(
            title = current.optString("title").ifBlank { album.title },
            artists = listOfNotNull(artistName.takeIf { it.isNotBlank() }?.let { Artist(it, it) }),
            cover = cover ?: album.cover
        )
    }

    override suspend fun loadTracks(album: Album): Feed<Track>? {
        val url = album.extras["url"] ?: album.id
        val tralbum = extractTralbumData(httpGet(url)) ?: return null
        val artistName = tralbum.optString("artist")
        val artId = tralbum.optString("art_id")
        val cover = artId.takeIf { it.isNotBlank() }
            ?.let { "https://f4.bcbits.com/img/a${it}_9.jpg".toImageHolder() }
        val tracks = tralbum.optJSONArray("trackinfo")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val t = arr.getJSONObject(i)
                val sUrl = t.optJSONObject("file")?.optString("mp3-128")
                Track(
                    id = url + "#" + i,
                    title = t.optString("title"),
                    artists = listOfNotNull(artistName.takeIf { it.isNotBlank() }?.let { Artist(it, it) }),
                    duration = (t.optDouble("duration", 0.0) * 1000).toLong().takeIf { it > 0 },
                    cover = cover,
                    streamables = if (!sUrl.isNullOrBlank()) listOf(Streamable.server(sUrl, 0)) else emptyList(),
                    extras = mapOf("streamUrl" to (sUrl ?: ""), "url" to url)
                )
            }
        } ?: return null
        return PagedData.Single { tracks }.toFeed()
    }

    override suspend fun loadFeed(album: Album): Feed<Shelf>? = null

    override suspend fun loadArtist(artist: Artist): Artist {
        val url = artist.extras["url"] ?: artist.id
        val html = httpGet(url)
        val img = Regex("""<img[^>]*id="band-photo"[^>]*src="([^"]+)"""").find(html)?.groupValues?.get(1)
        return artist.copy(cover = img?.toImageHolder() ?: artist.cover)
    }

    override suspend fun loadFeed(artist: Artist): Feed<Shelf> {
        val url = artist.extras["url"] ?: artist.id
        val html = httpGet(url)
        val albums = parseArtistAlbums(html, url)
        val shelves: List<Shelf> = if (albums.isEmpty()) emptyList()
        else listOf(Shelf.Lists.Items("discography", "Discography", albums))
        return PagedData.Single { shelves }.toFeed()
    }

    private fun parseArtistAlbums(html: String, baseUrl: String): List<EchoMediaItem> {
        val results = mutableListOf<EchoMediaItem>()
        val itemRe = Regex("""<li[^>]*class="[^"]*music-grid-item[^"]*"[^>]*>(.*?)</li>""", RegexOption.DOT_MATCHES_ALL)
        val hrefRe = Regex("""<a[^>]*href="(/(?:album|track)/[^"]+)"""")
        val titleRe = Regex("""<p[^>]*class="[^"]*title[^"]*"[^>]*>\s*([^<]+?)\s*</p>""")
        val imgRe = Regex("""<img[^>]*src="([^"]+)"""")
        val base = baseUrl.trimEnd('/')
        for (m in itemRe.findAll(html)) {
            val block = m.groupValues[1]
            val href = hrefRe.find(block)?.groupValues?.get(1) ?: continue
            val title = titleRe.find(block)?.groupValues?.get(1)?.unescapeHtml() ?: continue
            val img = imgRe.find(block)?.groupValues?.get(1)?.replace("_7.", "_9.")
            val albumUrl = "$base$href"
            results += Album(id = albumUrl, title = title, cover = img?.toImageHolder(), extras = mapOf("url" to albumUrl))
        }
        return results
    }

    private fun extractTralbumData(html: String): JSONObject? {
        listOf(
            Regex("""data-tralbum="(\{.*?})"[\s>]""", RegexOption.DOT_MATCHES_ALL),
            Regex("""var\s+TralbumData\s*=\s*(\{.*?});\s*(?://|var\s|</script)""", RegexOption.DOT_MATCHES_ALL)
        ).forEach { p ->
            p.find(html)?.groupValues?.get(1)?.let { raw ->
                runCatching { return JSONObject(raw.unescapeHtml()) }
            }
        }
        return null
    }

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun String.unescapeHtml() = replace("&amp;", "&").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&apos;", "'").replace("&#x27;", "'").replace("&nbsp;", " ")
}
