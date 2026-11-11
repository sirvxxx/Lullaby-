package me.sorihiko.dango.musicui

import me.sorihiko.dango.i18n.I18n
import me.sorihiko.dango.module.values.IntValue
import me.sorihiko.dango.platform.Keys
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.Theme

/**
 * Perekaman tombol (B1). Aturannya sama dengan SettingsPage.keyTyped:
 * tombol berikutnya dipakai, Esc membatalkan, Backspace / Delete mengosongkan (kalau boleh).
 */
internal object KeyCapture {
    @Volatile var target: IntValue? = null

    /** true kalau tombol ini sudah dipakai untuk perekaman (jangan diteruskan ke tempat lain) */
    fun keyTyped(keyCode: Int): Boolean {
        val v = target ?: return false
        when {
            keyCode == Keys.ESCAPE -> Unit
            (keyCode == Keys.BACK || keyCode == Keys.DELETE) && v.minRange <= 0 -> v.setValue(0)
            keyCode != 0 -> v.setValue(keyCode)
        }
        target = null
        return true
    }
}

/** Baris keybind: tombol nilai (klik untuk merekam) dan Reset */
internal class KeyRow(
    private val label: String,
    private val v: IntValue,
    private val reset: (IntValue) -> Unit,
) : Widget() {
    override fun measure(width: Float): Float = ROW_H

    override fun render(ui: Ui) {
        val b = bounds
        ui.text(label, b.x + 8f, b.y + ROW_H / 2f - 0.39f * 12f, 12f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        val binding = KeyCapture.target === v
        val kb = LayoutBounds(b.right - 150f, b.y + 3f, 90f, 24f)
        ui.card(kb, if (binding) RED.copy(a = 0.22f) else if (ui.hover(kb)) Theme.CARD_HOVER else Theme.CARD, 7f)
        val name = when {
            binding -> I18n.text("Press a key")
            v.value == 0 -> I18n.text("None")
            else -> Keys.name(v.value) ?: "#${v.value}"
        }
        val s = ui.trim(name, kb.width - 10f, 11f, Theme.FONT_SEMIBOLD)
        ui.text(
            s, kb.x + (kb.width - ui.width(s, 11f, Theme.FONT_SEMIBOLD)) / 2f,
            kb.y + kb.height / 2f - 0.39f * 11f, 11f,
            if (binding) RED else if (v.value == 0) Theme.TEXT_TERTIARY else Theme.TEXT_PRIMARY,
            Theme.FONT_SEMIBOLD,
        )
        ui.hits += kb to { KeyCapture.target = if (KeyCapture.target === v) null else v }
        ui.pill(LayoutBounds(kb.right + 6f, b.y + 3f, 52f, 24f), Theme.CARD, I18n.text("Reset"), null) {
            KeyCapture.target = null
            reset(v)
        }
    }
}
