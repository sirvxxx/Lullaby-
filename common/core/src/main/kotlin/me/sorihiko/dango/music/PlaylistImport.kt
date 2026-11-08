package me.sorihiko.dango.music

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.DangoMusic
import java.net.HttpURLConnection
import java.util.Base64
import java.net.URL
import kotlin.math.abs

/** Mengenali link playlist YouTube dan Spotify di dalam teks yang ditempel. */
object PlaylistLinks {
    sealed class Link {
        class YouTube(val listId: String) : Link()
        class Spotify(val id: String) : Link()
    }

    private val youtube = Regex("""(?:youtube\.com|youtu\.be|music\.youtube\.com)/[^\s]*[?&]list=([A-Za-z0-9_-]+)""")
    private val spotify = Regex("""open\.spotify\.com/(?:intl-[a-z]{2}/)?playlist/([A-Za-z0-9]+)""")

    fun parse(raw: String): Link? {
        val s = raw.trim()
        youtube.find(s)?.let { return Link.YouTube(it.groupValues[1]) }
        spotify.find(s)?.let { return Link.Spotify(it.groupValues[1]) }
        return null
    }
}

/** Playlist hasil impor, disimpan lokal (tidak dikirim ke akun mana pun). */
object ImportedPlaylists {
    class Imported(
        val id: Long,
        val name: String,
        val coverUrl: String,
        val tracks: List<NeteaseTrack>,
        /** lagu Spotify yang tidak ketemu di sumber musik mana pun */
        val missing: Int = 0,
    ) {
        fun asPlaylist() = NeteasePlaylist(id, name, tracks.size, coverUrl, owned = true)
    }

    private val lists = LinkedHashMap<Long, Imported>()
    private var loaded = false

    private fun file() = DangoMusic.instance.clientDir.resolve("imported-playlists.json").toFile()

    /** ID negatif supaya tidak bentrok dengan ID NetEase; key yang sama = ID yang sama (impor ulang menimpa) */
    fun idFor(key: String): Long = -(abs(key.hashCode().toLong()) + 1_000_000L)

    @Synchronized fun all(): List<Imported> { ensureLoaded(); return lists.values.toList() }

    @Synchronized fun get(id: Long): Imported? { ensureLoaded(); return lists[id] }

    @Synchronized fun rename(id: Long, name: String) {
        ensureLoaded()
        val p = lists[id] ?: return
        lists[id] = Imported(id, name.trim().ifEmpty { p.name }, p.coverUrl, p.tracks, p.missing)
        save()
    }

    @Synchronized fun delete(id: Long) {
        ensureLoaded()
        lists.remove(id)
        save()
    }

    @Synchronized fun put(p: Imported) {
        ensureLoaded()
        lists[p.id] = p
        save()
    }

    @Synchronized private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try {
            val f = file()
            if (!f.isFile) return
            for (e in JsonParser().parse(f.readText(Charsets.UTF_8)).asJsonArray) {
                val o = e.asJsonObject
                val tracks = o.getAsJsonArray("tracks").mapNotNull { readTrack(it.asJsonObject) }
                val p = Imported(o.get("id").asLong, o.get("name").asString, o.get("cover")?.asString ?: "", tracks, o.get("missing")?.asInt ?: 0)
                lists[p.id] = p
            }
        } catch (e: Exception) {
            DangoLogger.warn("Imported playlists could not be read: {}", e.javaClass.simpleName)
        }
    }

    private fun save() {
        val arr = JsonArray()
        for (p in lists.values) {
            val o = JsonObject()
            o.addProperty("id", p.id)
            o.addProperty("name", p.name)
            o.addProperty("cover", p.coverUrl)
            o.addProperty("missing", p.missing)
            val ts = JsonArray()
            for (t in p.tracks) ts.add(writeTrack(t))
            o.add("tracks", ts)
            arr.add(o)
        }
        try {
            file().parentFile?.mkdirs()
            file().writeText(arr.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            DangoLogger.warn("Imported playlists could not be saved: {}", e.javaClass.simpleName)
        }
    }

    private fun writeTrack(t: NeteaseTrack): JsonObject = JsonObject().apply {
        addProperty("id", t.id)
        addProperty("name", t.name)
        add("artists", JsonArray().also { a -> t.artists.forEach { a.add(JsonPrimitive(it)) } })
        addProperty("dur", t.durationMs)
        addProperty("cover", t.coverUrl)
        addProperty("source", t.source.key)
        addProperty("nativeId", t.nativeId)
        addProperty("albumId", t.albumId)
        addProperty("albumAudioId", t.albumAudioId)
    }

    private fun readTrack(o: JsonObject): NeteaseTrack? {
        val source = MusicSource.values().firstOrNull { it.key == o.get("source")?.asString } ?: return null
        val artists = o.getAsJsonArray("artists").map { it.asString }
        return NeteaseTrack(
            o.get("id").asLong,
            o.get("name").asString,
            artists,
            o.get("dur")?.asLong ?: 0L,
            o.get("cover")?.asString ?: "",
            source,
            o.get("nativeId").asString,
            o.get("nativeId").asString,
            o.get("albumId")?.asString ?: "",
        ).apply { albumAudioId = o.get("albumAudioId")?.asString ?: "" }
    }
}

