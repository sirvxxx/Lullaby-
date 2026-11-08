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
import me.sorihiko.dango.utils.Color
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
 * Now Playing: antrean di kiri (bisa di-scroll). Kanan: cover dengan halo aksen, kotak judul,
 * dan lirik yang berjalan pelan mengikuti baris aktif, dengan glow lembut di baris yang sedang dinyanyikan.
 */
internal class NowPlayingPage(private val screen: MusicScreen) {
    private val ui = screen.ui

    /** wilayah yang bisa di-scroll; diisi saat digambar, dipakai roda mouse di MusicScreen */
    var queueArea = LayoutBounds()
    var lyricArea = LayoutBounds()

    private var queueTarget = 0f
    private var queuePos = 0f
    private var lyricPos = 0f        // posisi tampilan lirik (px), mengejar baris aktif pelan-pelan
    private var lyricManual = 0f     // geseran manual dari roda mouse, meluruh kembali ke baris aktif
    private var lastIdx = -1
    private val lineH = 40f

    fun scrollQueue(wheel: Int) {
        queueTarget += if (wheel > 0) -60f else 60f
    }

    fun scrollLyric(wheel: Int) {
        lyricManual += if (wheel > 0) -40f else 40f
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

    private fun drawQueue(list: LayoutBounds) {
        queueArea = list
        val q = MusicPlayer.queue
        val cur = MusicPlayer.current
        val rowH = 48f
        val step = rowH + 6f
        val maxScroll = max(0f, q.size * step - list.height)
        queueTarget = queueTarget.coerceIn(0f, maxScroll)
        queuePos = ui.approach(queuePos, queueTarget, 60f)

        ui.beginClip(list)
        for ((i, t) in q.withIndex()) {
            val row = LayoutBounds(list.x, list.y + i * step - queuePos, list.width, rowH)
            if (row.bottom < list.y || row.y > list.bottom) continue
            val active = cur != null && t.key == cur.key
            glassCard(row, 10f)
            if (active) ui.card(LayoutBounds(row.x, row.y + 10f, 3f, rowH - 20f), RED, 1.5f)
            ui.drawCover(LayoutBounds(row.x + 8f, row.y + 6f, rowH - 12f, rowH - 12f), CoverCache.track(t), 7f)
            val tx = row.x + rowH + 6f
            val tw = row.right - tx - 10f
            ui.text(ui.trim(t.name, tw, 12f, Theme.FONT_SEMIBOLD), tx, row.y + 9f, 12f,
                if (active) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
            ui.text(ui.trim(t.artists.firstOrNull().orEmpty(), tw, 10f), tx, row.y + 26f, 10f, Theme.TEXT_TERTIARY)
            ui.hits += row.intersect(list) to { MusicPlayer.playIndex(i) }
        }
        ui.endClip()
        if (q.isEmpty()) ui.text(I18n.text("Nothing in the queue yet"), list.x + 4f, list.y + 6f, 12f, Theme.TEXT_TERTIARY)
    }

    private fun drawPanel(area: LayoutBounds) {
        val cur = MusicPlayer.current
        val side = min(area.width * 0.9f, area.height * 0.5f)
        val cover = LayoutBounds(area.x + (area.width - side) / 2f, area.y, side, side)
        ui.drawCover(cover, cur?.let { CoverCache.trackRounded(it) }, 18f)

        val titleBox = LayoutBounds(area.x, cover.bottom + 14f, area.width, 54f)
        glassCard(titleBox, 14f)
        val cx = area.x + area.width / 2f
        if (cur != null) {
            centered(ui.trim(cur.name, titleBox.width - 28f, 15f, Theme.FONT_SEMIBOLD), cx, titleBox.y + 11f, 15f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
            centered(ui.trim(cur.artists.joinToString(" / "), titleBox.width - 28f, 11f), cx, titleBox.y + 33f, 11f, Theme.TEXT_SECONDARY)
        }

        val lyricBox = LayoutBounds(area.x, titleBox.bottom + 14f, area.width, area.bottom - titleBox.bottom - 14f)
        glassCard(lyricBox, 16f)
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
        if (lastIdx == -1) lyricPos = idx * lineH
        if (idx != lastIdx) {
            lastIdx = idx
            lyricManual = 0f
        }
        val manualMax = lines.size * lineH
        lyricManual = lyricManual.coerceIn(-manualMax, manualMax)
        // jalan pelan: posisi mengejar baris aktif (atau geseran manual), lalu geseran manual meluruh
        lyricPos = ui.approach(lyricPos, idx * lineH + lyricManual, 22f)
        lyricManual *= 0.95f

        val mid = lyricBox.y + lyricBox.height / 2f
        ui.beginClip(lyricBox)
        for (j in max(0, idx - 18)..min(lines.size - 1, idx + 18)) {
            val y = mid + j * lineH - lyricPos
            if (y < lyricBox.y - 40f || y > lyricBox.bottom + 40f) continue
            val dist = kotlin.math.abs(j - idx)
            val active = dist == 0
            val size = if (active) 17f else 13f
            val font = if (active) Theme.FONT_SEMIBOLD else Theme.FONT_REGULAR
            val text = ui.trim(lines[j].text, lyricBox.width - 32f, size, font)
            val tx = lyricBox.x + lyricBox.width / 2f
            val ty = y - size / 2f
            if (active) {
                // glow lembut: salinan tipis berwarna aksen di sekitar teks, tidak menyilaukan
                for ((dx, dy) in listOf(-1.5f to 0f, 1.5f to 0f, 0f to -1.5f, 0f to 1.5f)) {
                    centered(text, tx + dx, ty + dy, size, RED.copy(a = 0.22f), font)
                }
                centered(text, tx, ty, size, Theme.TEXT_PRIMARY, font)
            } else {
                val alpha = (1f - dist * 0.22f).coerceIn(0.25f, 1f)
                centered(text, tx, ty, size, Theme.TEXT_TERTIARY.copy(a = Theme.TEXT_TERTIARY.a * alpha), font)
            }
        }
        ui.endClip()
    }

    /** kartu kaca: isi gelap transparan + kilau tipis di tepi atas */
    private fun glassCard(b: LayoutBounds, r: Float) {
        ui.card(b, Theme.CARD, r)
    }

    private fun centered(s: String, cx: Float, y: Float, size: Float, color: Color, font: String = Theme.FONT_REGULAR) {
        ui.text(s, cx - ui.width(s, size, font) / 2f, y, size, color, font)
    }
}
