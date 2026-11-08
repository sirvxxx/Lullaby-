package me.sorihiko.dango.music

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.DangoMusic

/** Playlist yang disembunyikan. Tersimpan di config/lullaby/hidden-playlists.json, bisa dikembalikan. */
object HiddenPlaylists {
    private val ids = HashSet<Long>()
    private var loaded = false

    private fun file() = DangoMusic.instance.clientDir.resolve("hidden-playlists.json").toFile()

    @Synchronized private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try {
            val f = file()
            if (f.isFile) JsonParser().parse(f.readText(Charsets.UTF_8)).asJsonArray.forEach { ids += it.asLong }
        } catch (e: Exception) {
            DangoLogger.warn("Hidden playlists could not be read: {}", e.javaClass.simpleName)
        }
    }

    @Synchronized fun isHidden(id: Long): Boolean {
        ensureLoaded()
        return id in ids
    }

    @Synchronized fun toggle(id: Long) {
        ensureLoaded()
        if (!ids.remove(id)) ids += id
        try {
            val arr = JsonArray()
            ids.forEach { arr.add(JsonPrimitive(it)) }
            file().parentFile?.mkdirs()
            file().writeText(arr.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            DangoLogger.warn("Hidden playlists could not be saved: {}", e.javaClass.simpleName)
        }
    }
}
