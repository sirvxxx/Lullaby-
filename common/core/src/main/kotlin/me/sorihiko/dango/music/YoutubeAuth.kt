package me.sorihiko.dango.music

import me.sorihiko.dango.DangoMusic
import java.security.MessageDigest

/**
 * Login YouTube Music pakai cookie browser.
 * Cara isi: tempel satu baris cookie (header "cookie:" dari request ke music.youtube.com)
 * ke config/lullaby/youtube-cookie.txt. Cookie itu sama dengan sesi login, jangan dibagikan.
 */
object YoutubeAuth {
    private const val ORIGIN = "https://music.youtube.com"

    private fun file() = DangoMusic.instance.clientDir.resolve("youtube-cookie.txt").toFile()

    /** Satu baris cookie, atau null kalau belum diisi */
    fun cookie(): String? = try {
        file().takeIf { it.isFile }?.readLines(Charsets.UTF_8)?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    /** SAPISID (atau __Secure-3PAPISID) dari cookie: dipakai untuk menandatangani request */
    private fun sapisid(c: String): String? {
        for (name in listOf("SAPISID", "__Secure-3PAPISID")) {
            val m = Regex("(?:^|;\\s*)" + Regex.escape(name) + "=([^;]+)").find(c)
            if (m != null) return m.groupValues[1].trim()
        }
        return null
    }

    /** Simpan cookie dari menu login. False kalau cookie tidak memuat SAPISID. */
    fun save(raw: String): Boolean {
        val c = raw.trim()
        if (sapisid(c) == null) return false
        file().parentFile?.mkdirs()
        file().writeText(c, Charsets.UTF_8)
        return true
    }

    fun logout() {
        file().delete()
    }

    val loggedIn: Boolean get() = cookie()?.let { sapisid(it) } != null

    /** Header untuk request login: Cookie + Authorization SAPISIDHASH <waktu>_<sha1(waktu sapisid origin)> */
    fun authHeaders(): Map<String, String>? {
        val c = cookie() ?: return null
        val sid = sapisid(c) ?: return null
        val t = System.currentTimeMillis() / 1000
        val hash = MessageDigest.getInstance("SHA-1")
            .digest("$t $sid $ORIGIN".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return mapOf(
            "Cookie" to c,
            "Authorization" to "SAPISIDHASH ${t}_$hash",
            "X-Goog-AuthUser" to "0",
            "X-Origin" to ORIGIN,
        )
    }
}
