package me.sorihiko.dango.musicui

import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.i18n.I18n
import me.sorihiko.dango.module.impls.music.LyricsModule
import me.sorihiko.dango.module.impls.music.NeteaseMusicModule
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.FloatValue
import me.sorihiko.dango.module.values.IntValue
import me.sorihiko.dango.module.values.ModuleValue
import me.sorihiko.dango.music.MusicScreen
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.Theme
import kotlin.math.max

/** Settings bergaya LyricsFlow (rail tab di kiri, judul, kartu glass), warna dan Liquid Glass Lullaby. */
internal const val WIDGET_LYRICS_TAB = true

internal class LyricsWidgetPage(private val screen: MusicScreen) {
    private val ui = screen.ui

    /** true = tampilkan halaman lama (Music & HUD, warna, font, bahasa) */
    var legacy = false

    /** area konten yang terlihat (dipakai roda mouse di MusicScreen) */
    var area = LayoutBounds()

    private var tab = 0
    private var target = 0f
    private var pos = 0f
    private var maxScroll = 0f
    private val pages = arrayOfNulls<Widget>(TABS.size)

    private fun pageOf(i: Int): Widget = pages[i] ?: build(i).also { pages[i] = it }

    private fun build(i: Int): Widget = when (i) {
        0 -> lyricsPage()
        1 -> controlsPage()
        else -> advancedPage()
    }

    /** Tab Lyrics: toggle di GENERAL, slider di SIZE (nilai dibaca lewat anotasi, seperti SettingsPage) */
    private fun lyricsPage(): Widget {
        val m = DangoMusic.instance.moduleManager.get(LyricsModule::class.java)
        val general = ArrayList<Widget>()
        val size = ArrayList<Widget>()
        for (f in m.javaClass.declaredFields) {
            val ann = f.getAnnotation(ModuleValue::class.java) ?: continue
            f.isAccessible = true
            val v = f.get(m) ?: continue
            val label = ann.name.ifEmpty { f.name }
            when (v) {
                is BooleanValue -> general += SwitchRow(label, ann.desc, { v.get() }, { v.set(it) })
                is FloatValue -> size += SliderRow(label, { v.normalize() },
                    { x -> v.setValue(v.minRange + (v.maxRange - v.minRange) * x) },
                    { x -> "%.${v.decimals}f".format(v.minRange + (v.maxRange - v.minRange) * x) })
                is IntValue -> size += SliderRow(label, { v.normalize() },
                    { x -> v.setValue(v.minRange + ((v.maxRange - v.minRange) * x).toInt()) },
                    { x -> (v.minRange + ((v.maxRange - v.minRange) * x).toInt()).toString() })
                else -> Unit   // warna, font, tombol: di halaman lama (Advanced)
            }
        }
        return Stack(listOf(
            SectionLabel("General"), Card(general),
            SectionLabel("Size"), Card(size),
        ), 10f)
    }

    /** Tab Controls: keybind dari NeteaseMusicModule (openKey dan key*) */
    private fun controlsPage(): Widget {
        val nm = DangoMusic.instance.moduleManager.get(NeteaseMusicModule::class.java)
        val keys = ArrayList<Widget>()
        for (f in nm.javaClass.declaredFields) {
            val ann = f.getAnnotation(ModuleValue::class.java) ?: continue
            if (!(f.name == "openKey" || f.name.startsWith("key"))) continue
            f.isAccessible = true
            val v = (f.get(nm) as? IntValue) ?: continue
            keys += KeyRow(ann.name.ifEmpty { f.name }, v) { value -> nm.resetKeyBinding(value) }
        }
        return Stack(listOf(SectionLabel("Keybind"), Card(keys)), 10f)
    }

    /** Tab Advanced: pintu ke halaman lama (Music & HUD, warna, font, bahasa) */
    private fun advancedPage(): Widget = Stack(listOf(
        SectionLabel("Lainnya"),
        Card(listOf(ButtonRow(I18n.text("Music & HUD, warna, font, bahasa  \u203a")) { legacy = true })),
    ), 10f)

    fun draw(body: LayoutBounds) {
        // rail tab di kiri, seperti LyricsFlow: ikon di atas, nama di bawah
        for ((i, name) in TABS.withIndex()) {
            val item = LayoutBounds(body.x + 6f, body.y + 6f + i * 62f, 84f, 54f)
            val on = i == tab
            if (on) ui.card(item, Theme.CARD_HOVER, 14f)
            val glyph = GLYPHS[i]
            ui.text(glyph, item.x + (item.width - ui.width(glyph, 14f)) / 2f, item.y + 8f, 14f,
                if (on) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY)
            ui.text(name, item.x + (item.width - ui.width(name, 10f, Theme.FONT_SEMIBOLD)) / 2f, item.y + 30f, 10f,
                if (on) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
            ui.hits += item to {
                if (tab != i) {
                    tab = i
                    target = 0f
                    pos = 0f
                }
            }
        }

        // judul dan subjudul, lalu konten bisa di-scroll
        val cx = body.x + 104f
        val cw = body.width - 104f
        ui.text(TABS[tab], cx, body.y + 2f, 20f, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
        ui.text(SUBTITLES[tab], cx, body.y + 28f, 11f, Theme.TEXT_TERTIARY)
        val view = LayoutBounds(cx, body.y + 56f, cw, body.height - 56f)
        area = view
        val r = pageOf(tab)
        val h = r.measure(cw)
        maxScroll = max(0f, h - view.height)
        target = target.coerceIn(0f, maxScroll)
        pos = ui.approach(pos, target, 60f)
        ui.beginClip(view)
        r.layout(LayoutBounds(cx, view.y - pos, cw, h))
        r.render(ui)
        ui.endClip()
    }

    /** tombol kembali di atas halaman lama */
    fun drawBackButton(body: LayoutBounds) {
        area = LayoutBounds()
        ui.pill(LayoutBounds(body.right - 150f, body.y, 142f, 28f), Theme.CARD, I18n.text("\u2039 Lyrics & Tombol"), null) {
            legacy = false
        }
    }

    fun scroll(wheel: Int) {
        target = (target + if (wheel > 0) -60f else 60f).coerceIn(0f, maxScroll)
    }

    companion object {
        private val TABS = listOf("Lyrics", "Controls", "Advanced")
        private val GLYPHS = listOf("\u2261", "\u2328", "\u2699")
        private val SUBTITLES = listOf(
            "How and where lyrics appear",
            "Keyboard shortcuts for the player",
            "Other settings",
        )
    }
}
