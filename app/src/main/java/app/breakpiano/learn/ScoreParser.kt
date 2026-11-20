package app.breakpiano.learn

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** A song written out exactly — from an AI (Break song format), ABC notation, etc. */
data class Score(
    val title: String,
    val bpm: Float,
    val meterNum: Int,
    val meterDen: Int,
    val key: String,
    val notes: List<SongNote>,
    val warnings: List<String>,
    val format: String,
)

object ScoreParser {
    /** Reads whatever was pasted or imported: Break song JSON, or ABC notation. */
    fun parse(text: String): Score {
        val t = text.replace("\r", "").trim()
        require(t.isNotEmpty()) { "That file is empty." }
        val abc = Regex("(?m)^\\s*K:").containsMatchIn(t) && (Regex("(?m)^\\s*X:").containsMatchIn(t) || !t.contains('{'))
        val score = if (abc) AbcParser.parse(t) else BreakSongFormat.parse(t)
        require(score.notes.isNotEmpty()) { "No notes found in that file." }
        return score
    }

    // ── shared note-name reading ────────────────────────────────────────────

    private val PC = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)
    private val SOLFEGE = mapOf("DO" to 0, "RE" to 2, "RÉ" to 2, "MI" to 4, "FA" to 5, "SOL" to 7, "SO" to 7, "LA" to 9, "SI" to 11, "TI" to 11)
    private val NAME = Regex("^([A-Ga-g]|Do|DO|do|Re|RE|re|Ré|Mi|MI|mi|Fa|FA|fa|Sol|SOL|sol|So|so|La|LA|la|Si|SI|si|Ti|TI|ti)(##|bb|#|b|♯|♭|x)?(-?\\d)$")

    /** "C4" (middle C = 60), "F#3", "Bb5", "Eb4", also solfège "Sol4", "Re#4". */
    fun noteNumber(name: String): Int? {
        val m = NAME.matchEntire(name.trim()) ?: return null
        val base = m.groupValues[1]
        val pc = if (base.length == 1) PC[base[0].uppercaseChar()] ?: return null else SOLFEGE[base.uppercase()] ?: return null
        val acc = when (m.groupValues[2]) { "#", "♯" -> 1; "##", "x" -> 2; "b", "♭" -> -1; "bb" -> -2; else -> 0 }
        val oct = m.groupValues[3].toInt()
        val n = 12 * (oct + 1) + pc + acc
        return if (n in 0..127) n else null
    }

    fun parseMeter(s: String): Pair<Int, Int> {
        val t = s.trim()
        if (t == "C") return 4 to 4
        if (t == "C|") return 2 to 2
        val m = Regex("(\\d+)\\s*/\\s*(\\d+)").find(t) ?: return 4 to 4
        return m.groupValues[1].toInt().coerceIn(1, 32) to m.groupValues[2].toInt().coerceIn(1, 32)
    }
}

/**
 * The Break song format — simple enough for any AI to write exactly:
 * {
 *   "format": "break-song", "title": "...", "tempo": 100, "timeSignature": "4/4", "key": "C major",
 *   "right": "C4:1 C4:1 G4:1 G4:1 | A4:1 A4:1 G4:2 |",
 *   "left":  "C3+E3+G3:4 | F3+A3+C4:2 C3+E3+G3:2 |"
 * }
 * Lengths are in beats (1 = quarter note); R = rest; + joins a chord; | separates bars; ~ ties into the next note.
 */
