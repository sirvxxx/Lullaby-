package me.sorihiko.dango

/**
 * 低配模式（Lyrics 模块里的 Low-end mode）。关着 = 和原来一样。
 * 开着时：液态玻璃背景每隔几帧才重新截取一次、Deep Glow 发光关闭。
 */
object Performance {
    @Volatile @JvmField var lowEnd = false

    /** Skala resolusi buffer blur kaca: 1f = normal, 0.5f, 0.25f = lebih ringan (lebih buram) */
    @Volatile @JvmField var blurScale = 1f

    /** 低配模式下，玻璃背景每几帧截取一次 */
    const val GLASS_CAPTURE_EVERY = 4
}