/** Mengambil isi playlist dari link lalu mencocokkan lagunya ke sumber musik yang bisa diputar. */
object PlaylistImporter {
    /** urutan sumber untuk mencocokkan lagu Spotify. Tanpa login NetEase sering tidak punya audio, jadi YouTube dulu. */
    private val matchOrder: List<MusicSource>
        get() = if (NeteaseApi.loggedIn) listOf(MusicSource.NETEASE, MusicSource.YOUTUBE, MusicSource.QQ)
                // tanpa login NetEase dan QQ tidak memberi audio, jadi hanya YouTube
                else listOf(MusicSource.YOUTUBE)

    fun importLink(link: PlaylistLinks.Link, progress: (String) -> Unit): ImportedPlaylists.Imported = when (link) {
        is PlaylistLinks.Link.YouTube -> importYouTube(link.listId, progress)
        is PlaylistLinks.Link.Spotify -> importSpotify(link.id, progress)
    }

    private fun importYouTube(listId: String, progress: (String) -> Unit): ImportedPlaylists.Imported {
        val (name, tracks) = YoutubeMusicSource.playlist(listId) { n -> progress("Loading playlist… $n songs") }
        if (tracks.isEmpty()) throw SourceAccessException("Playlist YouTube kosong atau tidak bisa dibuka (pastikan publik)")
        return ImportedPlaylists.Imported(
            ImportedPlaylists.idFor("yt:$listId"), name, tracks.first().coverUrl, tracks
        )
    }

    /** Satu baris lagu dari Spotify: judul, artis, durasi */
    private class SpotifyRow(val title: String, val artist: String, val durMs: Long)

    private fun importSpotify(id: String, progress: (String) -> Unit): ImportedPlaylists.Imported {
        progress("Reading Spotify playlist…")
        // Utamakan Web API: bisa membaca semua lagu (halaman embed hanya 100 lagu pertama)
        val (name, rows) = spotifyApiRows(id, progress) ?: run {
            DangoLogger.warn("Spotify: Web API failed, falling back to embed page (max 100 songs)")
            embedRows(id)
        }
        if (rows.isEmpty()) throw SourceAccessException("Tidak ada lagu di playlist Spotify ini")

        val out = ArrayList<NeteaseTrack>()
        var missing = 0
        val total = rows.size
        for ((i, r) in rows.withIndex()) {
            progress("Matching ${i + 1}/$total · ${r.title}")
            val hit = matchTrack(r.title, r.artist, r.durMs)
            if (hit != null) out += hit else missing++
        }
        if (out.isEmpty()) throw SourceAccessException("Tidak ada lagu Spotify yang ketemu di sumber musik")
        return ImportedPlaylists.Imported(
            ImportedPlaylists.idFor("sp:$id"), name, out.first().coverUrl, out, missing
        )
    }

    /** Token anonim dari web player Spotify, lalu baca semua halaman lagu lewat Web API. null = gagal. */
    private fun spotifyApiRows(id: String, progress: (String) -> Unit): Pair<String, List<SpotifyRow>>? {
        for ((label, token) in listOfNotNull(
            clientCredentialsToken()?.let { "client credentials" to it },
            webPlayerToken()?.let { "web player token" to it },
        )) {
            DangoLogger.info("Spotify: trying {}", label)
            readAllRows(id, token, progress)?.let { return it }
        }
        return null
    }

    private fun readAllRows(id: String, token: String, progress: (String) -> Unit): Pair<String, List<SpotifyRow>>? {
        val meta = getJson("https://api.spotify.com/v1/playlists/$id?fields=name", token) ?: return null
        val name = meta.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: "Spotify playlist"

        val rows = ArrayList<SpotifyRow>()
        var offset = 0
        while (offset < 5000) {
            // endpoint /tracks; kalau sudah diganti Spotify, coba /items
            val page = getJson("https://api.spotify.com/v1/playlists/$id/tracks?limit=100&offset=$offset", token)
                ?: getJson("https://api.spotify.com/v1/playlists/$id/items?limit=100&offset=$offset", token) ?: break
            val items = page.get("items")?.takeIf { it.isJsonArray }?.asJsonArray ?: break
            for (el in items) {
                val t = (el.asJsonObject.get("track") ?: el.asJsonObject.get("item"))
                    ?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val title = str(t, "name")
                if (title.isEmpty()) continue
                val artist = t.get("artists")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.mapNotNull { a -> a.asJsonObject.get("name")?.asString }?.joinToString(", ") ?: ""
                val dur = t.get("duration_ms")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
                rows += SpotifyRow(title, artist, dur)
            }
            progress("Reading Spotify playlist… ${rows.size} songs")
            val hasNext = page.get("next")?.let { !it.isJsonNull } == true
            if (!hasNext || items.size() == 0) break
            offset += 100
        }
        return if (rows.isEmpty()) null else name to rows
    }