object BreakSongFormat {
    fun parse(text: String): Score {
        val json = JSONObject(extractJson(text))
        val title = json.optString("title").ifBlank { json.optString("name").ifBlank { "My song" } }
        val bpm = (if (json.has("tempo")) json.optDouble("tempo", 100.0) else json.optDouble("bpm", 100.0)).toFloat().coerceIn(20f, 320f)
        val (num, den) = ScoreParser.parseMeter(json.optString("timeSignature", json.optString("time", json.optString("meter", "4/4"))))
        val key = json.optString("key")
        val warnings = ArrayList<String>()
        val notes = ArrayList<SongNote>()
        val beatsPerBar = num * 4.0 / den
        fun part(field: String, hand: Int, label: String) {
            val v = json.opt(field) ?: return
            val txt = when (v) {
                is JSONArray -> (0 until v.length()).joinToString(" | ") { v.optString(it) }
                else -> v.toString()
            }
            notes += parsePart(txt, hand, bpm, beatsPerBar, label, warnings)
        }
        part("right", 0, "Right hand")
        part("melody", 0, "Melody")
        part("left", 1, "Left hand")
        json.optJSONArray("parts")?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val hand = if (p.optString("hand").lowercase().startsWith("l")) 1 else 0
                val v = p.opt("notes") ?: continue
                val txt = if (v is JSONArray) (0 until v.length()).joinToString(" | ") { v.optString(it) } else v.toString()
                notes += parsePart(txt, hand, bpm, beatsPerBar, if (hand == 1) "Left hand" else "Right hand", warnings)
            }
        }
        return Score(title, bpm, num, den, key, notes.sortedWith(compareBy({ it.start }, { it.pitch })), warnings, "Break song")
    }

    /** Pulls the JSON object out of an AI reply (code fences, chatter, comments, trailing commas). */
    internal fun extractJson(text: String): String {
        val a = text.indexOf('{'); val b = text.lastIndexOf('}')
        require(a >= 0 && b > a) { "That file isn't a Break song or ABC song." }
        var s = text.substring(a, b + 1)
        s = s.lines().joinToString("\n") { line ->
            // drop // comments that aren't inside a string
            var inStr = false; var cut = -1
            var i = 0
            while (i < line.length) {
                val c = line[i]
                if (c == '"' && (i == 0 || line[i - 1] != '\\')) inStr = !inStr
                if (!inStr && c == '/' && i + 1 < line.length && line[i + 1] == '/') { cut = i; break }
                i++
            }
            if (cut >= 0) line.substring(0, cut) else line
        }
        s = s.replace(Regex(",\\s*([}\\]])"), "$1")
        s = s.replace('“', '"').replace('”', '"')
        return s
    }

    private fun parseLen(s: String): Double? {
        val t = s.trim()
        if (t.isEmpty()) return 1.0
        if ('/' in t) {
            val (a, b) = t.split('/', limit = 2)
            val n = if (a.isBlank()) 1.0 else a.toDoubleOrNull() ?: return null
            val d = b.toDoubleOrNull() ?: return null
            return if (d > 0) n / d else null
        }
        return t.toDoubleOrNull()
    }

    internal fun parsePart(text: String, hand: Int, bpm: Float, beatsPerBar: Double, label: String, warnings: MutableList<String>): List<SongNote> {
        val spb = 60.0 / bpm
        val out = ArrayList<SongNote>()
        val tokens = text.replace("|", " | ").split(Regex("\\s+")).filter { it.isNotBlank() }
        var cursor = 0.0
        var barStart = 0.0
        var bar = 1
        val tied = HashMap<Int, Int>()          // pitch → index in out of a note tied into the next
        var bad = 0
        for (tok in tokens) {
            if (tok == "|" || tok == "||" || tok == "|]") {
                val got = cursor - barStart
                if (got > 1e-6 && abs(got - beatsPerBar) > 0.02 && !(bar == 1 && got < beatsPerBar)) {
                    warnings += "$label bar $bar has ${fmt(got)} beats (expected ${fmt(beatsPerBar)})"
                }
                if (got > 1e-6) bar++
                barStart = cursor
                continue
            }
            val tie = tok.endsWith("~")
            val body = tok.removeSuffix("~")
            val colon = body.lastIndexOf(':')
            val pitchPart = if (colon >= 0) body.substring(0, colon) else body
            val len = parseLen(if (colon >= 0) body.substring(colon + 1) else "")
            if (len == null || len <= 0) { bad++; continue }
            if (pitchPart.uppercase() in setOf("R", "REST", "-", "_", "Z")) { cursor += len; continue }
            val pitches = pitchPart.split('+').map { ScoreParser.noteNumber(it) }
            if (pitches.any { it == null }) { bad++; cursor += len; continue }
            val start = (cursor * spb).toFloat()
            val dur = (len * spb).toFloat()
            for (p in pitches.filterNotNull()) {
                val prev = tied.remove(p)
                if (prev != null && abs(out[prev].end - start) < 0.06f) {
                    out[prev] = out[prev].copy(end = start + dur - gapFor(dur))
                    if (tie) tied[p] = prev
                    continue
                }
                out += SongNote(start, start + dur - gapFor(dur), p, 0.8f, hand)
                if (tie) tied[p] = out.lastIndex
            }
            cursor += len
        }
        if (bad > 0) warnings += "$label: skipped $bad unreadable note${if (bad > 1) "s" else ""}"
        return out
    }

    /** A hair of silence between notes so repeated keys read as separate presses. */
    private fun gapFor(dur: Float) = minOf(0.03f, dur * 0.1f)
    private fun fmt(v: Double) = if (abs(v - v.toLong()) < 1e-6) v.toLong().toString() else "%.2f".format(v)
}

