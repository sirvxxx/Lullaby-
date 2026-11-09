package me.sorihiko.dango.musicui

import me.sorihiko.dango.ui.LayoutBounds

/**
 * Tata letak island yang sudah expand, dalam pecahan dari ukurannya.
 * Dipakai sama oleh gambar (DynamicIslandModule) dan klik (MusicScreen), jadi keduanya selalu cocok.
 */
internal class IslandRects(b: LayoutBounds) {
    private val bx = b.x
    private val bb = b.bottom
    private val w = b.width
    private val h = b.height

    val cover = LayoutBounds(b.x + 0.04f * w, b.y + 0.14f * h, 0.4f * h, 0.4f * h)
    val titleX = cover.right + 0.03f * w
    val titleY = cover.y + 0.11f * h
    val artistY = cover.y + 0.26f * h
    val progress = LayoutBounds(cover.x, b.y + 0.6f * h, 0.42f * w, 3f)

    private val btnD = 0.24f * h
    private val btnCy = b.y + 0.78f * h
    private fun btn(cx: Float) = LayoutBounds(cx - btnD / 2f, btnCy - btnD / 2f, btnD, btnD)
    val prev = btn(b.x + 0.12f * w)
    val play = btn(b.x + 0.22f * w)
    val next = btn(b.x + 0.32f * w)

    val volume = LayoutBounds(b.x + 0.40f * w, btnCy - 3f, 0.06f * w, 6f)
    val lyrics = LayoutBounds(b.x + 0.5f * w + 6f, b.y + 0.12f * h, 0.46f * w - 12f, 0.56f * h)
    val search = LayoutBounds(b.x + 0.52f * w, b.y + 0.8f * h, 0.44f * w, 0.14f * h)

    // panel hasil pencarian: tepat di bawah island yang sudah expand
    private val pad = 0.06f * h
    private val rowH = 0.3f * h
    private val gap = 0.03f * h

    /** kotak hasil untuk [rows] baris */
    fun resultsRect(rows: Int): LayoutBounds =
        LayoutBounds(bx, bb + 0.06f * h, w, rows * rowH + (rows - 1) * gap + 2f * pad)

    /** baris ke-[i] di dalam kotak hasil */
    fun resultRow(box: LayoutBounds, i: Int): LayoutBounds = resultRowAt(box, i.toFloat())

    /** baris pada posisi [pos] (pecahan baris, dipakai saat digulir) */
    fun resultRowAt(box: LayoutBounds, pos: Float): LayoutBounds =
        LayoutBounds(box.x + pad, box.y + pad + pos * (rowH + gap), box.width - 2f * pad, rowH)

    /** tombol + (tambah ke antrean) di kanan baris */
    fun resultPlus(row: LayoutBounds): LayoutBounds =
        LayoutBounds(row.right - row.height * 0.85f, row.y + row.height * 0.2f, row.height * 0.6f, row.height * 0.6f)
}
