package me.sorihiko.dango.musicui

import me.sorihiko.dango.ui.LayoutBounds

/**
 * Tata letak island yang sudah expand, dalam pecahan dari ukurannya.
 * Dipakai sama oleh gambar (DynamicIslandModule) dan klik (MusicScreen), jadi keduanya selalu cocok.
 */
internal class IslandRects(b: LayoutBounds) {
    private val w = b.width
    private val h = b.height

    val cover = LayoutBounds(b.x + 0.033f * w, b.y + 0.1f * h, 0.4f * h, 0.4f * h)
    val titleX = cover.right + 0.025f * w
    val titleY = b.y + 0.12f * h
    val artistY = b.y + 0.36f * h
    val progress = LayoutBounds(cover.x, b.y + 0.7f * h, 0.44f * w, 3f)

    private val btnD = 0.28f * h
    private val btnCy = b.y + 0.84f * h
    private fun btn(cx: Float) = LayoutBounds(cx - btnD / 2f, btnCy - btnD / 2f, btnD, btnD)
    val prev = btn(b.x + 0.1f * w)
    val play = btn(b.x + 0.2f * w)
    val next = btn(b.x + 0.3f * w)

    val volume = LayoutBounds(b.x + 0.36f * w, btnCy - 3f, 0.1f * w, 6f)
    val lyrics = LayoutBounds(b.x + 0.52f * w, b.y + 0.1f * h, 0.44f * w, 0.6f * h)
    val search = LayoutBounds(b.x + 0.52f * w, b.y + 0.8f * h, 0.44f * w, 0.16f * h)
}
