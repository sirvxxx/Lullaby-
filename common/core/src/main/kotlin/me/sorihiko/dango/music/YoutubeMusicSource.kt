package me.sorihiko.dango.music

// =====================================================================
//  YoutubeMusicSource (jar-only)
//  - search : NewPipeExtractor (YouTube Music "songs", fallback ke video)
//  - songUrl: ambil stream audio Opus/WebM tertinggi, download (chunked)
//             ke cache, sajikan lewat server HTTP lokal 127.0.0.1
//  - decode : OpusWebmDecoder (lihat file sebelah), dipasang di
//             AudioDecoder.open() -> player/kontrol bawaan mod tetap dipakai
//  Tanpa yt-dlp / ffmpeg / file tambahan di PC pemain.
// =====================================================================

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.lyrics.Lyrics
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

object YoutubeMusicSource : MusicSourceApi {

    override val source: MusicSource
        get() = MusicSource.YOUTUBE

    private val idRegex = Regex("[A-Za-z0-9_-]{6,20}")

    // ------------------------------------------------ charts & trending (YouTube Music internal API)
    // Tidak resmi: endpoint internal music.youtube.com bisa berubah. Kalau gagal, daftar kosong (tidak crash).
    private const val REMIX_VERSION = "1.20250101.01.00"
    private const val CHARTS_ID = -2001L
    private const val TRENDING_ID = -2002L
    private const val CACHE_MS = 60 * 60 * 1000L
    @Volatile private var chartCache: Pair<Long, List<NeteaseTrack>>? = null

    override fun charts(): List<NeteasePlaylist> =
        listOf(NeteasePlaylist(CHARTS_ID, "YouTube Music Charts", 0, "", owned = false))

    override fun recommend(): List<NeteasePlaylist> =
        listOf(NeteasePlaylist(TRENDING_ID, "Trending now", 0, "", owned = false))

    override fun playlistTracks(id: Long): List<NeteaseTrack> = when (id) {
        CHARTS_ID -> chartTracks()
        TRENDING_ID -> chartTracks().shuffled()   // Home: urutan diacak setiap dibuka
        else -> libraryTracks(id)
    }

    /** Home: lagu trending (diacak setiap dipanggil) */
    fun trendingTracks(): List<NeteaseTrack> = playlistTracks(TRENDING_ID)

    /** Charts: lagu chart YouTube Music (urutan asli) */
    fun chartTracksNow(): List<NeteaseTrack> = playlistTracks(CHARTS_ID)

    /** Cadangan kalau chart/trending kosong: hasil pencarian beberapa kueri populer (pencarian sudah jalan di app) */
    fun fallbackSongs(): List<NeteaseTrack> =
        listOf("top hits this week", "trending songs", "viral songs 2026", "lagu indonesia terpopuler")
            .flatMap { q -> try { search(q) } catch (_: Exception) { emptyList() } }
            .distinctBy { it.nativeId }
            .take(60)

    @Synchronized private fun chartTracks(): List<NeteaseTrack> {
        val now = System.currentTimeMillis()
        chartCache?.let { if (now - it.first < CACHE_MS) return it.second }
        val tracks = fetchCharts()
        if (tracks.isNotEmpty()) chartCache = now to tracks
        return tracks
    }

    private fun fetchCharts(): List<NeteaseTrack> {
        val client = JsonObject().apply {
            addProperty("clientName", "WEB_REMIX")
            addProperty("clientVersion", REMIX_VERSION)
            addProperty("hl", "en")
            addProperty("gl", "US")
        }
        val body = JsonObject().apply {
            add("context", JsonObject().apply { add("client", client) })
            addProperty("browseId", "FEmusic_charts")
        }
        val root = postJson("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false", body)
            ?: throw SourceAccessException("YouTube Music charts are unavailable right now")
        val out = LinkedHashMap<String, NeteaseTrack>()
        collectItems(root) { r -> trackFromRenderer(r)?.let { t -> out.putIfAbsent(t.nativeId, t) } }
        return out.values.take(100)
    }

    // ------------------------------------------------ akun: playlist milik sendiri (butuh cookie login)
    private const val BROWSE_URL = "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false"
    private val libraryIds = HashMap<Long, String>()     // id di aplikasi -> browseId ("VL...")

    override val libraryWarning: String
        get() = if (YoutubeAuth.loggedIn) "" else "Tempel cookie YouTube Music ke config/lullaby/youtube-cookie.txt untuk melihat playlist kamu"

