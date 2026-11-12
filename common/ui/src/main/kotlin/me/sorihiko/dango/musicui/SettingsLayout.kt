package me.sorihiko.dango.musicui

import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.utils.Color
import kotlin.math.max

/** Judul kecil huruf kapital di atas kartu (GENERAL, SIZE, ...) */
internal class SectionLabel(private val title: String) : Widget() {
    override fun measure(width: Float): Float = 24f

    override fun render(ui: Ui) {
        ui.text(title.uppercase(), bounds.x + 4f, bounds.y + 8f, 10f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
    }
}

/** Kartu glass: baris-baris di dalamnya dipisah garis tipis */
internal class Card(private val children: List<Widget>) : Widget() {
    override fun measure(width: Float): Float {
        var h = 0f
        for (c in children) h += c.measure(width)
        return h
    }

    override fun layout(b: LayoutBounds) {
        bounds = b
        var y = b.y
        for (c in children) {
            val h = c.measure(b.width)
            c.layout(LayoutBounds(b.x, y, b.width, h))
            y += h
        }
    }

    override fun render(ui: Ui) {
        ui.ctx.drawGlass(bounds, Theme.GLASS_WINDOW.copy(radius = 14f))
        for ((i, c) in children.withIndex()) {
            c.render(ui)
            if (i < children.size - 1) {
                ui.card(LayoutBounds(bounds.x + 14f, c.bounds.bottom, bounds.width - 28f, 1f), Color(1f, 1f, 1f, 0.08f), 0f)
            }
        }
    }
}

/** Baris on/off dengan deskripsi (toggle pill) */
internal class SwitchRow(
    private val label: String,
    private val desc: String,
    private val get: () -> Boolean,
    private val set: (Boolean) -> Unit,
) : Widget() {
    override fun measure(width: Float): Float = 60f

    override fun render(ui: Ui) {
        val b = bounds
        if (ui.hover(b)) ui.card(b, Theme.CARD_HOVER, 0f)
        ui.text(label, b.x + 14f, b.y + 12f, 13f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        if (desc.isNotEmpty()) ui.text(ui.trim(desc, b.width - 96f, 11f), b.x + 14f, b.y + 32f, 11f, Theme.TEXT_TERTIARY)
        val on = get()
        val track = LayoutBounds(b.right - 58f, b.y + (b.height - 22f) / 2f, 42f, 22f)
        ui.card(track, if (on) Theme.TEXT_PRIMARY else Theme.TRACK, 11f)
        val kx = if (on) track.right - 19f else track.x + 3f
        ui.card(LayoutBounds(kx, track.y + 3f, 16f, 16f), if (on) Theme.TRACK else Theme.TEXT_PRIMARY, 8f)
        ui.hits += b to { set(!get()) }
    }
}

/** Baris dua pilihan (segmen), mis. Sky / Floating */
internal class SegmentRow(
    private val label: String,
    private val desc: String,
    private val options: List<String>,
    private val get: () -> Int,
    private val set: (Int) -> Unit,
) : Widget() {
    override fun measure(width: Float): Float = 60f

    override fun render(ui: Ui) {
        val b = bounds
        if (ui.hover(b)) ui.card(b, Theme.CARD_HOVER, 0f)
        ui.text(label, b.x + 14f, b.y + 12f, 13f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        if (desc.isNotEmpty()) ui.text(ui.trim(desc, b.width - 200f, 11f), b.x + 14f, b.y + 32f, 11f, Theme.TEXT_TERTIARY)
        if (options.isEmpty()) return
        val seg = LayoutBounds(b.right - 14f - 150f, b.y + 17f, 150f, 26f)
        ui.card(seg, Theme.CARD, 13f)
        val cur = get().coerceIn(0, options.size - 1)
        val w = seg.width / options.size
        for ((i, name) in options.withIndex()) {
            val part = LayoutBounds(seg.x + i * w + 2f, seg.y + 2f, w - 4f, seg.height - 4f)
            val on = i == cur
            if (on) ui.card(part, Theme.TEXT_PRIMARY, 11f) else ui.hits += part to { set(i) }
            val s = ui.trim(name, part.width - 8f, 11f, Theme.FONT_SEMIBOLD)
            ui.text(
                s, part.x + (part.width - ui.width(s, 11f, Theme.FONT_SEMIBOLD)) / 2f,
                part.y + part.height / 2f - 0.39f * 11f, 11f,
                if (on) Theme.TRACK else Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD,
            )
        }
    }
}

/** Baris slider: label dan nilai di atas, bar penuh di bawah. Klik bar untuk mengatur. */
internal class SliderRow(
    private val label: String,
    private val get: () -> Float,
    private val set: (Float) -> Unit,
    private val fmt: (Float) -> String,
) : Widget() {
    override fun measure(width: Float): Float = 56f

    override fun render(ui: Ui) {
        val b = bounds
        ui.text(label, b.x + 14f, b.y + 10f, 13f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        val v = get().coerceIn(0f, 1f)
        val value = fmt(v)
        ui.text(
            value, b.right - 14f - ui.width(value, 12f, Theme.FONT_SEMIBOLD), b.y + 10f, 12f,
            Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD,
        )
        val track = LayoutBounds(b.x + 14f, b.y + 36f, b.width - 28f, 4f)
        ui.card(track, Theme.TRACK, 2f)
        ui.card(LayoutBounds(track.x, track.y, max(4f, track.width * v), track.height), Theme.TEXT_PRIMARY, 2f)
        ui.card(LayoutBounds(track.x + track.width * v - 7f, track.y - 5f, 14f, 14f), Theme.TEXT_PRIMARY, 7f)
        ui.hits += LayoutBounds(track.x, track.y - 10f, track.width, track.height + 20f) to {
            set(((ui.mx - track.x) / track.width).coerceIn(0f, 1f))
        }
    }
}