/**
 * ABC notation (the text music format most AIs know): header fields X T M L Q K V, notes with
 * accidentals, octaves and lengths, chords, rests, ties, broken rhythm, triplets, repeats with
 * 1st/2nd endings, multiple voices (first voice = right hand, others = left hand).
 */
object AbcParser {
    private sealed interface Tok
    private class NoteTok(val pitches: List<Int>, var len: Double, var tie: Boolean = false) : Tok
    private class RestTok(var len: Double) : Tok
    private class BarTok(val repStart: Boolean, val repEnd: Boolean) : Tok
    private class EndingTok(val n: Int) : Tok

    private class Voice(val hand: Int) {
        val toks = ArrayList<Tok>()
        val barAcc = HashMap<Int, Int>()          // (letter index * 16 + octave) → accidental
    }

    private val SHARPS = "FCGDAEB"
    private val FLATS = "BEADGCF"

    fun parse(text: String): Score {
        var title = ""
        var meter = 4 to 4
        var unit = -1.0
        var bpm = 0f; var beatUnit = 0.25
        var keyName = ""
        var keySig = IntArray(7)                      // per letter C D E F G A B: −1/0/+1
        val voices = LinkedHashMap<String, Voice>()
        var cur: Voice? = null
        var inBody = false
        val warnings = ArrayList<String>()

        // first voice is the right hand; later voices, or any marked bass / left, are the left hand
        fun voice(id: String, hint: String = ""): Voice = voices.getOrPut(id) {
            val marked = Regex("(?i)bass|left|\\blh\\b").containsMatchIn(hint)
            Voice(if (marked || voices.isNotEmpty()) 1 else 0)
        }

        fun applyField(f: Char, v: String) {
            when (f) {
                'T' -> if (title.isEmpty()) title = v.trim()
                'M' -> meter = ScoreParser.parseMeter(v)
                'L' -> Regex("(\\d+)\\s*/\\s*(\\d+)").find(v)?.let { unit = it.groupValues[1].toDouble() / it.groupValues[2].toDouble() }
                'Q' -> {
                    val m = Regex("(\\d+)\\s*/\\s*(\\d+)\\s*=\\s*(\\d+)").find(v)
                    if (m != null) { beatUnit = m.groupValues[1].toDouble() / m.groupValues[2].toDouble(); bpm = m.groupValues[3].toFloat() }
                    else Regex("(\\d+)").find(v)?.let { bpm = it.groupValues[1].toFloat(); beatUnit = 0.25 }
                }
                'K' -> { val (sig, name) = keySignature(v); keySig = sig; keyName = name; inBody = true }
                'V' -> { val id = v.trim().split(Regex("\\s+")).firstOrNull() ?: "1"; cur = voice(id, v) }
            }
        }

        for (raw in text.lines()) {
            val line = raw.substringBefore('%').trimEnd()
            val field = Regex("^\\s*([A-Za-z]):(.*)$").find(line)
            if (field != null && field.groupValues[1].length == 1 && field.groupValues[1][0] !in "|") {
                val f = field.groupValues[1][0]
                if (f == 'w' || f == 'W') continue
                applyField(f, field.groupValues[2])
                continue
            }
            if (!inBody || line.isBlank()) continue
            if (unit < 0) unit = if (meter.first.toDouble() / meter.second < 0.75) 1.0 / 16 else 1.0 / 8
            val v = cur ?: voice("1").also { cur = it }
            tokenize(line, v, { keySig }, { unit }, { meter.first.toDouble() / meter.second }, { f, value -> applyField(f, value); cur ?: v })
        }
        if (unit < 0) unit = 1.0 / 8
        if (bpm <= 0f) { bpm = 100f; beatUnit = 0.25 }
        val secPerWhole = 60.0 / bpm / beatUnit
        val notes = ArrayList<SongNote>()
        for (v in voices.values) notes += render(expandRepeats(v.toks), v.hand, secPerWhole)
        return Score(title.ifBlank { "ABC song" }, (bpm * (beatUnit / 0.25)).toFloat(), meter.first, meter.second, keyName,
            notes.sortedWith(compareBy({ it.start }, { it.pitch })), warnings, "ABC")
    }

