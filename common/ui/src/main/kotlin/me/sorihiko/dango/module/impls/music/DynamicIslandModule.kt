package me.sorihiko.dango.module.impls.music

import me.sorihiko.dango.i18n.I18n

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.event.EventHandler
import me.sorihiko.dango.events.HudRenderEvent
import me.sorihiko.dango.font.MixedText
import me.sorihiko.dango.gfx.GuiCanvas
import me.sorihiko.dango.gfx.Texture
import me.sorihiko.dango.module.Category
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleMeta
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.musicui.IslandRects
import me.sorihiko.dango.musicui.IslandState
import me.sorihiko.dango.musicui.Library
import me.sorihiko.dango.module.values.FloatValue
import me.sorihiko.dango.module.values.IntValue
import me.sorihiko.dango.module.values.ModuleValue
import me.sorihiko.dango.music.CoverCache
import me.sorihiko.dango.music.MusicPlayer
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShadowStyle
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.ui.Vec2
import me.sorihiko.dango.update.UpdateChecker
import me.sorihiko.dango.utils.Color

import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 灵动岛（iOS 风格的胶囊）：
 *  - 平时：用户名 · FPS · 延迟 · 时间；内置播放器在放歌时显示 封面 + 歌名 + 跳动的音浪
 *  - 切歌 / 暂停 / 继续：展开成大卡片（封面、歌名、歌手、进度条）
 *  - 开关模块、攻击玩家：短暂显示对应信息
 *  - 有新版本时在游戏里提醒一次（每个版本只提醒一次）
 * 尺寸用弹簧动画过渡，内容淡入淡出；背景可选液态玻璃或纯黑。
 */
@ModuleMeta(Category.MUSIC, "DynamicIsland")
class DynamicIslandModule : Module() {

    @ModuleValue("Liquid glass", "Liquid glass background; off is a solid black pill")
    private val glass = BooleanValue(true)

    @ModuleValue("Show now playing", "Show what the built-in player is playing")
    private val showMusic = BooleanValue(true)

    @ModuleValue("Song notify", "Expand to show the song on track change or pause")
    private val songNotify = BooleanValue(true)

    @ModuleValue("Scale", "Island size")
    private val scale = FloatValue(0.6f, 1.6f, 1f, 2)

    @ModuleValue("Notify time", "How long song notifications stay (seconds)")
    private val notifyTime = FloatValue(1f, 8f, 3.5f, 1)


    @ModuleValue("Offset X", "Move the island sideways, in pixels")
    private val offsetX = IntValue(-400, 400, 0)

    @ModuleValue("Offset Y", "Move the island up or down, in pixels")
    private val offsetY = IntValue(-400, 400, 0)

    private fun vertical() = false   // hanya di atas

    /** Ukuran yang dipakai: di sisi layar lebarnya dan tingginya ditukar (pil berdiri tegak) */
    private fun dims(kind: Kind): Pair<Float, Float> =
        if (vertical() && kind == Kind.SONG) 72f to 150f          // pil sisi: cover bulat + batang visualizer
        else sizeOf(kind).let { (w0, h0) -> if (vertical()) h0 to w0 else w0 to h0 }

    private fun pillPosition(lw: Float, lh: Float, edge: Float, margin: Float): LayoutBounds =
        LayoutBounds((lw - w) / 2f + offsetX.value, edge + offsetY.value, w, h)

    private enum class Kind { IDLE, SONG, UPDATE }

    private var context: GuiCanvas? = null

    // 当前显示的内容 / 临时内容
    private var shown = Kind.IDLE
    private var temp: Kind? = null
    private var tempUntil = 0L
    private var contentAlpha = 0f

    // 弹簧动画（单位：逻辑像素）
    private var w = 0f
    private var h = 0f
    private var vw = 0f
    private var vh = 0f
    private var lastNanos = 0L
    private var enabledAt = 0L

    private var lastTrackId = ""
    private var lastState: MusicPlayer.State? = null
    /** 这次启动是否已经处理过新版本提醒 */
    private var updateHandled = false

