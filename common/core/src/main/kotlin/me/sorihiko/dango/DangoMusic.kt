package me.sorihiko.dango

import me.sorihiko.dango.config.ConfigManager
import me.sorihiko.dango.event.Event
import me.sorihiko.dango.event.EventManager
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleManager
import me.sorihiko.dango.platform.Platform

import java.nio.file.Files
import java.nio.file.Path

/**
 * Dango Music 的核心：事件系统 + 模块 + 设置。所有 Minecraft 版本共用这一份。
 * 和游戏打交道的地方都通过 [Platform]，由各版本的平台层在启动时传进来。
 */
class DangoMusic private constructor(val platform: Platform) {
    companion object {
        const val MOD_ID = "lullaby"
        const val NAME = "Lullaby+"

        lateinit var instance: DangoMusic
            private set

        /** 平台层在 mod 初始化时调用：注册模块、读取设置 */
        @JvmStatic
        fun start(platform: Platform, modules: List<Module>) {
            instance = DangoMusic(platform)
            instance.init(modules)
        }

        @JvmStatic
        fun callEvent(event: Event) {
            if (::instance.isInitialized) instance.eventManager.callEvent(event)
        }
    }

    /** 配置、登录凭证、缓存都放在 .minecraft/config/lullaby/ */
    val clientDir: Path = platform.configDir.also { migrateLegacyConfig(it) }

    val eventManager = EventManager()
    val moduleManager = ModuleManager(eventManager)
    val configManager = ConfigManager(moduleManager, clientDir.resolve("config.json"))

    /**
     * Pindah config lama (config/dangomusic/) ke config/lullaby/ sekali saja, supaya
     * playlist, cookie login, spotify.txt, dan pengaturan yang sudah ada tetap terbaca.
     * Folder cache yt-cache tidak ikut disalin (bisa besar, bisa dibuat ulang).
     */
    private fun migrateLegacyConfig(target: Path) {
        val legacy = target.resolveSibling("dangomusic")
        if (Files.exists(target) || !Files.isDirectory(legacy)) return
        try {
            Files.walk(legacy).use { stream ->
                stream.forEach { src ->
                    val rel = legacy.relativize(src)
                    if (rel.toString().startsWith("yt-cache")) return@forEach
                    val dst = target.resolve(rel.toString())
                    if (Files.isDirectory(src)) Files.createDirectories(dst)
                    else if (!Files.exists(dst)) { Files.createDirectories(dst.parent); Files.copy(src, dst) }
                }
            }
            DangoLogger.info("Config lama dipindahkan dari config/dangomusic ke config/lullaby")
        } catch (e: Exception) {
            DangoLogger.warn("Migrasi config lama gagal: {}", e.javaClass.simpleName)
        }
    }

    private fun init(modules: List<Module>) {
        clientDir.toFile().mkdirs()

        modules.forEach { moduleManager.register(it) }
        moduleManager.load()

        configManager.load()
        eventManager.addListener(configManager)
        configManager.installShutdownHook()
    }
}
