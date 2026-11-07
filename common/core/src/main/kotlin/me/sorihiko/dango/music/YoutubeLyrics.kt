package me.sorihiko.dango.music

// =====================================================================
//  YoutubeLyrics - cari lirik untuk lagu YouTube
//  1. LRCLIB (lrclib.net): gratis, tanpa login, lirik bertimestamp (synced)
//  2. Sumber bawaan mod (NetEase -> QQ -> Kugou): cari judul+artis, cocokkan durasi
//  3. LRCLIB plain (tanpa timestamp): baris dibagi rata sepanjang lagu
//  Judul dicoba beberapa varian (dengan dan tanpa "(From ...)", "(feat. ...)", dll).
//  Hasil di-cache per lagu.
// =====================================================================

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.lyrics.LyricLine
import me.sorihiko.dango.lyrics.Lyrics
import me.sorihiko.dango.lyrics.LyricsProvider
import java.util.concurrent.ConcurrentHashMap

internal object YoutubeLyrics {
    private const val REFERER = "https://lrclib.net/"
    private const val API = "https://lrclib.net/api"
    private val cache = ConcurrentHashMap<String, Lyrics>()

    /** Kurung yang isinya noise: versi video, soundtrack "(From ...)", featuring, dll. */
    private val noise = Regex(
        """\s*[(\[][^)\]]*(official|lyric|video|audio|visuali[sz]er|\bhd\b|\bhq\b|4k|remaster|\bmv\b|m/v|live|\bfrom\b|\bfeat\b|\bft\b|featuring|explicit|clean\s+version|\bprod\b|deluxe|bonus|soundtrack|\bost\b|\bwith\b)[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE
    )
    private val anyBracket = Regex("""\s*[(\[][^)\]]*[)\]]""")
    private val trailingDots = Regex("""(\.{3}|…)\s*$""")
    private val bar = Regex("""\s*[|｜].*$""")

    fun find(track: NeteaseTrack): Lyrics {
        val key = track.key
        cache[key]?.let { return it }
        val result = try { search(track) } catch (e: Exception) {
            DangoLogger.warn("YouTube lyrics lookup failed: {}", e.toString()); null
        } ?: Lyrics(emptyList(), "YouTube")
        // jangan cache kegagalan yang mungkin cuma masalah jaringan
        if (result.lines.isNotEmpty()) cache[key] = result
        return result
    }

    private fun search(track: NeteaseTrack): Lyrics? {
        val artist = (track.artists.firstOrNull() ?: "").removeSuffix(" - Topic").replace(Regex("(?i)vevo$"), "").trim()
        val titles = titleVariants(track.name, artist)
        if (titles.isEmpty()) return null
        val durSec = if (track.durationMs > 0) (track.durationMs / 1000).toInt() else 0

        var plain: String? = null

        // 1. LRCLIB (tiap varian judul)
        for (title in titles) {
            try {
                val hit = lrclib(title, artist, durSec)
                val synced = hit?.let { str(it, "syncedLyrics") }
                if (!synced.isNullOrBlank()) {
                    val lines = LyricsProvider.parseLrc(synced)
                    if (lines.isNotEmpty()) return Lyrics(lines, "LRCLIB")
                }
                if (plain == null) plain = hit?.let { str(it, "plainLyrics") }
            } catch (e: Exception) { DangoLogger.warn("LRCLIB failed: {}", e.toString()) }
        }

        // 2. Sumber lirik bawaan mod (tiap varian judul, dengan dan tanpa artis di query)
        for (src in listOf(MusicSource.NETEASE, MusicSource.QQ, MusicSource.KUGOU)) {
            val api = try { MusicSources.api(src) } catch (e: Exception) { continue }
            for (title in titles) {
                val queries = listOf("$title $artist".trim(), title).distinct()
                for (q in queries) {
                    try {
                        val hit = api.search(q).firstOrNull { matches(it, title, durSec) } ?: continue
                        val l = api.lyrics(hit)
                        if (l != null && l.lines.isNotEmpty()) return l
                    } catch (e: Exception) { DangoLogger.warn("Lyrics fallback {} failed: {}", src.key, e.toString()) }
                }
            }
        }

        // 3. Plain lyrics: bagi rata
        if (!plain.isNullOrBlank()) {
            val rows = plain.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (rows.isNotEmpty()) {
                val total = if (track.durationMs > 0) track.durationMs else 180_000L
                val step = total / (rows.size + 1)
                return Lyrics(rows.mapIndexed { i, t -> LyricLine((i + 1) * step - step / 2, t) }, "LRCLIB (unsynced)")
            }
        }
        return null
    }

    // ------------------------------------------------------------ LRCLIB
    private fun lrclib(title: String, artist: String, durSec: Int): JsonObject? {
        // exact match
        val get = StringBuilder("$API/get?track_name=${SourceHttp.enc(title)}&artist_name=${SourceHttp.enc(artist)}")
        if (durSec > 0) get.append("&duration=$durSec")
        fetch(get.toString())?.let { if (it.isJsonObject) return it.asJsonObject }

        // search: judul+artis, lalu judul saja, lalu gabungan (query bebas)
        val urls = listOf(
            "$API/search?track_name=${SourceHttp.enc(title)}&artist_name=${SourceHttp.enc(artist)}",
            "$API/search?q=${SourceHttp.enc("$title $artist".trim())}",
            "$API/search?track_name=${SourceHttp.enc(title)}",
        )
        for (u in urls) {
            val arr = fetch(u)?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
            pick(arr, durSec)?.let { return it }
        }
        return null
    }

    private fun pick(arr: JsonArray, durSec: Int): JsonObject? {
        val items = arr.filter { it.isJsonObject }.map { it.asJsonObject }
        fun synced(o: JsonObject) = !str(o, "syncedLyrics").isNullOrBlank()
        fun close(o: JsonObject, tol: Double) = durSec <= 0 || o.get("duration")?.takeIf { it.isJsonPrimitive }
            ?.asDouble?.let { Math.abs(it - durSec) <= tol } == true
        return items.firstOrNull { synced(it) && close(it, 6.0) }
            ?: items.firstOrNull { close(it, 6.0) && !str(it, "plainLyrics").isNullOrBlank() }
            ?: items.firstOrNull { synced(it) }   // tanpa cek durasi, lebih baik daripada tidak ada
    }

    private fun fetch(url: String): com.google.gson.JsonElement? {
        val bytes = SourceHttp.bytes(url, REFERER, 2 * 1024 * 1024) ?: return null
        return try { JsonParser().parse(String(bytes, Charsets.UTF_8)) } catch (_: Exception) { null }
    }

    private fun str(o: JsonObject, key: String): String? =
        o.get(key)?.takeIf { it.isJsonPrimitive }?.asString

    // ----------------------------------------------------------- helpers

    /** Varian judul yang dicoba berurutan: versi bersih, lalu tanpa semua kurung. */
    internal fun titleVariants(raw: String, artist: String): List<String> {
        val cleaned = cleanTitle(raw, artist)
        val bare = cleaned.replace(anyBracket, "").trim()
        return listOf(cleaned, bare, raw.trim()).filter { it.isNotBlank() }.distinct()
    }

    internal fun cleanTitle(raw: String, artist: String): String {
        var s = noise.replace(raw, "").replace(bar, "").replace(trailingDots, "").trim()
        val dash = s.indexOf(" - ")
        if (dash > 0 && artist.isNotEmpty() && norm(s.substring(0, dash)).isNotEmpty() &&
            norm(s.substring(0, dash)).let { norm(artist).contains(it) || it.contains(norm(artist)) }
        ) {
            s = s.substring(dash + 3).trim()
        }
        return s
    }

    private fun norm(s: String) = s.filter { it.isLetterOrDigit() }.lowercase()

    private fun matches(t: NeteaseTrack, title: String, durSec: Int): Boolean {
        val a = norm(t.name); val b = norm(title)
        if (a.isEmpty() || b.isEmpty() || !(a.contains(b) || b.contains(a))) return false
        return durSec <= 0 || t.durationMs <= 0 || Math.abs(t.durationMs / 1000 - durSec) <= 6
    }
}
