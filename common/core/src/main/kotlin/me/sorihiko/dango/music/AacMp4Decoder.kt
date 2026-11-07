package me.sorihiko.dango.music

// =====================================================================
//  AacMp4Decoder
//  Demux MP4/M4A minimal (progressive `stbl` DAN fragmented `moof/trun`)
//  + decode AAC pakai JAAD (pure Java). Hanya Decoder + SampleBuffer dari
//  JAAD yang dipakai; parsing kontainer ditulis sendiri.
// =====================================================================

import net.sourceforge.jaad.aac.SampleBuffer
import net.sourceforge.jaad.aac.Decoder as AacDecoder

internal class AacMp4Decoder(private val data: ByteArray) : AudioDecoder {

    private val samples = ArrayList<IntArray>() // [offset, length]
    private var asc: ByteArray? = null
    private var idx = 0
    private val buf = SampleBuffer()

    // fragmented state
    private var tfBase = 0L
    private var defSize = 0
    private var trunCur = -1L

    // progressive (stbl) state
    private var sizes: IntArray? = null
    private var stscFirst: IntArray? = null
    private var stscCount: IntArray? = null
    private var chunkOffsets: LongArray? = null

    private val dec: AacDecoder

    init {
        walk(0, data.size, 0)
        if (samples.isEmpty()) buildFromStbl()
        val config = asc ?: throw IllegalStateException("M4A tanpa konfigurasi AAC (esds)")
        if (samples.isEmpty()) throw IllegalStateException("M4A tanpa sample audio")
        dec = AacDecoder(config)
        buf.setBigEndian(false)
    }

    // -------------------------------------------------------------- helpers
    private fun u32(p: Int): Long =
        ((data[p].toLong() and 255) shl 24) or ((data[p + 1].toLong() and 255) shl 16) or
            ((data[p + 2].toLong() and 255) shl 8) or (data[p + 3].toLong() and 255)

    private fun u64(p: Int): Long = (u32(p) shl 32) or u32(p + 4)

    // -------------------------------------------------------------- boxes
    private fun walk(start: Int, end: Int, moofStart: Int) {
        var p = start
        while (p + 8 <= end) {
            var size = u32(p)
            val type = String(data, p + 4, 4, Charsets.ISO_8859_1)
            var hdr = 8
            if (size == 1L) {
                if (p + 16 > end) return
                size = u64(p + 8); hdr = 16
            } else if (size == 0L) size = (end - p).toLong()
            if (size < hdr) return
            val boxEnd = minOf(end.toLong(), p + size).toInt()
            val body = p + hdr
            when (type) {
                "moov", "trak", "mdia", "minf", "stbl", "mvex" -> walk(body, boxEnd, moofStart)
                "moof" -> walk(body, boxEnd, p)
                "traf" -> {
                    tfBase = moofStart.toLong(); defSize = 0; trunCur = -1L
                    walk(body, boxEnd, moofStart)
                }
                "tfhd" -> readTfhd(body, boxEnd, moofStart)
                "trun" -> readTrun(body, boxEnd)
                "stsd" -> if (asc == null) readStsd(body, boxEnd)
                "stsz" -> readStsz(body, boxEnd)
                "stsc" -> readStsc(body, boxEnd)
                "stco" -> readChunkOffsets(body, boxEnd, false)
                "co64" -> readChunkOffsets(body, boxEnd, true)
            }
            p = boxEnd
        }
    }

    private fun readTfhd(body: Int, end: Int, moofStart: Int) {
        if (body + 8 > end) return
        val flags = (u32(body) and 0xFFFFFF).toInt()
        var p = body + 8
        tfBase = moofStart.toLong()
        if (flags and 0x01 != 0) { tfBase = u64(p); p += 8 }
        if (flags and 0x02 != 0) p += 4
        if (flags and 0x08 != 0) p += 4
        if (flags and 0x10 != 0 && p + 4 <= end) defSize = u32(p).toInt()
    }

    private fun readTrun(body: Int, end: Int) {
        if (body + 8 > end) return
        val flags = (u32(body) and 0xFFFFFF).toInt()
        val count = u32(body + 4).toInt()
        var p = body + 8
        var cur: Long
        if (flags and 0x01 != 0) {
            cur = tfBase + u32(p).toInt() // signed
            p += 4
        } else cur = if (trunCur >= 0) trunCur else tfBase
        if (flags and 0x04 != 0) p += 4
        for (i in 0 until count) {
            if (p > end) return
            if (flags and 0x100 != 0) p += 4
            var sz = defSize
            if (flags and 0x200 != 0) { if (p + 4 > end) return; sz = u32(p).toInt(); p += 4 }
            if (flags and 0x400 != 0) p += 4
            if (flags and 0x800 != 0) p += 4
            if (sz > 0 && cur in 0..Int.MAX_VALUE.toLong()) samples.add(intArrayOf(cur.toInt(), sz))
            cur += sz
        }
        trunCur = cur
    }

