package app.breakpiano.learn

/** Minimal Standard MIDI File (format 0/1) reader → notes in seconds. Drums (channel 10) are skipped. */
object MidiFile {
    private class Reader(val b: ByteArray, var p: Int = 0) {
        fun u8() = b[p++].toInt() and 0xff
        fun u16() = (u8() shl 8) or u8()
        fun u32() = (u16() shl 16) or u16()
        fun vlq(): Int { var v = 0; while (true) { val c = u8(); v = (v shl 7) or (c and 0x7f); if (c and 0x80 == 0) return v } }
        fun tag() = String(b, p, 4, Charsets.US_ASCII).also { p += 4 }
    }

    fun parse(bytes: ByteArray): List<SongNote> {
        val r = Reader(bytes)
        require(r.tag() == "MThd") { "Not a MIDI file." }
        val hdrLen = r.u32()
        r.u16() // format
        val tracks = r.u16()
        val division = r.u16()
        r.p = 8 + hdrLen
        val smpte = division and 0x8000 != 0
        val tpq = if (smpte) 480 else division
        val smpteSecPerTick = if (smpte) {
            val fps = -(division shr 8).toByte().toInt()
            val sub = division and 0xff
            1.0 / (fps.coerceAtLeast(1) * sub.coerceAtLeast(1))
        } else 0.0

        data class Raw(val tick: Long, val on: Boolean, val ch: Int, val note: Int, val vel: Int)
        val raws = ArrayList<Raw>()
        val tempos = ArrayList<Pair<Long, Int>>() // tick → µs per quarter

        repeat(tracks) {
            if (r.p + 8 > bytes.size) return@repeat
            val tag = r.tag(); val len = r.u32(); val end = r.p + len
            if (tag != "MTrk") { r.p = end; return@repeat }
            var tick = 0L; var status = 0
            while (r.p < end) {
                tick += r.vlq()
                var b = r.u8()
                if (b < 0x80) { r.p--; b = status } else if (b < 0xF0) status = b
                when {
                    b == 0xFF -> {
                        val type = r.u8(); val l = r.vlq()
                        if (type == 0x51 && l == 3) tempos += tick to ((r.u8() shl 16) or (r.u8() shl 8) or r.u8())
                        else r.p += l
                    }
                    b == 0xF0 || b == 0xF7 -> r.p += r.vlq()
                    else -> {
                        val ch = b and 0x0f
                        when (b and 0xf0) {
                            0x80 -> { val n = r.u8(); r.u8(); raws += Raw(tick, false, ch, n, 0) }
                            0x90 -> { val n = r.u8(); val v = r.u8(); raws += Raw(tick, v > 0, ch, n, v) }
                            0xA0, 0xB0, 0xE0 -> r.p += 2
                            0xC0, 0xD0 -> r.p += 1
                            else -> r.p = end
                        }
                    }
                }
            }
            r.p = end
        }

        tempos.sortBy { it.first }
        if (tempos.isEmpty() || tempos[0].first > 0) tempos.add(0, 0L to 500_000)
        // precompute seconds at each tempo change
        val tempoSec = DoubleArray(tempos.size)
        for (i in 1 until tempos.size) {
            val dt = tempos[i].first - tempos[i - 1].first
            tempoSec[i] = tempoSec[i - 1] + dt * tempos[i - 1].second / 1e6 / tpq
        }
        fun secOf(tick: Long): Float {
            if (smpte) return (tick * smpteSecPerTick).toFloat()
            var i = tempos.binarySearch { it.first.compareTo(tick) }
            if (i < 0) i = -i - 2
            i = i.coerceIn(0, tempos.lastIndex)
            return (tempoSec[i] + (tick - tempos[i].first) * tempos[i].second / 1e6 / tpq).toFloat()
        }

        raws.sortBy { it.tick }
        val open = HashMap<Int, ArrayDeque<Raw>>()
        val notes = ArrayList<SongNote>()
        for (e in raws) {
            if (e.ch == 9) continue
            val key = e.ch * 128 + e.note
            if (e.on) open.getOrPut(key) { ArrayDeque() }.addLast(e)
            else {
                val s = open[key]?.removeFirstOrNull() ?: continue
                val start = secOf(s.tick); val stop = secOf(e.tick)
                if (stop > start && e.note in 21..108) notes += SongNote(start, stop, e.note, (s.vel / 127f).coerceIn(0.2f, 1f))
            }
        }
        require(notes.isNotEmpty()) { "That MIDI file has no playable notes." }
        return notes.sortedWith(compareBy({ it.start }, { it.pitch }))
    }
}