    override fun playlists(): List<NeteasePlaylist> {
        if (!YoutubeAuth.loggedIn) return emptyList()
        val root = postJson(BROWSE_URL, browseBody("FEmusic_liked_playlists")) ?: return emptyList()
        val out = ArrayList<NeteasePlaylist>()
        val seen = HashSet<String>()
        collectTwoRow(root) { name, browseId, cover ->
            if (seen.add(browseId)) {
                val id = MusicSources.stableId(MusicSource.YOUTUBE, "lib:$browseId")
                synchronized(libraryIds) { libraryIds[id] = browseId }
                out += NeteasePlaylist(id, name, 0, cover, owned = true)
            }
        }
        return out
    }

    private fun libraryTracks(id: Long): List<NeteaseTrack> {
        val browseId = synchronized(libraryIds) { libraryIds[id] } ?: return emptyList()
        if (!YoutubeAuth.loggedIn) return emptyList()
        val root = postJson(BROWSE_URL, browseBody(browseId))
            ?: throw SourceAccessException("Playlist YouTube Music tidak bisa dibuka")
        val out = LinkedHashMap<String, NeteaseTrack>()
        collectItems(root) { r -> trackFromRenderer(r)?.let { out.putIfAbsent(it.nativeId, it) } }
        return out.values.toList()
    }

    private fun browseBody(browseId: String): JsonObject {
        val client = JsonObject().apply {
            addProperty("clientName", "WEB_REMIX")
            addProperty("clientVersion", REMIX_VERSION)
            addProperty("hl", "en")
            addProperty("gl", "US")
        }
        return JsonObject().apply {
            add("context", JsonObject().apply { add("client", client) })
            addProperty("browseId", browseId)
        }
    }

    /** Baris playlist di halaman library (musicTwoRowItemRenderer): judul, browseId "VL...", cover */
    private fun collectTwoRow(e: JsonElement, onItem: (String, String, String) -> Unit) {
        if (e.isJsonObject) {
            val o = e.asJsonObject
            o.get("musicTwoRowItemRenderer")?.takeIf { it.isJsonObject }?.asJsonObject?.let { r ->
                val title = r.get("title")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("runs")?.takeIf { it.isJsonArray }?.asJsonArray?.firstOrNull()
                    ?.takeIf { it.isJsonObject }?.asJsonObject?.get("text")?.takeIf { it.isJsonPrimitive }?.asString
                val browseId = findBrowseId(r)
                if (!title.isNullOrBlank() && browseId != null && browseId.startsWith("VL")) {
                    onItem(title, browseId, coverOfRow(r))
                }
            }
            for (v in o.entrySet().map { it.value }) collectTwoRow(v, onItem)
        } else if (e.isJsonArray) {
            for (v in e.asJsonArray) collectTwoRow(v, onItem)
        }
    }

    private fun findBrowseId(e: JsonElement): String? {
        if (e.isJsonObject) {
            val o = e.asJsonObject
            o.get("browseId")?.takeIf { it.isJsonPrimitive }?.asString?.let { return it }
            for (v in o.entrySet().map { it.value }) findBrowseId(v)?.let { return it }
        } else if (e.isJsonArray) {
            for (v in e.asJsonArray) findBrowseId(v)?.let { return it }
        }
        return null
    }

    private fun coverOfRow(r: JsonObject): String {
        val thumbs = r.get("thumbnailRenderer")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("musicThumbnailRenderer")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("thumbnail")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("thumbnails")?.takeIf { it.isJsonArray }?.asJsonArray
        return thumbs?.lastOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("url")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    }

