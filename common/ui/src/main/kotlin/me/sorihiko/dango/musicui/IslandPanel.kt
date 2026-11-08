package me.sorihiko.dango.musicui

import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.i18n.I18n
import me.sorihiko.dango.module.impls.music.NeteaseMusicModule
import me.sorihiko.dango.music.CoverCache
import me.sorihiko.dango.music.MusicPlayer
import me.sorihiko.dango.music.MusicScreen
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.utils.Color
import kotlin.math.max

/** Status island yang sedang di-expand (dibuka dengan menahan Alt) */
object IslandState {
    @Volatile var open = false
    /** Alt ditekan lagi / Esc / klik di luar: minta tutup */
    @Volatile var wantClose = false
    /** waktu terakhir layar island digambar (dipakai untuk tahu layarnya masih hidup) */
    @Volatile var lastFrame = 0L
}

/**
 * Panel island yang di-expand, sesuai sketsa: kiri = cover, judul, progres, prev / play / next, volume;
 * kanan = lirik dan search bar. Klik search bar membuka GUI penuh dengan kotak search aktif.
 */
internal class IslandPanel(private val screen: MusicScreen) {
    private val ui = screen.ui
    private val w = 480f
    private val h = 160f

    /**
     * t = 0 (pill kecil di atas) .. 1 (panel penuh). Kaca melebar dari ukuran pill ke ukuran panel,
     * isi panel memudar masuk setelah kaca hampir terbuka, dan di-clip ke kaca supaya tidak keluar saat transisi.
     */
    fun draw(lw: Float, t: Float, onPillClick: () -> Unit) {
        val e = 1f - (1f - t.coerceIn(0f, 1f)).let { it * it * it }   // ease-out
        val full = LayoutBounds((lw - w) / 2f, 18f, w, h)
        val pillW = 270f
        val pillH = 76f
        val pill = LayoutBounds((lw - pillW) / 2f, 18f, pillW, pillH)
        val rect = LayoutBounds(
            lerp(pill.x, full.x, e), lerp(pill.y, full.y, e),
            lerp(pill.width, full.width, e), lerp(pill.height, full.height, e),
        )
        ui.ctx.drawGlass(rect, Theme.GLASS_WINDOW.copy(radius = lerp(pillH / 2f, 26f, e)))
        // pill (belum expand): klik untuk expand
        if (t < 0.35f) ui.hits += rect to onPillClick
        if (t < 0.6f) {
            ui.beginClip(rect)
            ui.ctx.pushOpacity(1f - t / 0.6f)
            drawCompact(pill)
            ui.ctx.popOpacity()
            ui.endClip()
        }

        val alpha = ((t - 0.35f) / 0.65f).coerceIn(0f, 1f)
        if (alpha <= 0f) return
        ui.beginClip(rect)
        ui.ctx.pushOpacity(alpha)
        val midX = full.x + w * 0.5f
        ui.card(LayoutBounds(midX, full.y + 16f, 1f, h - 32f), Color(1f, 1f, 1f, 0.12f), 0.5f)
        drawLeft(LayoutBounds(full.x, full.y, w * 0.5f, h))
        drawRight(LayoutBounds(midX, full.y, w * 0.5f, h))
        ui.ctx.popOpacity()
        ui.endClip()
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** Tampilan pill saat belum expand: jam di kiri, cover, judul, dan batang audio */
    private fun drawCompact(p: LayoutBounds) {
        val t = MusicPlayer.current ?: return
        val playing = MusicPlayer.state == MusicPlayer.State.PLAYING
        val clock = java.time.LocalTime.now().let { "%02d:%02d".format(it.hour, it.minute) }
        val cw = ui.width(clock, 12f, Theme.FONT_SEMIBOLD) + 12f
        ui.text(clock, p.x + 14f, p.y + (p.height - 14f) / 2f, 12f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        val cover = LayoutBounds(p.x + 14f + cw, p.y + 10f, 56f, 56f)
        ui.drawCover(cover, CoverCache.trackRounded(t, 0.5f), 28f)
        val tx = cover.right + 12f
        val tw = p.right - 60f - tx
        ui.text(ui.trim(t.name, tw, 13f, Theme.FONT_SEMIBOLD), tx, p.y + 26f, 13f,
            if (playing) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
        ui.text(ui.trim(t.artists.firstOrNull().orEmpty(), tw, 10f), tx, p.y + 44f, 10f, Theme.TEXT_TERTIARY)
        val barsX = p.right - 34f
        for (i in 0 until 4) {
            val hh = if (playing) 6f + 10f * kotlin.math.abs(kotlin.math.sin(System.nanoTime() / 1e9 * (4 + i) + i)).toFloat() else 4f
            ui.card(LayoutBounds(barsX + i * 6f, p.y + 26f - hh / 2f + 6f, 3f, hh), Theme.TEXT_PRIMARY, 1.5f)
        }
    }

    private fun drawLeft(area: LayoutBounds) {
        val t = MusicPlayer.current
        val x = area.x + 16f
        ui.drawCover(LayoutBounds(x, area.y + 16f, 64f, 64f), t?.let { CoverCache.trackRounded(it) }, 12f)
        val tw = area.width - 16f - 80f - 16f
        if (t != null) {
            ui.text(ui.trim(t.name, tw, 14f, Theme.FONT_SEMIBOLD), x + 80f, area.y + 22f, 14f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
            ui.text(ui.trim(t.artists.joinToString(" / "), tw, 11f), x + 80f, area.y + 44f, 11f, Theme.TEXT_SECONDARY)
        }

        // progres (tampilan saja)
        val bar = LayoutBounds(x, area.y + 96f, area.width - 32f, 3f)
        ui.card(bar, Theme.TRACK, 1.5f)
        val dur = MusicPlayer.durationMs
        if (dur > 0) {
            val f = (MusicPlayer.positionMs.toDouble() / dur).coerceIn(0.0, 1.0).toFloat()
            ui.card(LayoutBounds(bar.x, bar.y, max(3f, bar.width * f), bar.height), Theme.TEXT_PRIMARY, 1.5f)
        }

        // tombol: prev, play/pause, next. Volume di sebelah kanannya
        val cy = area.y + 128f
        val cx0 = x + 22f
        circle(cx0, cy, 34f) { MusicPlayer.prev() }
        ui.icon("◀", cx0, cy, 13f, Theme.TEXT_SECONDARY)
        circle(cx0 + 46f, cy, 40f) { MusicPlayer.togglePause() }
        playPauseGlyph(cx0 + 46f, cy)
        circle(cx0 + 92f, cy, 34f) { MusicPlayer.next() }
        ui.icon("▶", cx0 + 92f, cy, 13f, Theme.TEXT_SECONDARY)
        drawVolume(LayoutBounds(cx0 + 124f, cy - 6f, area.width - (cx0 + 124f) - 16f, 12f))
    }

    private fun drawRight(area: LayoutBounds) {
        val t = MusicPlayer.current
        val lyrics = t?.let { Library.lyricsFor(it) }
        val lines = lyrics?.lines.orEmpty()
        val cx = area.x + area.width / 2f
        val lw = area.width - 36f
        val mid = area.y + (area.height - 44f) / 2f
        if (lyrics == null || lines.isEmpty()) {
            val msg = when {
                t == null -> ""
                lyrics == null && !Library.lyricsReady(t) -> "Loading lyrics..."
                else -> "No lyrics"
            }
            centered(msg, cx, mid - 6f, 12f, Theme.TEXT_TERTIARY)
        } else {
            val idx = max(0, lyrics.indexAt(MusicPlayer.positionMs))
            for (k in -1..1) {
                val j = idx + k
                if (j < 0 || j >= lines.size) continue
                val active = k == 0
                val size = if (active) 15f else 12f
                val font = if (active) Theme.FONT_SEMIBOLD else Theme.FONT_REGULAR
                val color = if (active) Theme.TEXT_PRIMARY else Theme.TEXT_TERTIARY
                centered(ui.trim(lines[j].text, lw, size, font), cx, mid + k * 26f - 8f, size, color, font)
            }
        }

        // search bar: klik untuk masuk ke GUI penuh dengan kotak search aktif
        val sb = LayoutBounds(area.x + 18f, area.bottom - 44f, lw, 30f)
        ui.card(sb, Theme.CARD, 15f)
        ui.text(I18n.text("Search songs..."), sb.x + 14f, sb.y + 8f, 12f, Theme.TEXT_TERTIARY, Theme.FONT_REGULAR)
        ui.hits += sb to { screen.openSearchFromIsland() }
    }

    private fun circle(cx: Float, cy: Float, d: Float, action: () -> Unit) {
        val b = LayoutBounds(cx - d / 2f, cy - d / 2f, d, d)
        ui.card(b, Theme.CARD, d / 2f)
        ui.hits += b to action
    }

    private fun playPauseGlyph(cx: Float, cy: Float) {
        if (MusicPlayer.state == MusicPlayer.State.PLAYING) {
            ui.card(LayoutBounds(cx - 6f, cy - 7f, 4f, 14f), Theme.TEXT_PRIMARY, 1.5f)
            ui.card(LayoutBounds(cx + 2f, cy - 7f, 4f, 14f), Theme.TEXT_PRIMARY, 1.5f)
        } else {
            ui.icon("▶", cx + 2f, cy, 16f, Theme.TEXT_PRIMARY)
        }
    }

    private fun drawVolume(r: LayoutBounds) {
        val track = LayoutBounds(r.x, r.y + 4f, r.width, 4f)
        ui.card(track, Theme.CARD, 2f)
        val v = MusicPlayer.volume.coerceIn(0f, 1f)
        ui.card(LayoutBounds(track.x, track.y, max(4f, track.width * v), track.height), Theme.TEXT_PRIMARY, 2f)
        ui.card(LayoutBounds(track.x + track.width * v - 5f, track.y - 4f, 10f, 10f), Theme.TEXT_PRIMARY, 5f)
        ui.hits += r.expand(0f, 8f) to { setVolume(((ui.mx - r.x) / r.width).coerceIn(0f, 1f)) }
    }

    private fun setVolume(v: Float) {
        DangoMusic.instance.moduleManager.get(NeteaseMusicModule::class.java).setVolume(v)
        MusicPlayer.volume = v
    }

    private fun centered(s: String, cx: Float, y: Float, size: Float, color: Color, font: String = Theme.FONT_REGULAR) {
        if (s.isEmpty()) return
        ui.text(s, cx - ui.width(s, size, font) / 2f, y, size, color, font)
    }
}
