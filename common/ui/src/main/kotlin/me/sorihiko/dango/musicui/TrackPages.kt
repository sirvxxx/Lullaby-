package me.sorihiko.dango.musicui

import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.i18n.I18n
import me.sorihiko.dango.module.impls.music.NeteaseMusicModule
import me.sorihiko.dango.music.CoverCache
import me.sorihiko.dango.music.MusicPlayer
import me.sorihiko.dango.music.MusicScreen
import me.sorihiko.dango.music.NeteaseTrack
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.Theme
import kotlin.math.max
import kotlin.math.min

/** Home & Charts: grid lagu 5 kolom. Kartu = cover persegi + nama + artis (sesuai mockup). */
internal class TrackCardGrid(private val screen: MusicScreen) {
    private val ui = screen.ui

    /** area grid yang sedang digambar (untuk scroll mouse) */
    var area = LayoutBounds()
    private var target = 0f
    private var pos = 0f
    private var maxScroll = 0f

    fun tick() {
        pos = ui.approach(pos, target, 70f)
    }

    fun resetScroll() {
        target = 0f
        pos = 0f
    }

    fun scroll(wheel: Int) {
        target = (target + (if (wheel > 0) -120f else 120f)).coerceIn(0f, maxScroll)
    }

    fun draw(area0: LayoutBounds, title: String, sub: String, tracks: List<NeteaseTrack>, loading: Boolean, accent: Boolean) {
        // di atas dock pemutar
        val body = LayoutBounds(area0.x, area0.y, area0.width, area0.height - ui.bottomInset)
        ui.headerAnim {
            ui.text(title, body.x, body.y, 22f, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
            ui.text(if (loading) I18n.text("Loading...") else sub, body.x, body.y + 30f, 11f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
            if (accent) ui.card(LayoutBounds(body.x, body.y + 50f, 120f, 2f), Theme.ACCENT, 1f)
        }
        val top = body.y + (if (accent) 66f else 56f)
        val cols = 5
        val gap = 16f
        val cw = (body.width - gap * (cols - 1)) / cols
        val rowH = cw + 46f
        val gridArea = LayoutBounds(body.x, top, body.width, body.bottom - top)
        area = gridArea
        val rows = (tracks.size + cols - 1) / cols
        maxScroll = max(0f, rows * rowH - gridArea.height)

        if (tracks.isEmpty() && !loading) {
            ui.text(I18n.text("No songs yet"), body.x, top + 4f, 12f, Theme.TEXT_TERTIARY)
            return
        }
        ui.beginClip(gridArea)
        for ((i, t) in tracks.withIndex()) {
            val r = i / cols
            val c = i % cols
            val x = body.x + c * (cw + gap)
            val y = top + r * rowH - pos
            if (y + rowH < gridArea.y || y > gridArea.bottom) continue
            val card = LayoutBounds(x, y, cw, rowH)
            if (ui.hover(card)) ui.card(LayoutBounds(x - 4f, y - 4f, cw + 8f, rowH + 4f), Theme.CARD_HOVER, 12f)
            ui.drawCover(LayoutBounds(x, y, cw, cw), CoverCache.track(t), 8f)
            ui.text(ui.trim(t.name, cw, 12f, Theme.FONT_SEMIBOLD), x + 2f, y + cw + 8f, 12f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
            ui.text(ui.trim(t.artists.firstOrNull().orEmpty(), cw, 10f), x + 2f, y + cw + 24f, 10f, Theme.TEXT_SECONDARY)
            val list = tracks
            ui.hits += card.intersect(gridArea) to { MusicPlayer.playQueue(list, i) }
        }
        ui.endClip()
    }
}

/**
 * Now Playing (mockup): antrean di kiri (bisa di-scroll) + lima tombol bulat dan volume di bawah;
 * di kanan: cover, kotak judul, dan panel lirik (bisa di-scroll).
 */
internal class NowPlayingPage(private val screen: MusicScreen) {
    private val ui = screen.ui

    /** wilayah yang bisa di-scroll; diisi saat digambar, dipakai roda mouse di MusicScreen */
    var queueArea = LayoutBounds()
    var lyricArea = LayoutBounds()

    private var queueTarget = 0f
    private var queuePos = 0f
    private var lyricScroll = 0f      // px; lirik aktif berpusat di sini kalau 0
    private var lastIdx = -1

    fun scrollQueue(wheel: Int) {
        queueTarget += if (wheel > 0) -60f else 60f
    }

    fun scrollLyric(wheel: Int) {
        lyricScroll += if (wheel > 0) -32f else 32f
    }

    fun draw(body0: LayoutBounds) {
        val body = LayoutBounds(body0.x, body0.y, body0.width, body0.height - ui.bottomInset)
        val gap = 20f
        val leftW = body.width * 0.36f
        val left = LayoutBounds(body.x, body.y, leftW, body.height)
        val rightX = body.x + leftW + gap
        val right = LayoutBounds(rightX, body.y, body.right - rightX, body.height)
        drawQueue(left)
        drawPanel(right)
    }

    private fun drawQueue(area: LayoutBounds) {
        val list = LayoutBounds(area.x, area.y, area.width, area.height)
        queueArea = list
        val q = MusicPlayer.queue
        val cur = MusicPlayer.current
        val rowH = 46f
        val step = rowH + 4f
        val maxScroll = max(0f, q.size * step - list.height)
        queueTarget = queueTarget.coerceIn(0f, maxScroll)
        queuePos = ui.approach(queuePos, queueTarget, 60f)

        ui.beginClip(list)
        for ((i, t) in q.withIndex()) {
            val row = LayoutBounds(list.x, list.y + i * step - queuePos, list.width, rowH)
            if (row.bottom < list.y || row.y > list.bottom) continue
            val active = cur != null && t.key == cur.key
            ui.card(row, if (active) Theme.CARD_HOVER else Theme.CARD, 9f)
            ui.drawCover(LayoutBounds(row.x + 7f, row.y + 7f, rowH - 14f, rowH - 14f), CoverCache.track(t), 6f)
            val tx = row.x + rowH + 4f
            val tw = row.right - tx - 8f
            ui.text(ui.trim(t.name, tw, 12f, Theme.FONT_SEMIBOLD), tx, row.y + 8f, 12f,
                if (active) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
            ui.text(ui.trim(t.artists.firstOrNull().orEmpty(), tw, 10f), tx, row.y + 25f, 10f, Theme.TEXT_TERTIARY)
            ui.hits += row.intersect(list) to { MusicPlayer.playIndex(i) }
        }
        ui.endClip()
        if (q.isEmpty()) ui.text(I18n.text("Nothing in the queue yet"), list.x + 4f, list.y + 6f, 12f, Theme.TEXT_TERTIARY)
    }

    /** Kanan: cover di tengah, kotak judul, lalu panel lirik yang bisa di-scroll */
    private fun drawPanel(area: LayoutBounds) {
        val cur = MusicPlayer.current
        val side = min(area.width, area.height * 0.5f)
        val cover = LayoutBounds(area.x + (area.width - side) / 2f, area.y, side, side)
        ui.drawCover(cover, cur?.let { CoverCache.track(it) }, 14f)

        val titleBox = LayoutBounds(area.x, cover.bottom + 12f, area.width, 56f)
        ui.card(titleBox, Theme.CARD, 14f)
        val cx = area.x + area.width / 2f
        if (cur != null) {
            centered(ui.trim(cur.name, titleBox.width - 24f, 15f, Theme.FONT_SEMIBOLD), cx, titleBox.y + 10f, 15f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
            centered(ui.trim(cur.artists.joinToString(" / "), titleBox.width - 24f, 11f), cx, titleBox.y + 32f, 11f, Theme.TEXT_SECONDARY)
        }

        val lyricBox = LayoutBounds(area.x, titleBox.bottom + 12f, area.width, area.bottom - titleBox.bottom - 12f)
        ui.card(lyricBox, Theme.CARD, 14f)
        lyricArea = lyricBox
        if (cur == null) {
            centered(I18n.text("Nothing playing"), cx, lyricBox.y + lyricBox.height / 2f - 7f, 12f, Theme.TEXT_TERTIARY)
            return
        }
        val lyrics = Library.lyricsFor(cur)
        val lines = lyrics?.lines.orEmpty()
        if (lines.isEmpty()) {
            val msg = if (lyrics == null && !Library.lyricsReady(cur)) "Loading lyrics..." else "No lyrics"
            centered(I18n.text(msg), cx, lyricBox.y + lyricBox.height / 2f - 7f, 12f, Theme.TEXT_TERTIARY)
            return
        }
        val idx = max(0, lyrics!!.indexAt(MusicPlayer.positionMs))
        if (idx != lastIdx) {            // lagu sudah pindah baris: kembali mengikuti lirik
            lastIdx = idx
            lyricScroll = 0f
        }
        val maxScroll = lines.size * 32f
        lyricScroll = lyricScroll.coerceIn(-maxScroll, maxScroll)
        val mid = lyricBox.y + lyricBox.height / 2f
        ui.beginClip(lyricBox)
        for (j in max(0, idx - 14)..min(lines.size - 1, idx + 14)) {
            val k = j - idx
            val y = mid + k * 32f - lyricScroll
            if (y < lyricBox.y - 20f || y > lyricBox.bottom) continue
            val active = k == 0
            val size = if (active) 16f else 12f
            val font = if (active) Theme.FONT_SEMIBOLD else Theme.FONT_REGULAR
            val color = if (active) Theme.TEXT_PRIMARY else Theme.TEXT_TERTIARY
            centered(ui.trim(lines[j].text, lyricBox.width - 24f, size, font), cx, y - (if (active) 8f else 6f), size, color, font)
        }
        ui.endClip()
    }

    private fun centered(s: String, cx: Float, y: Float, size: Float, color: me.sorihiko.dango.utils.Color, font: String = Theme.FONT_REGULAR) {
        ui.text(s, cx - ui.width(s, size, font) / 2f, y, size, color, font)
    }
}