    /** Key field → accidentals per letter (C D E F G A B) and a readable name. */
    private fun keySignature(v: String): Pair<IntArray, String> {
        val sig = IntArray(7)
        val t = v.trim().split(Regex("\\s+")).firstOrNull()?.trim() ?: return sig to ""
        if (t.equals("none", true) || t.isEmpty()) return sig to ""
        val m = Regex("^([A-Ga-g])([#b]?)(.*)$").find(t) ?: return sig to t
        val tonic = m.groupValues[1].uppercase() + m.groupValues[2]
        val mode = m.groupValues[3].lowercase()
        val fifths = mapOf("C" to 0, "G" to 1, "D" to 2, "A" to 3, "E" to 4, "B" to 5, "F#" to 6, "C#" to 7,
            "F" to -1, "Bb" to -2, "Eb" to -3, "Ab" to -4, "Db" to -5, "Gb" to -6, "Cb" to -7,
            "G#" to 8, "D#" to 9, "A#" to 10, "E#" to 11, "B#" to 12, "Fb" to -8)[tonic] ?: 0
        val modeOff = when {
            mode.startsWith("min") || mode == "m" || mode.startsWith("aeo") -> -3
            mode.startsWith("mix") -> -1
            mode.startsWith("dor") -> -2
            mode.startsWith("phr") -> -4
            mode.startsWith("loc") -> -5
            mode.startsWith("lyd") -> 1
            else -> 0
        }
        val n = (fifths + modeOff).coerceIn(-7, 7)
        val letters = "CDEFGAB"
        if (n > 0) for (i in 0 until n) sig[letters.indexOf(SHARPS[i])] = 1
        if (n < 0) for (i in 0 until -n) sig[letters.indexOf(FLATS[i])] = -1
        val name = tonic + when (modeOff) { -3 -> " minor"; 0 -> " major"; else -> " " + mode }
        return sig to name
    }