    private fun postJson(url: String, body: JsonObject): JsonObject? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.setRequestProperty("Origin", "https://music.youtube.com")
            conn.setRequestProperty("Referer", "https://music.youtube.com/")
            conn.setRequestProperty("X-YouTube-Client-Name", "67")
            conn.setRequestProperty("X-YouTube-Client-Version", REMIX_VERSION)
            YoutubeAuth.authHeaders()?.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) {
                DangoLogger.warn("YouTube Music {} -> HTTP {}", url.substringBefore('?'), conn.responseCode)
                return null
            }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            JsonParser().parse(text).takeIf { it.isJsonObject }?.asJsonObject
        } catch (e: Exception) {
            DangoLogger.warn("YouTube Music request failed: {}", e.javaClass.simpleName)
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Kumpulkan semua baris lagu (musicResponsiveListItemRenderer) dalam urutan muncul */
    private fun collectItems(e: JsonElement, onItem: (JsonObject) -> Unit) {
        if (e.isJsonObject) {
            val o = e.asJsonObject
            o.get("musicResponsiveListItemRenderer")?.takeIf { it.isJsonObject }?.asJsonObject?.let(onItem)
            for (v in o.entrySet().map { it.value }) collectItems(v, onItem)
        } else if (e.isJsonArray) {
            for (v in e.asJsonArray) collectItems(v, onItem)
        }
    }

    private fun trackFromRenderer(r: JsonObject): NeteaseTrack? {
        val vid = findVideoId(r) ?: return null
        val title = flexText(r, 0)
        if (title.isEmpty()) return null
        val artist = flexText(r, 1).substringBefore(" • ").trim().ifEmpty { "YouTube" }
        return NeteaseTrack(
            MusicSources.stableId(MusicSource.YOUTUBE, vid),
            title,
            listOf(artist),
            0L,
            coverOf(r, vid),
            MusicSource.YOUTUBE,
            vid
        )
    }

    private fun findVideoId(e: JsonElement): String? {
        if (e.isJsonObject) {
            val o = e.asJsonObject
            o.get("videoId")?.takeIf { it.isJsonPrimitive }?.asString?.let { if (idRegex.matches(it)) return it }
            for (v in o.entrySet().map { it.value }) findVideoId(v)?.let { return it }
        } else if (e.isJsonArray) {
            for (v in e.asJsonArray) findVideoId(v)?.let { return it }
        }
        return null
    }

    /** Teks kolom ke-[idx] (0 = judul, 1 = artis) */
    private fun flexText(r: JsonObject, idx: Int): String {
        val cols = r.get("flexColumns")?.takeIf { it.isJsonArray }?.asJsonArray ?: return ""
        if (idx >= cols.size()) return ""
        val col = cols.get(idx).takeIf { it.isJsonObject }?.asJsonObject
            ?.get("musicResponsiveListItemFlexColumnRenderer")?.takeIf { it.isJsonObject }?.asJsonObject ?: return ""
        val runs = col.get("text")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("runs")?.takeIf { it.isJsonArray }?.asJsonArray ?: return ""
        return runs.joinToString("") { it.asJsonObject.get("text")?.takeIf { t -> t.isJsonPrimitive }?.asString ?: "" }.trim()
    }

    private fun coverOf(r: JsonObject, vid: String): String {
        val thumbs = r.get("thumbnail")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("musicThumbnailRenderer")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("thumbnail")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("thumbnails")?.takeIf { it.isJsonArray }?.asJsonArray
        val url = thumbs?.lastOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("url")?.takeIf { it.isJsonPrimitive }?.asString
        return url ?: "https://i.ytimg.com/vi/$vid/hqdefault.jpg"
    }
    private val dlLock = Any()
    private const val CHUNK = 8L * 1024 * 1024
    private const val EXT = "bin" // isi bisa WebM/Opus atau M4A/AAC; dibedakan dari magic bytes

    private val cacheDir: File by lazy { File("config/lullaby/yt-cache").also { it.mkdirs() } }

    private val initOnce: Unit by lazy { NewPipe.init(NewPipeDownloaderImpl()) }
    private fun ensureInit() { initOnce }

    // ---------------------------------------------------------------- search
    override fun search(query: String): List<NeteaseTrack> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        try {
            ensureInit()
            var items = searchWith(q, YoutubeSearchQueryHandlerFactory.MUSIC_SONGS)
            if (items.isEmpty()) items = searchWith(q, YoutubeSearchQueryHandlerFactory.VIDEOS)
            val out = ArrayList<NeteaseTrack>()
            for (it in items) {
                val id = try {
                    YoutubeStreamLinkHandlerFactory.getInstance().getId(it.url)
                } catch (_: Exception) { continue }
                if (!idRegex.matches(id)) continue
                val artist = (it.uploaderName ?: "YouTube").removeSuffix(" - Topic").trim().ifEmpty { "YouTube" }
                val durMs = if (it.duration > 0) it.duration * 1000L else 0L
                out.add(
                    NeteaseTrack(
                        MusicSources.stableId(MusicSource.YOUTUBE, id),
                        it.name,
                        listOf(artist),
                        durMs,
                        "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                        MusicSource.YOUTUBE,
                        id
                    )
                )
            }
            return out
        } catch (e: SourceAccessException) {
            throw e
        } catch (e: Exception) {
            throw SourceAccessException("Pencarian YouTube gagal: ${e.message}")
        }
    }

    private fun searchWith(q: String, filter: String): List<StreamInfoItem> {
        val ex = ServiceList.YouTube.getSearchExtractor(q, listOf(filter), "")
        ex.fetchPage()
        return ex.initialPage.items.filterIsInstance<StreamInfoItem>()
    }

    // ------------------------------------------------------------- playlist

    /** Isi playlist YouTube (lewat NewPipe). [progress] dipanggil dengan jumlah lagu sejauh ini. */
    fun playlist(listId: String, progress: (Int) -> Unit): Pair<String, List<NeteaseTrack>> {
        ensureInit()
        val ex = ServiceList.YouTube.getPlaylistExtractor("https://www.youtube.com/playlist?list=$listId")
        ex.fetchPage()
        val out = ArrayList<NeteaseTrack>()
        var page = ex.initialPage
        while (true) {
            for (item in page.items.filterIsInstance<StreamInfoItem>()) toTrack(item)?.let { out += it }
            progress(out.size)
            val next = page.nextPage ?: break
            if (out.size >= 2000) break
            page = ex.getPage(next)
        }
        return (ex.name ?: "YouTube playlist") to out
    }

    private fun toTrack(item: StreamInfoItem): NeteaseTrack? {
        val id = try { YoutubeStreamLinkHandlerFactory.getInstance().getId(item.url) } catch (_: Exception) { return null }
        if (!idRegex.matches(id)) return null
        val artist = (item.uploaderName ?: "YouTube").removeSuffix(" - Topic").trim().ifEmpty { "YouTube" }
        val durMs = if (item.duration > 0) item.duration * 1000L else 0L
        return NeteaseTrack(
            MusicSources.stableId(MusicSource.YOUTUBE, id),
            item.name,
            listOf(artist),
            durMs,
            "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            MusicSource.YOUTUBE,
            id
        )
    }

    // --------------------------------------------------------------- songUrl
    override fun songUrl(track: NeteaseTrack): NeteaseApi.SongUrl {
        val vid: String = track.nativeId ?: throw SourceAccessException("Track tanpa video id")
        if (!idRegex.matches(vid)) throw SourceAccessException("Video id tidak valid")
        val file = File(cacheDir, "$vid.$EXT")
        if (!file.isFile || file.length() < 4096) {
            synchronized(dlLock) {
                if (!file.isFile || file.length() < 4096) fetch(vid, file)
            }
        }
        return NeteaseApi.SongUrl("http://127.0.0.1:${server.localPort}/$vid.$EXT", false, 0f, 0f, false)
    }

    private fun fetch(vid: String, target: File) {
        try {
            ensureInit()
            val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$vid")
            // Pilih lewat MIME type (nama enum/format berbeda antar versi): audio/webm = Opus, audio/mp4 = AAC (M4A)
            fun mime(s: AudioStream) = (s.format?.mimeType ?: "").lowercase()
            val aud = info.audioStreams.filter { it.isUrl && it.deliveryMethod.name == "PROGRESSIVE_HTTP" }
            val webm = aud.filter { mime(it) == "audio/webm" }
            val opus = webm.filter { (it.format?.name ?: "").lowercase().contains("opus") }
            val m4a = aud.filter { mime(it) == "audio/mp4" }
            val stream = opus.ifEmpty { m4a.ifEmpty { webm } }
                .maxByOrNull { it.averageBitrate }
                ?: throw SourceAccessException("Tidak ada stream audio yang didukung. Tersedia: " +
                    info.audioStreams.joinToString { "${mime(it)}/${it.deliveryMethod}" }.ifEmpty { "kosong" }.take(110))
            downloadTo(stream.content, target)
            trimCache()
        } catch (e: SourceAccessException) {
            throw e
        } catch (e: Exception) {
            throw SourceAccessException("Gagal ambil audio YouTube: ${e.message}")
        }
    }

    private fun downloadTo(url: String, out: File) {
        val tmp = File(out.path + ".part")
        var pos = 0L
        var total = -1L
        tmp.outputStream().use { os ->
            while (total < 0 || pos < total) {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 30000
                c.setRequestProperty("User-Agent", NewPipeDownloaderImpl.UA)
                c.setRequestProperty("Referer", "https://www.youtube.com/")
                c.setRequestProperty("Range", "bytes=$pos-${pos + CHUNK - 1}")
                val code = c.responseCode
                if (code == 200) {
                    // server mengabaikan Range -> kirim file utuh
                    if (pos != 0L) throw SourceAccessException("YouTube mengabaikan Range")
                    c.inputStream.use { it.copyTo(os) }
                    break
                }
                if (code != 206) throw SourceAccessException("YouTube HTTP $code")
                total = c.getHeaderField("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull()
                    ?: throw SourceAccessException("Content-Range tidak ada")
                val n = c.inputStream.use { it.copyTo(os) }
                if (n <= 0) break
                pos += n
            }
        }
        if (tmp.length() < 4096) {
            tmp.delete()
            throw SourceAccessException("Download audio terlalu kecil / gagal")
        }
        out.delete()
        if (!tmp.renameTo(out)) throw SourceAccessException("Gagal menyimpan cache audio")
    }

    private fun trimCache(keep: Int = 40) {
        val files = cacheDir.listFiles { f -> f.isFile && f.name.endsWith(".$EXT") } ?: return
        if (files.size <= keep) return
        files.sortedByDescending { it.lastModified() }.drop(keep).forEach { it.delete() }
    }

    // ---------------------------------------------------------------- lyrics
    override fun lyrics(track: NeteaseTrack): Lyrics = YoutubeLyrics.find(track)

    // ------------------------------------------- server HTTP lokal (java.base)
    private val server: ServerSocket by lazy {
        val ss = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        val acceptor = Thread {
            while (!ss.isClosed) {
                try {
                    val s = ss.accept()
                    val h = Thread { serve(s) }
                    h.isDaemon = true
                    h.start()
                } catch (_: Exception) {
                }
            }
        }
        acceptor.name = "dango-yt-http"
        acceptor.isDaemon = true
        acceptor.start()
        ss
    }

    private val nameRegex = Regex("[A-Za-z0-9_-]{6,20}\\.$EXT")

    private fun serve(s: Socket) {
        try {
            s.use {
                s.soTimeout = 20000
                val input = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val reqLine = input.readLine() ?: return
                var range: String? = null
                while (true) {
                    val h = input.readLine() ?: break
                    if (h.isEmpty()) break
                    if (h.startsWith("Range:", ignoreCase = true)) range = h.substring(6).trim()
                }
                val name = reqLine.split(" ").getOrNull(1)?.removePrefix("/")?.substringBefore('?') ?: ""
                val out = s.getOutputStream()
                val file = File(cacheDir, name)
                if (!nameRegex.matches(name) || !file.isFile) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    return
                }
                val total = file.length()
                var start = 0L
                var end = total - 1
                var partial = false
                if (range != null) {
                    val m = Regex("bytes=(\\d*)-(\\d*)").find(range)
                    if (m != null) {
                        val a = m.groupValues[1]
                        val b = m.groupValues[2]
                        if (a.isNotEmpty()) {
                            start = a.toLong()
                            if (b.isNotEmpty()) end = minOf(b.toLong(), total - 1)
                        } else if (b.isNotEmpty()) {
                            start = maxOf(0L, total - b.toLong())
                        }
                        if (start > end || start >= total) {
                            out.write(
                                ("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\n" +
                                    "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray()
                            )
                            out.flush()
                            return
                        }
                        partial = true
                    }
                }
                val len = end - start + 1
                val head = StringBuilder()
                head.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                head.append("Content-Type: audio/webm\r\nAccept-Ranges: bytes\r\n")
                if (partial) head.append("Content-Range: bytes $start-$end/$total\r\n")
                head.append("Content-Length: $len\r\nConnection: close\r\n\r\n")
                out.write(head.toString().toByteArray())
                FileInputStream(file).use { fis ->
                    var skipped = 0L
                    while (skipped < start) {
                        val n = fis.skip(start - skipped)
                        if (n <= 0) break
                        skipped += n
                    }
                    val buf = ByteArray(64 * 1024)
                    var left = len
                    while (left > 0) {
                        val n = fis.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) break
                        out.write(buf, 0, n)
                        left -= n
                    }
                }
                out.flush()
            }
        } catch (_: Exception) {
        }
    }
}