    // stsd -> mp4a -> esds -> AudioSpecificConfig
    private fun readStsd(body: Int, end: Int) {
        if (body + 8 > end) return
        val entry = body + 8
        if (entry + 8 > end) return
        val entrySize = u32(entry).toInt()
        val entryEnd = minOf(end, entry + entrySize)
        var p = entry + 8 + 28
        while (p + 8 <= entryEnd) {
            val sz = u32(p).toInt()
            if (sz < 8) return
            val type = String(data, p + 4, 4, Charsets.ISO_8859_1)
            val boxEnd = minOf(entryEnd, p + sz)
            if (type == "esds") { readEsds(p + 8, boxEnd); return }
            p = boxEnd
        }
    }

    private fun readEsds(body: Int, end: Int) {
        var p = body + 4 // version/flags
        fun len(): Int {
            var n = 0
            for (i in 0 until 4) {
                if (p >= end) return n
                val b = data[p++].toInt() and 255
                n = (n shl 7) or (b and 0x7F)
                if (b and 0x80 == 0) break
            }
            return n
        }
        if (p >= end) return
        if (data[p].toInt() == 3) {
            p++; len(); p += 2
            val fl = data[p++].toInt() and 255
            if (fl and 0x80 != 0) p += 2
            if (fl and 0x40 != 0) p += 1 + (data[p].toInt() and 255)
            if (fl and 0x20 != 0) p += 2
        }
        if (p < end && data[p].toInt() == 4) { p++; len(); p += 13 }
        if (p < end && data[p].toInt() == 5) {
            p++
            val n = len()
            if (n in 1..64 && p + n <= end) asc = data.copyOfRange(p, p + n)
        }
    }

    private fun readStsz(body: Int, end: Int) {
        if (body + 12 > end) return
        val fixed = u32(body + 4).toInt()
        val count = u32(body + 8).toInt()
        if (count < 0 || count > 10_000_000) return
        val arr = IntArray(count)
        if (fixed != 0) arr.fill(fixed) else {
            var p = body + 12
            for (i in 0 until count) { if (p + 4 > end) return; arr[i] = u32(p).toInt(); p += 4 }
        }
        sizes = arr
    }

    private fun readStsc(body: Int, end: Int) {
        if (body + 8 > end) return
        val n = u32(body + 4).toInt()
        if (n < 0 || n > 1_000_000) return
        val first = IntArray(n); val per = IntArray(n)
        var p = body + 8
        for (i in 0 until n) {
            if (p + 12 > end) return
            first[i] = u32(p).toInt(); per[i] = u32(p + 4).toInt(); p += 12
        }
        stscFirst = first; stscCount = per
    }

    private fun readChunkOffsets(body: Int, end: Int, wide: Boolean) {
        if (body + 8 > end) return
        val n = u32(body + 4).toInt()
        if (n < 0 || n > 10_000_000) return
        val out = LongArray(n)
        var p = body + 8
        val step = if (wide) 8 else 4
        for (i in 0 until n) {
            if (p + step > end) return
            out[i] = if (wide) u64(p) else u32(p); p += step
        }
        chunkOffsets = out
    }

    private fun buildFromStbl() {
        val sz = sizes ?: return
        val first = stscFirst ?: return
        val per = stscCount ?: return
        val offs = chunkOffsets ?: return
        var si = 0
        var entry = 0
        for (c in offs.indices) {
            val chunk = c + 1
            while (entry + 1 < first.size && first[entry + 1] <= chunk) entry++
            var off = offs[c]
            for (k in 0 until per[entry]) {
                if (si >= sz.size) return
                if (off in 0..Int.MAX_VALUE.toLong() && sz[si] > 0) samples.add(intArrayOf(off.toInt(), sz[si]))
                off += sz[si]; si++
            }
        }
    }

    // ----------------------------------------------------------- decoder
    override fun read(): AudioDecoder.Frame? {
        while (idx < samples.size) {
            val s = samples[idx++]
            if (s[0] < 0 || s[1] <= 0 || s[0].toLong() + s[1] > data.size) continue
            try {
                dec.decodeFrame(data.copyOfRange(s[0], s[0] + s[1]), buf)
            } catch (_: Exception) {
                continue
            }
            val bytes = buf.data
            val n = bytes.size / 2
            if (n <= 0 || buf.sampleRate <= 0 || buf.channels <= 0) continue
            val out = ShortArray(n)
            for (i in 0 until n) out[i] = ((bytes[2 * i].toInt() and 255) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
            return AudioDecoder.Frame(out, n, buf.sampleRate, buf.channels)
        }
        return null
    }

    override fun close() {}
}