    /**
     * Token untuk Web API. Utamakan Client Credentials (config/lullaby/spotify.txt: baris 1 = Client ID,
     * baris 2 = Client Secret). Kalau file tidak ada, pakai token anonim dari web player (tidak resmi).
     */
    private fun webPlayerToken(): String? =
        getJson("https://open.spotify.com/get_access_token?reason=transport&productType=web_player", null)
            ?.get("accessToken")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotEmpty() }

    private fun clientCredentialsToken(): String? {
        val f = DangoMusic.instance.clientDir.resolve("spotify.txt").toFile()
        if (!f.isFile) return null
        val lines = f.readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2) return null
        val auth = Base64.getEncoder().encodeToString("${lines[0]}:${lines[1]}".toByteArray(Charsets.UTF_8))
        val conn = URL("https://accounts.spotify.com/api/token").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            conn.setRequestProperty("Authorization", "Basic $auth")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write("grant_type=client_credentials".toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) {
                DangoLogger.warn("Spotify client credentials failed: HTTP {}", conn.responseCode)
                return null
            }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            JsonParser().parse(text).asJsonObject.get("access_token")?.asString?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            DangoLogger.warn("Spotify client credentials error: {}", e.javaClass.simpleName)
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Cadangan: halaman embed publik, hanya memuat sekitar 100 lagu pertama */
    private fun embedRows(id: String): Pair<String, List<SpotifyRow>> {
        val html = SourceHttp.bytes("https://open.spotify.com/embed/playlist/$id", "https://open.spotify.com/", 8 * 1024 * 1024)
            ?.let { String(it, Charsets.UTF_8) }
            ?: throw SourceAccessException("Tidak bisa membuka playlist Spotify (pastikan playlist-nya publik)")
        val json = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)
            ?: throw SourceAccessException("Format halaman Spotify berubah, belum bisa dibaca")
        val root = JsonParser().parse(json)
        val name = findElement(root, "entity")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: "Spotify playlist"
        val list = findElement(root, "trackList")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: throw SourceAccessException("Tidak ada daftar lagu di halaman Spotify")
        val rows = list.filter { it.isJsonObject }.map { el ->
            val o = el.asJsonObject
            SpotifyRow(
                str(o, "title"),
                str(o, "subtitle"),
                o.get("duration")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
            )
        }.filter { it.title.isNotEmpty() }
        return name to rows
    }

    /** GET JSON; [bearer] = null untuk permintaan tanpa login. Gagal = null, tidak melempar error. */
    private fun getJson(url: String, bearer: String?): JsonObject? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.setRequestProperty("Accept", "application/json")
            if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
            if (conn.responseCode !in 200..299) {
                DangoLogger.warn("Spotify {} -> HTTP {}", url.substringBefore('?'), conn.responseCode)
                return null
            }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            JsonParser().parse(text).takeIf { it.isJsonObject }?.asJsonObject
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun matchTrack(title: String, artist: String, durMs: Long): NeteaseTrack? {
        val wantName = norm(title)
        if (wantName.isEmpty()) return null
        for (src in matchOrder) {
            val api = try { MusicSources.api(src) } catch (_: Exception) { continue }
            val hit = try {
                api.search("$title $artist".trim()).take(8).firstOrNull { sameSong(it, wantName, durMs) }
            } catch (_: Exception) { null }
            if (hit != null) return hit
        }
        return null
    }

    /** Versi tiruan / karaoke / instrumental: jangan dipilih kalau judul asli tidak memintanya */
    private val fakeMarkers = listOf("karaoke", "instrumental", "tribute", "inthestyleof", "madefamousby")

    private fun sameSong(t: NeteaseTrack, wantName: String, durMs: Long): Boolean {
        val a = norm(t.name)
        if (a.isEmpty() || !(a.contains(wantName) || wantName.contains(a))) return false
        val artistText = norm(t.artists.joinToString(" "))
        if (fakeMarkers.any { m -> (a.contains(m) || artistText.contains(m)) && !wantName.contains(m) }) return false
        return durMs <= 0 || t.durationMs <= 0 || abs(t.durationMs - durMs) <= 4000
    }

    private fun norm(s: String) = s.filter { it.isLetterOrDigit() }.lowercase()

    private fun str(o: JsonObject, key: String): String =
        o.get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: ""

    /** Cari elemen dengan nama [key] di mana saja dalam JSON (struktur halaman Spotify bisa berubah) */
    private fun findElement(e: JsonElement, key: String): JsonElement? {
        if (e.isJsonObject) {
            val o = e.asJsonObject
            o.get(key)?.let { return it }
            for (v in o.entrySet().map { it.value }) findElement(v, key)?.let { return it }
        } else if (e.isJsonArray) {
            for (v in e.asJsonArray) findElement(v, key)?.let { return it }
        }
        return null
    }
}
