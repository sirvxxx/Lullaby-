package me.sorihiko.dango.musicui

import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.module.impls.music.LyricsModule
import me.sorihiko.dango.module.impls.music.NeteaseMusicModule
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.FloatValue
import me.sorihiko.dango.module.values.IntValue
import me.sorihiko.dango.module.values.ModuleValue
import me.sorihiko.dango.music.MusicScreen
import me.sorihiko.dango.ui.LayoutBounds
import kotlin.math.max

/**
 * Langkah A: tab Lyrics dengan model widget.
 * Hanya menampilkan toggle dan slider. Warna, font, tombol, dan bahasa masih di SettingsPage (Langkah B).
 * Dimatikan secara default lewat [WIDGET_LYRICS_TAB] supaya halaman lama tetap berjalan.
 */
internal const val WIDGET_LYRICS_TAB = false

internal class LyricsWidgetPage(private val screen: MusicScreen) {
    private val ui = screen.ui
    private var root: Widget? = null

    /** area yang terlihat (dipakai roda mouse di MusicScreen) */
    var area = LayoutBounds()
    private var target = 0f
    private var pos = 0f
    private var maxScroll = 0f

    /** membangun baris sekali; setiap baris membaca nilai lewat lambda, jadi selalu terbaru */
    private fun build(): Widget {
        val m = DangoMusic.instance.moduleManager.get(LyricsModule::class.java)
        val rows = ArrayList<Widget>()
        for (f in m.javaClass.declaredFields) {
            val ann = f.getAnnotation(ModuleValue::class.java) ?: continue
            f.isAccessible = true
            val v = f.get(m) ?: continue
            val label = ann.name.ifEmpty { f.name }
            when (v) {
                is BooleanValue -> rows += ValueWidgets.toggle(label, v)
                is FloatValue -> rows += ValueWidgets.floatSlider(label, v) { "%.${v.decimals}f".format(it) }
                is IntValue -> rows += ValueWidgets.intSlider(label, v) { it.toString() }
                else -> Unit   // warna, font, tombol, bahasa: tetap di SettingsPage
            }
        }
        // Tombol (B1): keybind dari NeteaseMusicModule (openKey dan key*)
        val nm = DangoMusic.instance.moduleManager.get(NeteaseMusicModule::class.java)
        val keys = ArrayList<Widget>()
        for (f in nm.javaClass.declaredFields) {
            val ann = f.getAnnotation(ModuleValue::class.java) ?: continue
            if (!(f.name == "openKey" || f.name.startsWith("key"))) continue
            f.isAccessible = true
            val v = (f.get(nm) as? IntValue) ?: continue
            keys += KeyRow(ann.name.ifEmpty { f.name }, v) { value -> nm.resetKeyBinding(value) }
        }
        return Stack(listOf(Section("Lyrics", rows), Section("Tombol", keys)))
    }

    fun draw(body: LayoutBounds) {
        val view = LayoutBounds(body.x, body.y + 40f, body.width, body.height - 40f)
        area = view
        val r = root ?: build().also { root = it }
        val h = r.measure(body.width)
        maxScroll = max(0f, h - view.height)
        target = target.coerceIn(0f, maxScroll)
        pos = ui.approach(pos, target, 60f)
        ui.beginClip(view)
        r.layout(LayoutBounds(body.x, view.y - pos, body.width, h))
        r.render(ui)
        ui.endClip()
    }

    fun scroll(wheel: Int) {
        target = (target + if (wheel > 0) -60f else 60f).coerceIn(0f, maxScroll)
    }
}
