package me.sorihiko.dango.musicui

import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.FloatValue
import me.sorihiko.dango.module.values.IntValue
import kotlin.math.roundToInt

/**
 * Jembatan Tahap 2: membuat widget dari nilai modul yang sudah ada.
 * Nilai tetap disimpan lewat setter yang sama (set / setValue), jadi ConfigManager tidak berubah.
 */
internal object ValueWidgets {
    fun toggle(label: String, v: BooleanValue): Widget =
        ToggleRow(label, { v.get() }, { v.set(it) })

    fun intSlider(label: String, v: IntValue, fmt: (Int) -> String): Widget = SliderRow(
        label,
        { v.normalize() },
        { x -> v.setValue(v.minRange + ((v.maxRange - v.minRange) * x).roundToInt()) },
        { x -> fmt(v.minRange + ((v.maxRange - v.minRange) * x).roundToInt()) },
    )

    fun floatSlider(label: String, v: FloatValue, fmt: (Float) -> String): Widget = SliderRow(
        label,
        { v.normalize() },
        { x -> v.setValue(v.minRange + (v.maxRange - v.minRange) * x) },
        { x -> fmt(v.minRange + (v.maxRange - v.minRange) * x) },
    )

    fun choice(label: String, options: List<String>, get: () -> Int, set: (Int) -> Unit): Widget =
        ChoiceRow(label, options, get, set)
}
