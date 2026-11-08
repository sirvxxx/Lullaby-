package me.sorihiko.dango.musicui

import me.sorihiko.dango.i18n.I18n
import me.sorihiko.dango.music.CoverCache
import me.sorihiko.dango.music.MusicPlayer
import me.sorihiko.dango.music.MusicScreen
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.Theme
import kotlin.math.max

/** Halaman Queue: daftar antrean, tiap baris punya tombol naik, turun, dan hapus (×) */
internal class QueuePage(private val screen: MusicScreen) {
    private val ui = screen.ui

    /** wilayah yang bisa di-scroll; dipakai roda mouse di MusicScreen */
    var area = LayoutBounds()
    private var target = 0f
    private var pos = 0f
    private var maxScroll = 0f

    fun scroll(wheel: Int) {
        target = (target + if (wheel > 0) -60f else 60f).coerceIn(0f, maxScroll)
    }

    fun resetScroll() {
        target = 0f
        pos = 0f
    }

    fun draw(body0: LayoutBounds) {
        val body = LayoutBounds(body0.x, body0.y, body0.width, body0.height - ui.bottomInset)
        val q = MusicPlayer.queue
        val cur = MusicPlayer.current
        ui.headerAnim {
            ui.text(I18n.text("Queue"), body.x, body.y, 22f, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
            ui.text(I18n.format("%d songs in queue", q.size), body.x, body.y + 30f, 11f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
        }
        val top = body.y + 60f
        if (q.isEmpty()) {
            ui.text(I18n.text("Nothing in the queue yet"), body.x, top + 4f, 12f, Theme.TEXT_TERTIARY)
            area = LayoutBounds()
            return
        }
        val rowH = 58f
        val step = rowH + 6f
        val list = LayoutBounds(body.x, top, body.width, body.bottom - top)
        area = list
        maxScroll = max(0f, q.size * step - list.height)
        target = target.coerceIn(0f, maxScroll)
        pos = ui.approach(pos, target, 60f)

        ui.beginClip(list)
        for ((i, t) in q.withIndex()) {
            val row = LayoutBounds(list.x, list.y + i * step - pos, list.width, rowH)
            if (row.bottom < list.y || row.y > list.bottom) continue
            val active = cur != null && t.key == cur.key
            ui.card(row, if (active) Theme.CARD_HOVER else Theme.CARD, 12f)
            ui.text("${i + 1}", row.x + 14f, row.y + rowH / 2f - 7f, 12f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
            val coverSide = rowH - 16f
            ui.drawCover(LayoutBounds(row.x + 40f, row.y + 8f, coverSide, coverSide), CoverCache.track(t), 8f)
            val tx = row.x + 40f + coverSide + 12f
            val btnsW = 3 * 34f + 8f
            val tw = row.right - tx - btnsW - 12f
            ui.text(ui.trim(t.name, tw, 13f, Theme.FONT_SEMIBOLD), tx, row.y + 12f, 13f,
                if (active) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
            ui.text(ui.trim(t.artists.firstOrNull().orEmpty(), tw, 10.5f), tx, row.y + 31f, 10.5f, Theme.TEXT_TERTIARY)

            // tombol naik, turun, hapus. Didaftarkan sebelum baris, supaya klik tombol tidak memutar lagu
            val bx = row.right - btnsW
            val cy = row.y + rowH / 2f
            button(bx, cy, "↑", i > 0) { MusicPlayer.moveQueue(i, i - 1) }
            button(bx + 34f, cy, "↓", i < q.size - 1) { MusicPlayer.moveQueue(i, i + 1) }
            button(bx + 68f, cy, "×", true) { MusicPlayer.removeQueue(i) }
            ui.hits += row.intersect(list) to { MusicPlayer.playIndex(i) }
        }
        ui.endClip()
    }

    private fun button(cx: Float, cy: Float, glyph: String, enabled: Boolean, action: () -> Unit) {
        val b = LayoutBounds(cx, cy - 12f, 26f, 26f)
        ui.card(b, if (enabled && ui.hover(b)) Theme.CARD_HOVER else Theme.CARD, 13f)
        ui.icon(glyph, b.x + 13f, b.y + 13f, 13f, if (enabled) Theme.TEXT_PRIMARY else Theme.TEXT_TERTIARY)
        if (enabled) ui.hits += b to action
    }
}