    private val clock = SimpleDateFormat("HH:mm")

    private val mt = MixedText()

    override fun onEnable() {
        enabledAt = System.currentTimeMillis()
        w = 0f; h = 0f; vw = 0f; vh = 0f
        shown = Kind.IDLE
        temp = null
        contentAlpha = 0f
        lastNanos = 0L
        lastTrackId = MusicPlayer.current?.key ?: ""
        lastState = MusicPlayer.state
    }

    private fun show(kind: Kind, ms: Long) {
        temp = kind
        tempUntil = System.currentTimeMillis() + ms
    }

    private fun watchMusic() {
        val cur = MusicPlayer.current
        val st = MusicPlayer.state
        if (cur == null) {
            lastTrackId = ""
        } else if (cur.key != lastTrackId) {
            lastTrackId = cur.key
            if (songNotify.get()) show(Kind.SONG, (notifyTime.value * 1000).toLong())
        } else if (lastState != null && st != lastState && songNotify.get() &&
            (st == MusicPlayer.State.PAUSED || (st == MusicPlayer.State.PLAYING && lastState == MusicPlayer.State.PAUSED))
        ) {
            if (temp != Kind.SONG || tempUntil - System.currentTimeMillis() < 1500L) show(Kind.SONG, 1500L)
        }
        lastState = st
    }

    /** 有新版本、设置里开着检查更新、这个版本还没提醒过：等没有别的通知时展开提醒一次 */
    private fun watchUpdate() {
        if (updateHandled || temp != null || UpdateChecker.available == null || !DangoClient.platform.inGame) return
        updateHandled = true
        val on = try {
            DangoMusic.instance.moduleManager.get(NeteaseMusicModule::class.java).checkUpdates.get()
        } catch (_: Exception) {
            false
        }
        if (!on || !UpdateChecker.shouldNotify()) return
        show(Kind.UPDATE, 7000L)
        UpdateChecker.markNotified()
    }

    private fun desired(): Kind {
        val t = temp
        if (t != null && System.currentTimeMillis() < tempUntil) {
            val ok = when (t) {
                Kind.SONG -> MusicPlayer.current != null
                Kind.UPDATE, Kind.IDLE -> true
            }
            if (ok) return t
        }
        temp = null
        return Kind.IDLE
    }

    private fun musicIdle() = showMusic.get() && MusicPlayer.current != null && MusicPlayer.active

    // ---------------------------------------------------------------- 尺寸（逻辑像素）

    private fun sizeOf(kind: Kind): Pair<Float, Float> = when (kind) {
        Kind.SONG -> {
            val t = MusicPlayer.current
            val tw = if (t == null) 0f else max(
                width(t.name, 14f, Theme.FONT_SEMIBOLD).coerceAtMost(260f),
                width(t.artists.joinToString(" / "), 11f).coerceAtMost(260f),
            )
            (76f + max(tw, 150f) + 44f) to 76f
        }
        Kind.UPDATE -> {
            val tw = max(width(updateTitle(), 13f, Theme.FONT_SEMIBOLD), width(I18n.text("Copy the download link in the player's settings"), 11f))
            (16f + 8f + 10f + tw.coerceAtMost(320f) + 18f) to 52f
        }
        Kind.IDLE -> if (musicIdle()) {
            val t = MusicPlayer.current!!
            (clockW() + 9f + 22f + 9f + width(t.name, 12f, Theme.FONT_SEMIBOLD).coerceAtMost(170f) + 12f + 14f + 12f) to 32f
        } else {
            (14f + 6f + 8f + idleWidth() + 16f) to 32f
        }
    }

    private fun idleParts(): List<Pair<String, Color>> {
        val parts = mutableListOf<Pair<String, Color>>()
        val platform = DangoClient.platform
        parts += platform.playerName to Theme.TEXT_PRIMARY
        parts += "${platform.fps} fps" to Theme.TEXT_SECONDARY
        val ping = platform.pingMs
        if (ping > 0) parts += "$ping ms" to Theme.TEXT_SECONDARY
        parts += clock.format(Date()) to Theme.TEXT_SECONDARY
        return parts
    }

