package me.sorihiko.dango.music

// =====================================================================
//  OpusWebmDecoder
//  Demux WebM (EBML) minimal + decode Opus pakai Concentus (pure Java).
//  Output: AudioDecoder.Frame(samples interleaved, length = total short,
//  rate = 48000, channels) - sama persis konvensi decoder MP3 bawaan.
// =====================================================================

import io.github.jaredmdobson.concentus.OpusDecoder

internal class OpusWebmDecoder(private val data: ByteArray) : AudioDecoder {

    companion object {
        private const val RATE = 48000
        private const val MAX_FRAME = 5760 // 120 ms @ 48 kHz

        /** Magic EBML header: 1A 45 DF A3 */
        fun matches(b: ByteArray): Boolean =
            b.size > 4 &&
                b[0] == 0x1A.toByte() && b[1] == 0x45.toByte() &&
                b[2] == 0xDF.toByte() && b[3] == 0xA3.toByte()
    }

    private val packets = ArrayList<IntArray>() // [offset, length]
    private var channels = 2
    private var preSkip = 0
    private var idx = 0
    private var skipLeft = 0
    private val pcm: ShortArray
    private val dec: OpusDecoder

    init {
        parse(0, data.size)
        if (packets.isEmpty()) throw IllegalStateException("WebM tanpa paket Opus")
        if (channels !in 1..2) throw IllegalStateException("Opus channels=$channels tidak didukung")
        dec = OpusDecoder(RATE, channels)
        pcm = ShortArray(MAX_FRAME * channels)
        skipLeft = preSkip
    }

    // ------------------------------------------------------------- EBML
    private fun parse(from: Int, to: Int) {
        var p = from
        while (p < to) {
            val first = data[p].toInt() and 0xFF
            if (first == 0) return
            val idLen = Integer.numberOfLeadingZeros(first) - 24 + 1
            if (idLen > 4 || p + idLen > to) return
            var id = 0L
            for (k in 0 until idLen) id = (id shl 8) or (data[p + k].toLong() and 0xFF)
            p += idLen
            if (p >= to) return

            val sFirst = data[p].toInt() and 0xFF
            if (sFirst == 0) return
            val sLen = Integer.numberOfLeadingZeros(sFirst) - 24 + 1
            if (sLen > 8 || p + sLen > to) return
            val mask = 0xFF shr sLen
            var size = (sFirst and mask).toLong()
            var allOnes = (sFirst and mask) == mask
            for (k in 1 until sLen) {
                val b = data[p + k].toLong() and 0xFF
                size = (size shl 8) or b
                if (b != 0xFFL) allOnes = false
            }
            p += sLen
            val bodyEnd = if (allOnes) to else minOf(to.toLong(), p.toLong() + size).toInt()

            when (id) {
                // Segment, Cluster, BlockGroup, Tracks, TrackEntry
                0x18538067L, 0x1F43B675L, 0xA0L, 0x1654AE6BL, 0xAEL -> parse(p, bodyEnd)
                // SimpleBlock, Block
                0xA3L, 0xA1L -> addBlock(p, bodyEnd)
                // CodecPrivate (OpusHead)
                0x63A2L -> readOpusHead(p, bodyEnd)
            }
            p = bodyEnd
        }
    }

    private fun addBlock(p: Int, end: Int) {
        if (p >= end) return
        val tn = data[p].toInt() and 0xFF
        if (tn == 0) return
        val trackLen = Integer.numberOfLeadingZeros(tn) - 24 + 1
        val flagsPos = p + trackLen + 2
        if (flagsPos >= end) return
        val lacing = (data[flagsPos].toInt() shr 1) and 3
        if (lacing != 0) return // Opus YouTube tidak pakai lacing
        val off = flagsPos + 1
        val len = end - off
        if (len > 0) packets.add(intArrayOf(off, len))
    }

    private fun readOpusHead(p: Int, end: Int) {
        if (end - p < 19) return
        if (String(data, p, 8, Charsets.ISO_8859_1) != "OpusHead") return
        channels = data[p + 9].toInt() and 0xFF
        preSkip = (data[p + 10].toInt() and 0xFF) or ((data[p + 11].toInt() and 0xFF) shl 8)
    }

    // ----------------------------------------------------------- decoder
    override fun read(): AudioDecoder.Frame? {
        while (idx < packets.size) {
            val pk = packets[idx++]
            val n = try {
                dec.decode(data, pk[0], pk[1], pcm, 0, MAX_FRAME, false)
            } catch (_: Exception) {
                continue
            }
            if (n <= 0) continue
            var start = 0
            if (skipLeft > 0) {
                val s = minOf(skipLeft, n)
                skipLeft -= s
                start = s
            }
            val count = (n - start) * channels
            if (count <= 0) continue
            val out = ShortArray(count)
            System.arraycopy(pcm, start * channels, out, 0, count)
            return AudioDecoder.Frame(out, count, RATE, channels)
        }
        return null
    }

    override fun close() {}
}
