package me.sorihiko.dango.musicui

import me.sorihiko.dango.ui.LayoutBounds

/**
 * Keadaan island yang dibagi antara layar (klik, Alt) dan modul Dynamic Island (gambar).
 * Posisi disimpan sebagai pecahan layar (0..1), supaya tidak tergantung skala GUI.
 */
object IslandState {
    /** layar island sedang terbuka (Alt ditekan) */
    @Volatile var open = false
    /** Alt ditekan lagi / Esc / klik di luar: minta tutup */
    @Volatile var wantClose = false
    /** waktu terakhir layar island digambar */
    @Volatile var lastFrame = 0L
    /** 0 = island asli, 1 = expand penuh */
    @Volatile var expandT = 0f

    @Volatile private var px = 0f
    @Volatile private var py = 0f
    @Volatile private var pw = 0f
    @Volatile private var ph = 0f
    @Volatile private var pKnown = false

    @Volatile private var ex = 0f
    @Volatile private var ey = 0f
    @Volatile private var ew = 0f
    @Volatile private var eh = 0f
    @Volatile private var eKnown = false

    /** dipanggil modul Dynamic Island setiap frame: posisi island asli */
    fun setPill(x: Float, y: Float, w: Float, h: Float, lw: Float, lh: Float) {
        if (lw <= 0f || lh <= 0f) return
        px = x / lw; py = y / lh; pw = w / lw; ph = h / lh; pKnown = true
    }

    /** dipanggil modul Dynamic Island setiap frame: posisi island yang sudah expand */
    fun setExpanded(x: Float, y: Float, w: Float, h: Float, lw: Float, lh: Float) {
        if (lw <= 0f || lh <= 0f) return
        ex = x / lw; ey = y / lh; ew = w / lw; eh = h / lh; eKnown = true
    }

    /** posisi island asli dalam koordinat layar GUI (null kalau belum pernah digambar) */
    fun startRect(lw: Float, lh: Float): LayoutBounds? =
        if (!pKnown) null else LayoutBounds(px * lw, py * lh, pw * lw, ph * lh)

    /** posisi island yang sudah expand dalam koordinat layar GUI */
    fun expandedRect(lw: Float, lh: Float): LayoutBounds? =
        if (!eKnown) null else LayoutBounds(ex * lw, ey * lh, ew * lw, eh * lh)
}