    private fun idleWidth(): Float {
        val parts = idleParts()
        var x = 0f
        parts.forEachIndexed { i, (s, _) ->
            if (i > 0) x += width("  ·  ", 12f)
            x += width(s, 12f, if (i == 0) Theme.FONT_SEMIBOLD else Theme.FONT_REGULAR)
        }
        return x
    }

    // ---------------------------------------------------------------- 渲染

    @EventHandler
    fun render(e: HudRenderEvent) {
        if (!enabled) return
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.016f else ((now - lastNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastNanos = now

        watchMusic()
        watchUpdate()
        val want = desired()

        // 内容切换：先淡出旧内容，再换成新内容淡入
        if (shown != want) {
            contentAlpha -= dt / 0.09f
            if (contentAlpha <= 0f) {
                contentAlpha = 0f
                shown = want
            }
        } else {
            contentAlpha = min(1f, contentAlpha + dt / 0.16f)
        }

        // 弹簧：略带回弹，接近 iOS 灵动岛的手感
        val (tw, th) = dims(want)
        val stiff = 230f
        val damp = 24f
        vw += ((tw - w) * stiff - vw * damp) * dt
        vh += ((th - h) * stiff - vh * damp) * dt
        w += vw * dt
        h += vh * dt
        if (w < 1f || h < 1f) return

        val platform = DangoClient.platform
        val ctx = context ?: DangoClient.gfx.newCanvas().also { context = it }
        ctx.begin(glass.get(), 0, 0)
        try {
            val uiScale = min(platform.windowWidth / 1280f, platform.windowHeight / 800f).coerceAtLeast(0.5f)
            val k = uiScale * scale.value
            val lw = platform.windowWidth / k
            val sf = platform.guiScale
            val top = 18f * sf / k                       // 和原来一样，留出 Boss 血条的位置
            val lh = platform.windowHeight / k
            val pill = pillPosition(lw, lh, top, 14f * sf / k)
            // animasi mengembang / mengecil dijalankan di sini, jadi tetap jalan walau layar island sudah tutup
            if (System.currentTimeMillis() - IslandState.lastFrame > 500L) IslandState.expandTarget = false
            IslandState.expandT += ((if (IslandState.expandTarget) 1f else 0f) - IslandState.expandT) * 0.2f
            IslandState.setPill(pill.x, pill.y, pill.width, pill.height, lw, lh)
            // ukuran island yang sudah expand (di tengah atas), dan keadaan animasinya
            val full = LayoutBounds((lw - 480f) / 2f, top, 480f, 160f)
            IslandState.setExpanded(full.x, full.y, full.width, full.height, lw, lh)
            val e = IslandState.expandT.coerceIn(0f, 1f)
            val rect = LayoutBounds(
                islandLerp(pill.x, full.x, e), islandLerp(pill.y, full.y, e),
                islandLerp(pill.width, full.width, e), islandLerp(pill.height, full.height, e),
            )
            val r = min(h / 2f, 22f)

            ctx.pushScale(k, k, 1f)
            val bgRadius = islandLerp(r, 26f, e)
            if (glass.get()) {
                ctx.drawGlass(rect, Theme.GLASS_WINDOW.copy(radius = bgRadius, shadow = ShadowStyle(Color(0f, 0f, 0f, 0.14f), 8f, Vec2(0f, -2f))))
            } else {
                ctx.drawShape(
                    rect,
                    ShapeStyle(
                        islandBg(e), bgRadius, 1f, Color(1f, 1f, 1f, 0.07f),
                        ShadowStyle(Color(0f, 0f, 0f, 0.18f), 8f, Vec2(0f, -2f)),
                    )
                )
            }

            // isi: saat belum expand = isi island asli (memudar keluar); saat expand = isi expand (memudar masuk)
            val (cw, ch) = dims(shown)
            val cb = LayoutBounds(pill.x + (w - cw) / 2f, pill.y + (h - ch) / 2f, cw, ch)
            ctx.scissor((rect.x * k).toInt(), (platform.windowHeight - (rect.y + rect.height) * k).toInt(), (rect.width * k).toInt() + 1, (rect.height * k).toInt() + 1)
            mt.clear()
            mt.clip = rect
            val compactAlpha = contentAlpha * (1f - e / 0.5f).coerceIn(0f, 1f)
            if (compactAlpha > 0.01f) {
                ctx.pushOpacity(compactAlpha)
                when (shown) {
                    Kind.IDLE -> if (musicIdle()) drawMusicIdle(ctx, cb) else drawIdle(ctx, cb)
                    Kind.SONG -> if (vertical()) drawSongVertical(ctx, cb) else drawSong(ctx, cb)
                    Kind.UPDATE -> drawUpdate(ctx, cb)
                }
                ctx.popOpacity()
            }
            val expandAlpha = ((e - 0.4f) / 0.6f).coerceIn(0f, 1f)
            if (expandAlpha > 0.01f) {
                ctx.pushOpacity(expandAlpha)
                drawExpanded(ctx, full)
                ctx.popOpacity()
            }
            ctx.popTransform()
            mt.flush(k)
            ctx.noScissor()
        } finally {
            ctx.end()
        }
    }

    private fun islandLerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** latar island: gelap; saat mengembang berubah ke warna dominan cover (immersive) */
    private fun islandBg(e: Float): Color {
        val a = MusicPlayer.current?.let { CoverCache.accent(it) } ?: return Color(0.02f, 0.02f, 0.03f, 0.9f)
        val k = (e * 0.6f).coerceIn(0f, 1f)
        return Color(
            0.02f + (a.first * 0.45f - 0.02f) * k,
            0.02f + (a.second * 0.45f - 0.02f) * k,
            0.03f + (a.third * 0.45f - 0.03f) * k,
            0.9f,
        )
    }

    /** Isi island saat expand: cover, judul + jam (satu garis), artis, progres, tombol, volume, lirik, search. */
    private fun drawExpanded(ctx: GuiCanvas, b: LayoutBounds) {
        val t = MusicPlayer.current ?: return
        val r = IslandRects(b)
        val playing = MusicPlayer.state == MusicPlayer.State.PLAYING
        ctx.drawShape(LayoutBounds(b.x + b.width * 0.5f, b.y + 16f, 1f, b.height - 32f), ShapeStyle(Color(1f, 1f, 1f, 0.12f), 0.5f))

        CoverCache.upload()
        CoverCache.trackRounded(t, 0.2f)?.let { ctx.drawImage(r.cover, it) }

        // jam dan judul di garis y yang sama
        val clock = clockText()
        val clockW = width(clock, 13f, Theme.FONT_SEMIBOLD)
        val titleW = b.width * 0.5f - (r.titleX - b.x) - 16f - clockW - 10f
        text(ctx, clock, b.x + b.width * 0.5f - 16f - clockW, r.titleY, 13f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        text(ctx, trim(t.name, titleW, 13f, Theme.FONT_SEMIBOLD), r.titleX, r.titleY, 13f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        text(ctx, trim(t.artists.joinToString(" / "), titleW, 11f), r.titleX, r.artistY, 11f, Theme.TEXT_SECONDARY)

        // progres
        ctx.drawShape(r.progress, ShapeStyle(Theme.TRACK, 1.5f))
        val dur = MusicPlayer.durationMs
        if (dur > 0) {
            val f = (MusicPlayer.positionMs.toDouble() / dur).coerceIn(0.0, 1.0).toFloat()
            ctx.drawShape(LayoutBounds(r.progress.x, r.progress.y, max(3f, r.progress.width * f), r.progress.height), ShapeStyle(Theme.TEXT_PRIMARY, 1.5f))
        }

        // tombol: prev, play/pause, next
        for (btnRect in listOf(r.prev, r.play, r.next)) ctx.drawShape(btnRect, ShapeStyle(Theme.CARD, btnRect.width / 2f))
        glyphCentered(ctx, "◀", r.prev, Theme.TEXT_SECONDARY)
        if (playing) {
            val pc = r.play
            ctx.drawShape(LayoutBounds(pc.x + pc.width * 0.36f, pc.y + pc.height * 0.3f, pc.width * 0.12f, pc.height * 0.4f), ShapeStyle(Theme.TEXT_PRIMARY, 1f))
            ctx.drawShape(LayoutBounds(pc.x + pc.width * 0.54f, pc.y + pc.height * 0.3f, pc.width * 0.12f, pc.height * 0.4f), ShapeStyle(Theme.TEXT_PRIMARY, 1f))
        } else {
            glyphCentered(ctx, "▶", r.play, Theme.TEXT_PRIMARY)
        }
        glyphCentered(ctx, "▶", r.next, Theme.TEXT_SECONDARY)

        // volume
        ctx.drawShape(r.volume, ShapeStyle(Theme.TRACK, 3f))
        val vol = MusicPlayer.volume.coerceIn(0f, 1f)
        ctx.drawShape(LayoutBounds(r.volume.x, r.volume.y, max(3f, r.volume.width * vol), r.volume.height), ShapeStyle(Theme.TEXT_PRIMARY, 3f))

        // lirik (baris yang sedang dinyanyikan lebih besar)
        val lyrics = Library.lyricsFor(t)
        val lines = lyrics?.lines.orEmpty()
        val lcx = r.lyrics.x + r.lyrics.width / 2f
        val lmid = r.lyrics.y + r.lyrics.height / 2f
        if (lines.isEmpty()) {
            val msg = if (lyrics == null && !Library.lyricsReady(t)) "Loading lyrics..." else "No lyrics"
            centeredText(ctx, msg, lcx, lmid - 0.39f * 11f, 11f, Theme.TEXT_TERTIARY)
        } else {
            val idx = max(0, lyrics!!.indexAt(MusicPlayer.positionMs))
            for (k in -1..1) {
                val j = idx + k
                if (j < 0 || j >= lines.size) continue
                val active = k == 0
                val size = if (active) 14f else 11f
                val font = if (active) Theme.FONT_SEMIBOLD else Theme.FONT_REGULAR
                val color = if (active) Theme.TEXT_PRIMARY else Theme.TEXT_TERTIARY
                centeredText(ctx, trim(lines[j].text, r.lyrics.width - 16f, size, font), lcx, lmid + k * 24f - 0.39f * size, size, color, font)
            }
        }

        // search bar
        ctx.drawShape(r.search, ShapeStyle(Theme.CARD, r.search.height / 2f))
        text(ctx, I18n.text("Search songs..."), r.search.x + 14f, r.search.y + r.search.height / 2f - 0.39f * 11f, 11f, Theme.TEXT_TERTIARY)
    }

    private fun glyphCentered(ctx: GuiCanvas, s: String, b: LayoutBounds, color: Color) {
        val cx = b.x + b.width / 2f
        val cy = b.y + b.height / 2f
        text(ctx, s, cx - width(s, 12f) / 2f, cy - 0.39f * 12f, 12f, color)
    }

    private fun centeredText(ctx: GuiCanvas, s: String, cx: Float, y: Float, size: Float, color: Color, font: String = Theme.FONT_REGULAR) {
        if (s.isEmpty()) return
        text(ctx, s, cx - width(s, size, font) / 2f, y, size, color, font)
    }

    private fun drawIdle(ctx: GuiCanvas, b: LayoutBounds) {
        val cy = b.y + b.height / 2f
        ctx.drawShape(LayoutBounds(b.x + 14f, cy - 3f, 6f, 6f), ShapeStyle(Theme.GREEN, 3f))
        var x = b.x + 28f
        val ty = cy - 12f * 0.39f
        idleParts().forEachIndexed { i, (s, c) ->
            if (i > 0) {
                text(ctx, "  ·  ", x, ty, 12f, Theme.TEXT_TERTIARY)
                x += width("  ·  ", 12f)
            }
            val font = if (i == 0) Theme.FONT_SEMIBOLD else Theme.FONT_REGULAR
            text(ctx, s, x, ty, 12f, c, font)
            x += width(s, 12f, font)
        }
    }

    private fun drawMusicIdle(ctx: GuiCanvas, b: LayoutBounds) {
        val t = MusicPlayer.current ?: return
        // 胶囊两头是半圆：封面往里挪一点、圆角跟着胶囊的弧度走，看起来才居中
        val cw = clockW()
        if (cw > 0f) text(ctx, clockText(), b.x + 10f, b.y + b.height / 2f - 0.39f * 12f, 12f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        val cover = LayoutBounds(b.x + 9f + cw, b.y + 5f, 22f, 22f)
        drawCover(ctx, cover, t.let { CoverCache.trackRounded(it, 0.36f) ?: CoverCache.track(it) })
        val playing = MusicPlayer.state == MusicPlayer.State.PLAYING
        val tx = cover.right + 9f
        val title = trim(t.name, 170f, 12f, Theme.FONT_SEMIBOLD)
        text(ctx, title, tx, b.y + b.height / 2f - 0.39f * 12f, 12f, if (playing) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
        bars(ctx, b.right - 12f - 14f, b.y + 22f, playing, 11f)
    }

    private fun updateTitle() = I18n.text("LULLABY+ update: ") + (UpdateChecker.available?.version ?: "")

    private fun drawUpdate(ctx: GuiCanvas, b: LayoutBounds) {
        ctx.drawShape(LayoutBounds(b.x + 16f, b.y + 22f, 8f, 8f), ShapeStyle(Theme.ACCENT, 4f))
        val tx = b.x + 34f
        val tw = b.right - 18f - tx
        text(ctx, trim(updateTitle(), tw, 13f, Theme.FONT_SEMIBOLD), tx, b.y + 12f, 13f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        text(ctx, trim(I18n.text("Copy the download link in the player's settings"), tw, 11f), tx, b.y + 30f, 11f, Theme.TEXT_SECONDARY)
    }

    private fun drawSong(ctx: GuiCanvas, b: LayoutBounds) {
        val t = MusicPlayer.current ?: return
        val cover = LayoutBounds(b.x + 10f, b.y + 10f, 56f, 56f)
        drawCover(ctx, cover, CoverCache.trackRounded(t) ?: CoverCache.track(t))
        val playing = MusicPlayer.state == MusicPlayer.State.PLAYING
        val tx = cover.right + 10f
        val tw = b.right - 14f - tx

        val label = when (MusicPlayer.state) {
            MusicPlayer.State.PAUSED -> I18n.text("PAUSED")
            MusicPlayer.State.LOADING -> I18n.text("LOADING")
            else -> I18n.text("NOW PLAYING")
        }
        text(ctx, label, tx, b.y + 11f, 9f, if (playing) Theme.ACCENT else Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
        bars(ctx, b.right - 14f - 14f, b.y + 21f, playing, 10f)
        text(ctx, trim(t.name, tw - 4f, 14f, Theme.FONT_SEMIBOLD), tx, b.y + 24f, 14f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        text(ctx, trim(t.artists.joinToString(" / "), tw, 11f), tx, b.y + 42f, 11f, Theme.TEXT_SECONDARY)

        val bar = LayoutBounds(tx, b.y + 60f, tw, 3f)
        ctx.drawShape(bar, ShapeStyle(Theme.TRACK, 1.5f))
        val dur = MusicPlayer.durationMs
        if (dur > 0) {
            val f = (MusicPlayer.positionMs.toDouble() / dur).coerceIn(0.0, 1.0).toFloat()
            ctx.drawShape(LayoutBounds(bar.x, bar.y, max(3f, bar.width * f), bar.height), ShapeStyle(Theme.TEXT_PRIMARY, 1.5f))
        }
    }

    @ModuleValue("Clock", "Show the real-time clock to the left of the cover")
    private val showClock = BooleanValue(true)

    /** Jam sekarang (HH:mm), dihitung ulang setiap frame */
    private fun clockText(): String = java.time.LocalTime.now().let { "%02d:%02d".format(it.hour, it.minute) }

    /** Lebar yang dipakai jam (0 kalau dimatikan) */
    private fun clockW(): Float = if (showClock.get()) width(clockText(), 14f, Theme.FONT_SEMIBOLD) + 14f else 0f

    private val sideBars = FloatArray(11)

    /** Mode samping: capsule seperti Dynamic Island atas tapi diputar. Cover bulat di atas, batang audio mengikuti volume lagu. */
    private fun drawSongVertical(ctx: GuiCanvas, b: LayoutBounds) {
        val t = MusicPlayer.current ?: return
        val cx = b.x + b.width / 2f
        val d = 46f
        val cover = LayoutBounds(cx - d / 2f, b.y + 14f, d, d)
        ctx.drawShape(cover, ShapeStyle(Theme.TRACK, d / 2f))     // alas bulat dulu, supaya tepi cover tidak bolong
        CoverCache.upload()
        CoverCache.trackRounded(t, 0.5f)?.let { ctx.drawImage(cover, it) }

        val playing = MusicPlayer.state == MusicPlayer.State.PLAYING
        val level = if (playing) MusicPlayer.levelAt(MusicPlayer.positionMs) else 0f
        val top = cover.bottom + 12f
        val n = sideBars.size
        val step = (b.bottom - 14f - top) / n
        for (i in 0 until n) {
            val shape = 0.55f + 0.45f * kotlin.math.abs(sin(i * 1.3)).toFloat()
            val target = (0.06f + level * 3.8f * shape).coerceIn(0.06f, 1f)
            sideBars[i] += (target - sideBars[i]) * 0.5f         // halus, tidak loncat
            val w = 6f + 36f * sideBars[i]
            ctx.drawShape(
                LayoutBounds(cx - w / 2f, top + i * step, w, 2.4f),
                ShapeStyle(if (playing) Theme.TEXT_PRIMARY else Theme.TEXT_TERTIARY, 1.2f),
            )
        }
    }

    /** Teks rata tengah di sumbu [cx] */
    private fun centered(ctx: GuiCanvas, s: String, cx: Float, y: Float, size: Float, color: Color, font: String = Theme.FONT_REGULAR) {
        text(ctx, s, cx - width(s, size, font) / 2f, y, size, color, font)
    }

    private fun drawCover(ctx: GuiCanvas, b: LayoutBounds, tex: Texture?) {
        CoverCache.upload()
        if (tex != null) ctx.drawImage(b, tex)
        else ctx.drawShape(b, ShapeStyle(Theme.TRACK, b.width * 0.2f))
    }

    /** 跳动的音浪：3 根小柱子，底部对齐 yBottom */
    private fun bars(ctx: GuiCanvas, x: Float, yBottom: Float, playing: Boolean, maxH: Float) {
        val time = System.nanoTime() / 1e9
        for (i in 0 until 4) {
            val hh = if (playing)
                (3f + (maxH - 3f) * (0.5f + 0.5f * sin(time * (5.5 + i * 1.9) + i * 1.3).toFloat()))
            else 3f
            ctx.drawShape(
                LayoutBounds(x + i * 3.6f, yBottom - hh, 2.2f, hh),
                ShapeStyle(if (playing) Theme.TEXT_PRIMARY else Theme.TEXT_TERTIARY, 1.1f),
            )
        }
    }

    // ---------------------------------------------------------------- 文字（拉丁用菜单字体，中文用 MC 字体），y 是文字顶部

    private fun text(ctx: GuiCanvas, s: String, x: Float, y: Float, size: Float, color: Color, font: String = Theme.FONT_REGULAR) =
        mt.text(ctx, s, x, y, size, color, font)

    private fun width(s: String, size: Float, font: String = Theme.FONT_REGULAR): Float = mt.width(s, size, font)

    private fun trim(s: String, maxW: Float, size: Float, font: String = Theme.FONT_REGULAR): String = mt.trim(s, maxW, size, font)
}
