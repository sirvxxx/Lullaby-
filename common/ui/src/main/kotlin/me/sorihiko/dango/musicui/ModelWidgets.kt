package me.sorihiko.dango.musicui

import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.Theme
import kotlin.math.max

/**
 * Model widget (Tahap 1). Setiap widget punya tiga langkah:
 * measure = tinggi yang dibutuhkan, layout = tempat, render = gambar dan daftarkan klik.
 * Gayanya tetap Liquid Glass Lullaby (Theme + drawGlass), tidak mengambil gaya lain.
 */
internal abstract class Widget {
    var bounds = LayoutBounds()

    /** tinggi yang dibutuhkan pada lebar [width] */
    abstract fun measure(width: Float): Float

    /** tempatkan widget di [b] (dipanggil sebelum render) */
    open fun layout(b: LayoutBounds) {
        bounds = b
    }

    abstract fun render(ui: Ui)
}

internal const val ROW_H = 30f

/** Kartu glass dengan judul dan anak-anak di dalamnya (vertikal) */
internal class Section(private val title: String, private val children: List<Widget>) : Widget() {
    override fun measure(width: Float): Float {
        val inner = width - SECTION_PAD * 2f
        var h = SECTION_HEADER
        for (c in children) h += c.measure(inner) + SECTION_GAP
        return h + SECTION_PAD
    }

    override fun layout(b: LayoutBounds) {
        bounds = b
        val inner = b.width - SECTION_PAD * 2f
        var y = b.y + SECTION_HEADER
        for (c in children) {
            val h = c.measure(inner)
            c.layout(LayoutBounds(b.x + SECTION_PAD, y, inner, h))
            y += h + SECTION_GAP
        }
    }

    override fun render(ui: Ui) {
        ui.ctx.drawGlass(bounds, Theme.GLASS_WINDOW.copy(radius = 16f))
        ui.text(title.uppercase(), bounds.x + SECTION_PAD, bounds.y + 10f, 10f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
        for (c in children) c.render(ui)
    }

    companion object {
        const val SECTION_PAD = 12f
        const val SECTION_HEADER = 28f
        const val SECTION_GAP = 6f
    }
}

/** Halaman bertab: deretan tab di atas, isi tab yang dipilih di bawahnya */
internal class TabbedPanel(
    private val tabs: List<String>,
    private val selected: () -> Int,
    private val select: (Int) -> Unit,
    private val pages: List<List<Widget>>,
) : Widget() {
    private val tabH = 30f

    override fun measure(width: Float): Float {
        var h = tabH + 8f
        for (c in current()) h += c.measure(width) + 8f
        return h
    }

    override fun layout(b: LayoutBounds) {
        bounds = b
        var y = b.y + tabH + 8f
        for (c in current()) {
            val h = c.measure(b.width)
            c.layout(LayoutBounds(b.x, y, b.width, h))
            y += h + 8f
        }
    }

    override fun render(ui: Ui) {
        if (tabs.isEmpty()) return
        val tw = bounds.width / tabs.size
        for ((i, name) in tabs.withIndex()) {
            val tb = LayoutBounds(bounds.x + i * tw, bounds.y, tw - 4f, tabH)
            ui.pill(tb, if (i == selected()) Theme.CARD_HOVER else Theme.CARD, name, null) { select(i) }
        }
        for (c in current()) c.render(ui)
    }

    private fun current(): List<Widget> = pages.getOrNull(selected()) ?: emptyList()
}

/** Baris on/off */
internal class ToggleRow(
    private val label: String,
    private val get: () -> Boolean,
    private val set: (Boolean) -> Unit,
) : Widget() {
    override fun measure(width: Float): Float = ROW_H

    override fun render(ui: Ui) {
        val b = bounds
        if (ui.hover(b)) ui.card(b, Theme.CARD_HOVER, 10f)
        val on = get()
        ui.text(label, b.x + 8f, b.y + ROW_H / 2f - 0.39f * 12f, 12f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        val track = LayoutBounds(b.right - 46f, b.y + (ROW_H - 18f) / 2f, 36f, 18f)
        ui.card(track, if (on) Theme.TEXT_PRIMARY else Theme.TRACK, 9f)
        val knobX = if (on) track.right - 16f else track.x + 2f
        ui.card(LayoutBounds(knobX, track.y + 2f, 14f, 14f), if (on) Theme.TRACK else Theme.TEXT_PRIMARY, 7f)
        ui.hits += b to { set(!get()) }
    }
}

/** Baris pilihan ◀ nilai ▶ */
internal class ChoiceRow(
    private val label: String,
    private val options: List<String>,
    private val get: () -> Int,
    private val set: (Int) -> Unit,
) : Widget() {
    override fun measure(width: Float): Float = ROW_H

    override fun render(ui: Ui) {
        val b = bounds
        if (ui.hover(b)) ui.card(b, Theme.CARD_HOVER, 10f)
        ui.text(label, b.x + 8f, b.y + ROW_H / 2f - 0.39f * 12f, 12f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        if (options.isEmpty()) return
        val cur = get().coerceIn(0, options.size - 1)
        val prev = LayoutBounds(b.right - 150f, b.y + 4f, 22f, 22f)
        val next = LayoutBounds(b.right - 26f, b.y + 4f, 22f, 22f)
        val value = ui.trim(options[cur], 90f, 11f, Theme.FONT_SEMIBOLD)
        ui.text(value, b.right - 118f, b.y + ROW_H / 2f - 0.39f * 11f, 11f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
        ui.pill(prev, Theme.CARD, "◀", null) { set((cur - 1 + options.size) % options.size) }
        ui.pill(next, Theme.CARD, "▶", null) { set((cur + 1) % options.size) }
    }
}

/** Tombol satu baris */
internal class ButtonRow(private val label: String, private val action: () -> Unit) : Widget() {
    override fun measure(width: Float): Float = ROW_H + 4f

    override fun render(ui: Ui) {
        ui.pill(bounds, Theme.CARD, label, null, action)
    }
}

/** Tumpukan vertikal beberapa widget (dipakai untuk beberapa section sekaligus) */
internal class Stack(private val children: List<Widget>, private val gap: Float = 10f) : Widget() {
    override fun measure(width: Float): Float {
        var h = 0f
        for (c in children) h += c.measure(width) + gap
        return h
    }

    override fun layout(b: LayoutBounds) {
        bounds = b
        var y = b.y
        for (c in children) {
            val h = c.measure(b.width)
            c.layout(LayoutBounds(b.x, y, b.width, h))
            y += h + gap
        }
    }

    override fun render(ui: Ui) {
        for (c in children) c.render(ui)
    }
}
