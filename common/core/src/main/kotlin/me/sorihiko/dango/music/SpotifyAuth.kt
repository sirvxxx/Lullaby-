package me.sorihiko.dango.music

import com.google.gson.JsonParser
import me.sorihiko.dango.DangoMusic
import java.net.HttpURLConnection
import java.net.URL

/**
 * Login Spotify pakai cookie browser (sp_dc). Cookie disimpan lewat menu login di aplikasi.
 * Dari cookie itu diambil token web player, yang dipakai untuk membaca playlist dan lagu atas nama akunmu.
 */
object SpotifyAuth {
    private fun file() = DangoMusic.instance.clientDir.resolve("spotify-cookie.txt").toFile()

    fun cookie(): String? = try {
        file().takeIf { it.isFile }?.readLines(Charsets.UTF_8)?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    val loggedIn: Boolean get() = cookie()?.contains("sp_dc=") == true

    /** Simpan cookie lalu cek dengan meminta token. False kalau cookie tidak valid (file dihapus lagi). */
    fun save(raw: String): Boolean {
        val c = raw.trim()
        if (!c.contains("sp_dc=")) return false
        file().parentFile?.mkdirs()
        file().writeText(c, Charsets.UTF_8)
        if (token() == null) {
            file().delete()
            return false
        }
        return true
    }

    fun logout() {
        file().delete()
    }

    /** Token Web API atas nama akun, dari cookie login. null kalau belum login atau cookie tidak berlaku. */
    fun token(): String? {
        val c = cookie() ?: return null
        val conn = URL("https://open.spotify.com/get_access_token?reason=transport&productType=web_player").openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            conn.setRequestProperty("Cookie", c)
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.setRequestProperty("Referer", "https://open.spotify.com/")
            if (conn.responseCode !in 200..299) null
            else {
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                JsonParser().parse(text).asJsonObject.get("accessToken")?.asString?.takeIf { it.isNotEmpty() }
            }
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