    private fun tokenize(
        line: String, voiceIn: Voice, keySig: () -> IntArray, unit: () -> Double, barLen: () -> Double,
        inlineField: (Char, String) -> Voice,
    ) {
        var v = voiceIn
        var i = 0
        val s = line
        var tuplet = 0; var tupletRatio = 1.0
        var broken = 0                              // pending broken-rhythm factor for the next note: +1 '>' / -1 '<'
        var brokenDepth = 1
        fun readLen(): Double {
            var num = 0; var hasNum = false
            while (i < s.length && s[i].isDigit()) { num = num * 10 + (s[i] - '0'); i++; hasNum = true }
            var value = if (hasNum) num.toDouble() else 1.0
            while (i < s.length && s[i] == '/') {
                i++
                var den = 0; var hasDen = false
                while (i < s.length && s[i].isDigit()) { den = den * 10 + (s[i] - '0'); i++; hasDen = true }
                value /= if (hasDen && den > 0) den.toDouble() else 2.0
            }
            return value
        }
        fun readPitch(): Int? {
            var acc: Int? = null
            while (i < s.length && s[i] in "^_=") {
                acc = (acc ?: 0) + when (s[i]) { '^' -> 1; '_' -> -1; else -> 0 }
                if (s[i] == '=') acc = 0
                i++
            }
            if (i >= s.length || s[i] !in "ABCDEFGabcdefg") return null
            val c = s[i]; i++
            val letters = "CDEFGAB"
            val li = letters.indexOf(c.uppercaseChar())
            var oct = if (c.isUpperCase()) 4 else 5
            while (i < s.length && (s[i] == '\'' || s[i] == ',')) { if (s[i] == '\'') oct++ else oct--; i++ }
            val keyId = li * 16 + oct
            val a = if (acc != null) { v.barAcc[keyId] = acc; acc } else v.barAcc[keyId] ?: keySig()[li]
            return 12 * (oct + 1) + intArrayOf(0, 2, 4, 5, 7, 9, 11)[li] + a
        }
        fun addNote(t: Tok) {
            var mult = 1.0
            if (tuplet > 0) { mult *= tupletRatio; tuplet-- }
            if (broken != 0) {
                val f = 1.0 - Math.pow(0.5, brokenDepth.toDouble())
                mult *= if (broken > 0) (1 - f) else (1 + f)
                broken = 0
            }
            when (t) { is NoteTok -> t.len *= mult; is RestTok -> t.len *= mult; else -> {} }
            v.toks += t
        }
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> { val e = s.indexOf('"', i + 1); i = if (e < 0) s.length else e + 1 }
                c == '!' -> { val e = s.indexOf('!', i + 1); i = if (e < 0) s.length else e + 1 }
                c == '{' -> { val e = s.indexOf('}', i + 1); i = if (e < 0) s.length else e + 1 }
                c == '[' && i + 2 < s.length && s[i + 1].isLetter() && s[i + 2] == ':' -> {
                    val e = s.indexOf(']', i); val body = s.substring(i + 3, if (e < 0) s.length else e)
                    v = inlineField(s[i + 1], body)
                    i = if (e < 0) s.length else e + 1
                }
                c == '[' && i + 1 < s.length && s[i + 1].isDigit() -> { v.toks += EndingTok(s[i + 1] - '0'); i += 2 }
                c == '[' && i + 1 < s.length && s[i + 1] == '|' -> { v.toks += BarTok(false, false); v.barAcc.clear(); i += 2 }
                c == '[' -> {                                   // chord
                    i++
                    val ps = ArrayList<Int>(); var firstLen = 1.0
                    while (i < s.length && s[i] != ']') {
                        val p = readPitch()
                        if (p != null) { val l = readLen(); if (ps.isEmpty()) firstLen = l; ps += p }
                        else i++
                        while (i < s.length && s[i] == '-') i++
                    }
                    i++
                    val l = readLen()
                    var tie = false
                    if (i < s.length && s[i] == '-') { tie = true; i++ }
                    if (ps.isNotEmpty()) addNote(NoteTok(ps, firstLen * l * unit(), tie))
                }
                c == '|' || c == ':' -> {
                    val st = i
                    while (i < s.length && (s[i] == '|' || s[i] == ':' || s[i] == ']')) i++
                    val bar = s.substring(st, i)
                    val repEnd = bar.startsWith(":")
                    val repStart = bar.endsWith(":")
                    v.toks += BarTok(repStart, repEnd)
                    v.barAcc.clear()
                    if (i < s.length && s[i].isDigit()) { v.toks += EndingTok(s[i] - '0'); i++ }
                }
                c == '(' -> {
                    if (i + 1 < s.length && s[i + 1].isDigit()) {
                        val p = s[i + 1] - '0'
                        i += 2
                        val q = when (p) { 2, 4, 8 -> 3; 3, 6 -> 2; else -> 2 }
                        tuplet = p; tupletRatio = q.toDouble() / p
                        while (i < s.length && (s[i] == ':' || s[i].isDigit())) i++
                    } else i++
                }
                c == '>' || c == '<' -> {
                    var d = 0; val dir = if (c == '>') 1 else -1
                    while (i < s.length && s[i] == c) { d++; i++ }
                    val last = v.toks.lastOrNull()
                    val f = 1.0 - Math.pow(0.5, d.toDouble())
                    when (last) {
                        is NoteTok -> last.len *= if (dir > 0) (1 + f) else (1 - f)
                        is RestTok -> last.len *= if (dir > 0) (1 + f) else (1 - f)
                        else -> {}
                    }
                    broken = dir; brokenDepth = d
                }
                c == 'z' || c == 'x' -> { i++; addNote(RestTok(readLen() * unit())) }
                c == 'Z' -> {
                    i++; val bars = readLen()
                    v.toks += RestTok(bars * barLen())
                }
                c in "^_=ABCDEFGabcdefg" -> {
                    val p = readPitch()
                    if (p == null) { i++; continue }
                    val l = readLen()
                    var tie = false
                    if (i < s.length && s[i] == '-') { tie = true; i++ }
                    addNote(NoteTok(listOf(p), l * unit(), tie))
                }
                else -> i++                                      // spaces, beams, decorations, slurs, etc.
            }
        }
    }

    /** |: … :| plays twice; on the second time through, a [1 ending is replaced by the [2 ending. */
    private fun expandRepeats(toks: List<Tok>): List<Tok> {
        val out = ArrayList<Tok>()
        var start = 0
        var i = 0
        while (i < toks.size) {
            val t = toks[i]
            if (t is BarTok && t.repEnd) {
                var skip = false
                for (k in start until i) {
                    val x = toks[k]
                    if (x is EndingTok) { skip = x.n == 1; continue }
                    if (!skip) out += x
                }
                out += BarTok(false, false)
                if (t.repStart) start = i + 1
                // first pass already emitted; continue after the repeat (skip [1 handled above)
                i++
                if (!t.repStart) start = i
                continue
            }
            if (t is BarTok && t.repStart) { out += t; start = i + 1; i++; continue }
            if (t is EndingTok) { i++; continue }
            out += t
            i++
        }
        return out
    }

    private fun render(toks: List<Tok>, hand: Int, secPerWhole: Double): List<SongNote> {
        val out = ArrayList<SongNote>()
        var cursor = 0.0
        val tied = HashMap<Int, Int>()
        for (t in toks) when (t) {
            is RestTok -> cursor += t.len
            is NoteTok -> {
                val start = (cursor * secPerWhole).toFloat()
                val dur = (t.len * secPerWhole).toFloat()
                for (p in t.pitches) {
                    val prev = tied.remove(p)
                    if (prev != null && abs(out[prev].end - start) < 0.06f) {
                        out[prev] = out[prev].copy(end = start + dur - minOf(0.03f, dur * 0.1f))
                        if (t.tie) tied[p] = prev
                        continue
                    }
                    out += SongNote(start, start + dur - minOf(0.03f, dur * 0.1f), p, 0.8f, hand)
                    if (t.tie) tied[p] = out.lastIndex
                }
                cursor += t.len
            }
            else -> {}
        }
        return out
    }
}
