package app.breakpiano.audio

import androidx.compose.ui.graphics.Color
import kotlin.random.Random

/**
 * Built-in groove library. Patterns are written as step grids:
 *   'X' accent · 'x' normal · 'o' soft · 'g' ghost · '.' rest     (spaces and '|' are ignored)
 * Bass lines are whitespace-separated tokens per step:
 *   number = semitones from the root · 'g' suffix = glide into it · '~' = hold · '-' = rest
 * A track shorter than the pattern simply repeats.
 */
object Drum {
    const val KICK = 36
    const val RIM = 37
    const val SNARE = 38
    const val CLAP = 39
    const val SNARE2 = 40
    const val FTOM = 41
    const val CHH = 42
    const val LTOM = 45
    const val PHH = 44
    const val OHH = 46
    const val MTOM = 47
    const val CRASH = 49
    const val HTOM = 50
    const val RIDE = 51
    const val BELL = 53
    const val TAMB = 54
    const val COWBELL = 56
    const val HBONGO = 60
    const val LBONGO = 61
    const val MCONGA = 62
    const val OCONGA = 63
    const val LCONGA = 64
    const val SHAKER = 70
    const val CLAVES = 75
}

object Kit {
    const val STANDARD = 0
    const val STANDARD2 = 1
    const val ROOM = 8
    const val POWER = 16
    const val ELECTRONIC = 24
    const val TR808 = 25
    const val DANCE = 26
    const val JAZZ = 32
    const val BRUSH = 40
}

const val TICKS_PER_BAR = 384
const val S8 = 48
const val S16 = 24
const val S32 = 12
const val T8 = 32   // 8th-note triplets (12 per bar)
const val T16 = 16  // 16th-note triplets (24 per bar)

sealed interface Track {
    data class Hits(val note: Int, val grid: String, val step: Int, val len: Int) : Track
    data class Bass(val line: String, val step: Int) : Track
}

class PatternSpec(val bars: Int, val tracks: List<Track>)

class PatternBuilder {
    val tracks = mutableListOf<Track>()
    fun hit(note: Int, grid: String, step: Int = S16, len: Int = 48) {
        tracks += Track.Hits(note, grid, step, len)
    }
    fun bass(line: String, step: Int = S16) {
        tracks += Track.Bass(line, step)
    }
}

fun pattern(bars: Int = 1, block: PatternBuilder.() -> Unit) =
    PatternSpec(bars, PatternBuilder().apply(block).tracks)

/** bassMode: -1 none · 0 long 808 · 1 punchy 808 · 2 log drum · 3 soundfont bass (bassPreset) */
data class BeatStyle(
    val id: String,
    val name: String,
    val vibe: String,
    val bpm: Int,
    val kit: Int,
    val color: Color,
    val swing: Int = 50,
    val bassMode: Int = -1,
    val bassPreset: Int = 33,
    val variations: List<PatternSpec>,
) {
    val hasBass get() = bassMode >= 0
}

class CompiledPattern(
    val lengthTicks: Int,
    val ticks: IntArray,
    val kinds: ByteArray,
    val notes: ByteArray,
    val vels: ByteArray,
    val glides: ByteArray,
    val lens: IntArray,
)

object BeatCompiler {
    private fun velOf(c: Char) = when (c) {
        'X' -> 124; 'x' -> 100; 'o' -> 74; 'g' -> 46; else -> 0
    }

    fun compile(style: BeatStyle, variation: Int): CompiledPattern {
        val spec = style.variations[variation.coerceIn(0, style.variations.lastIndex)]
        val length = spec.bars * TICKS_PER_BAR
        // MPC-style swing: offbeat 16ths move from 50% → `swing`% of the 8th.
        val swingShift = ((style.swing / 100.0) * S8 - S16).toInt()
        val rnd = Random(style.id.hashCode() * 31 + variation)

        data class Ev(val tick: Int, val kind: Int, val note: Int, val vel: Int, val glide: Int, var len: Int)
        val out = ArrayList<Ev>()

        fun place(idx: Int, step: Int): Int {
            var t = idx * step
            if (step == S16 && idx % 2 == 1) t += swingShift
            return t
        }

        for (track in spec.tracks) {
            when (track) {
                is Track.Hits -> {
                    val cells = track.grid.filter { it != ' ' && it != '|' }
                    if (cells.isEmpty()) continue
                    val n = length / track.step
                    for (idx in 0 until n) {
                        val v = velOf(cells[idx % cells.length])
                        if (v == 0) continue
                        val human = (v + rnd.nextInt(-5, 6)).coerceIn(1, 127)
                        out += Ev(place(idx, track.step), 0, track.note, human, 0, track.len)
                    }
                }
                is Track.Bass -> {
                    if (!style.hasBass) continue
                    val toks = track.line.split(' ', '|', '\n').filter { it.isNotBlank() }
                    if (toks.isEmpty()) continue
                    val n = length / track.step
                    var last: Ev? = null
                    for (idx in 0 until n) {
                        val tok = toks[idx % toks.size]
                        when (tok) {
                            "~" -> last?.let { it.len += track.step }
                            "-" -> last = null
                            else -> {
                                val glide = tok.endsWith("g")
                                val semis = tok.removeSuffix("g").toIntOrNull() ?: continue
                                val e = Ev(place(idx, track.step), 1, semis, 110, if (glide) 1 else 0, track.step)
                                out += e
                                last = e
                            }
                        }
                    }
                }
            }
        }
        out.sortBy { it.tick }
        return CompiledPattern(
            lengthTicks = length,
            ticks = IntArray(out.size) { out[it].tick },
            kinds = ByteArray(out.size) { out[it].kind.toByte() },
            notes = ByteArray(out.size) { out[it].note.toByte() },
            vels = ByteArray(out.size) { out[it].vel.toByte() },
            glides = ByteArray(out.size) { out[it].glide.toByte() },
            lens = IntArray(out.size) { out[it].len },
        )
    }
}

object Beats {
    val styles: List<BeatStyle> = listOf(
        BeatStyle(
            "gospel", "Gospel Praise", "Church shout · ghost notes", 112, Kit.STANDARD, Color(0xFFFFB547), swing = 56,
            variations = listOf(
                pattern(2) {
                    hit(Drum.KICK, "X......x..x..... | X......x..x..x..")
                    hit(Drum.SNARE, "..g.X..g....X.g. | ..g.X..g...gX.gg")
                    hit(Drum.CHH, "x.xox.xox.xox.xo | x.xox.xox.xo....")
                    hit(Drum.OHH, "................ | ............x...")
                    hit(Drum.TAMB, "....x.......x...")
                },
                pattern(2) {
                    hit(Drum.KICK, "X..x..x.x.....x. | X..x..x...x.x.x.")
                    hit(Drum.SNARE, "..g.X.gg..g.X.g. | ..g.X.gg..g.XoXX")
                    hit(Drum.RIDE, "x.xox.xox.xox.xo")
                    hit(Drum.CRASH, "X............... | ................", len = 192)
                    hit(Drum.TAMB, "..x.x...x.x.x.x.")
                },
            ),
        ),
        BeatStyle(
            "worship", "Worship 6/8", "Slow triplet sway", 64, Kit.ROOM, Color(0xFFE9C46A),
            variations = listOf(
                pattern(2) {
                    hit(Drum.KICK, "x.....x..... | x.....x..o..", step = T8)
                    hit(Drum.RIM, "...x.....x..", step = T8)
                    hit(Drum.CHH, "xooxooxooxoo", step = T8)
                },
                pattern(2) {
                    hit(Drum.KICK, "x.....x..... | x.....x..x..", step = T8)
                    hit(Drum.SNARE, "...x.....x.. | ...x.....xoo", step = T8)
                    hit(Drum.RIDE, "xooxooxooxoo", step = T8)
                    hit(Drum.CRASH, "x........... | ............", step = T8, len = 192)
                },
            ),
        ),
        BeatStyle(
            "afrobeats", "Afrobeats", "Lagos bounce · shakers", 104, Kit.STANDARD, Color(0xFF8FF0C4), swing = 53,
            bassMode = 1,
            variations = listOf(
                pattern(2) {
                    hit(Drum.KICK, "X.....x.X.....x. | X.....x.X..x..x.")
                    hit(Drum.RIM, "...x..x....x..x.")
                    hit(Drum.CLAP, "....o.......o...")
                    hit(Drum.SHAKER, "xoxoxoxoxoxoxoxo")
                    hit(Drum.OCONGA, ".......x..x.x... | .......x..x...x.")
                    bass("0 ~ ~ ~ ~ ~ 0 ~ 0 ~ ~ ~ ~ ~ 7 ~ | 0 ~ ~ ~ ~ ~ 0 ~ 0 ~ ~ 0 ~ ~ 7 ~")
                },
                pattern(2) {
                    hit(Drum.KICK, "X..x..x.X..x..x.")
                    hit(Drum.RIM, "...x..x....x.x..")
                    hit(Drum.CLAP, "....x.......x...")
                    hit(Drum.SHAKER, "xoxoxoxoxoxoxoxo")
                    hit(Drum.OCONGA, "..x..x.x..x..x.x")
                    hit(Drum.LCONGA, "x.......x.......")
                    bass("0 ~ ~ 0 ~ ~ 0 ~ 0 ~ ~ 0 ~ ~ 7 ~ | 0 ~ ~ 0 ~ ~ 0 ~ 0 ~ ~ 12 ~ ~ 7 ~")
                },
            ),
        ),
        BeatStyle(
            "amapiano", "Amapiano", "Log drums · deep groove", 112, Kit.STANDARD, Color(0xFF6BE3FF), swing = 55,
            bassMode = 2,
            variations = listOf(
                pattern(2) {
                    hit(Drum.KICK, "X...o...X...o...")
                    hit(Drum.SHAKER, "xoxoxoxoxoxoxoxo")
                    hit(Drum.RIM, "..x...x...x..x..")
                    hit(Drum.CLAP, "....x.......x...")
                    hit(Drum.CHH, "..o...o...o...o.")
                    bass("- - - 12 - - 12 - - - 12 - 12 ~ - - | - - - 12 - - 12 - - - 19 - 17 - 15 -")
                },
                pattern(2) {
                    hit(Drum.KICK, "X...x...X...x...")
                    hit(Drum.SHAKER, "xoxoxoxoxoxoxoxo")
                    hit(Drum.RIM, "..x..x....x..x.x")
                    hit(Drum.CLAP, "....x.......x...")
                    hit(Drum.OHH, "..x...x...x...x.")
                    hit(Drum.LCONGA, "......x.......x.")
                    bass("12 - - 12 - - 12 - - 12 - - 15 - 12 - | 12 - - 12 - - 12 - - 19 - 17 - 15 - 12")
                },
            ),
        ),
        BeatStyle(
            "trap", "Trap", "808 slides · hi-hat rolls", 140, Kit.TR808, Color(0xFFFF5A3C),
            bassMode = 0,
            variations = listOf(
                pattern(2) {
                    hit(Drum.KICK, "X......x..X..... | X.....x.....x.x.")
                    hit(Drum.CLAP, "........X.......")
                    hit(Drum.CHH, "x...x...x...x...x...x...x.x.x.x. x...x...x...x...x.x.x.x.x...xxxx", step = S32, len = 24)
                    hit(Drum.OHH, "................ | ..............x.")
                    bass("0 ~ ~ ~ ~ ~ ~ 0 ~ ~ 0 ~ ~ ~ ~ ~ | 0 ~ ~ ~ ~ ~ -2 ~ ~ ~ ~ ~ 3g ~ 0g ~")
                },
                pattern(2) {
                    hit(Drum.KICK, "X.....x...X..x.. | X.....x...x...x.")
                    hit(Drum.CLAP, "........X....... | ........X.....o.")
                    hit(Drum.CHH, "x..x..x..x..x..x..x..x.. | x..x..x..x..xxx.xxx.xxxx", step = T16, len = 24)
                    hit(Drum.RIM, "...........o....")
                    bass("0 ~ ~ ~ ~ ~ 0 ~ ~ ~ 0 ~ ~ 12g ~ ~ | 0 ~ ~ ~ ~ ~ 3 ~ ~ ~ 5g ~ ~ ~ 3g ~")
                },
            ),
        ),
        BeatStyle(
            "drill", "Drill", "Sliding 808s · syncopated hats", 142, Kit.TR808, Color(0xFFB23AFF),
            bassMode = 0,
            variations = listOf(
                pattern(2) {
                    hit(Drum.KICK, "X.........X..... | ..X.......X.....")
                    hit(Drum.SNARE, "........X....... | ........X....X..")
                    hit(Drum.CHH, "x..x..x...x..x.. | x..x..x...x.x.x.", len = 24)
                    hit(Drum.RIM, "......o.......o.")
                    bass("0 ~ ~ ~ ~ ~ ~ ~ ~ ~ 0 ~ 3g ~ ~ ~ | ~ ~ 0 ~ ~ ~ ~ ~ ~ ~ -2g ~ ~ ~ 0g ~")
                },
                pattern(2) {
                    hit(Drum.KICK, "X.....x...X..... | ..X...x...X...x.")
                    hit(Drum.SNARE, "........X....... | ......X.X....X..")
                    hit(Drum.CHH, "x..x..x..x..x..x..x..x.. | x..x..x..xxx..x..x..x..x", step = T16, len = 24)
                    hit(Drum.OHH, "..............x.")
                    bass("0 ~ ~ ~ ~ ~ 0 ~ ~ ~ 3g ~ ~ ~ 5g ~ | ~ ~ 0 ~ ~ ~ 7g ~ ~ ~ 5g ~ 3g ~ 0g ~")
                },
            ),
        ),
        BeatStyle(
            "boombap", "Boom Bap", "Dusty 90s hip-hop swing", 90, Kit.ROOM, Color(0xFFE0A15A), swing = 60,
            bassMode = 3, bassPreset = 33,
            variations = listOf(
                pattern(2) {
                    hit(Drum.KICK, "X......x..x..... | X......x..x...x.")
                    hit(Drum.SNARE, "....X.......X... | ....X.......X..o")
                    hit(Drum.CHH, "x.o.x.o.x.o.x.o. | x.o.x.o.x.o.x...")
                    hit(Drum.OHH, "................ | ..............x.")
                    bass("0 ~ ~ ~ ~ ~ ~ 0 ~ ~ 0 ~ ~ ~ ~ ~ | 0 ~ ~ ~ ~ ~ ~ 7 ~ ~ 5 ~ ~ ~ 3 ~")
                },
                pattern(2) {
                    hit(Drum.KICK, "X.x....x..x..... | X.x....x..x.x...")
                    hit(Drum.SNARE, "....X..g....X... | ....X..g....X.g.")
                    hit(Drum.CHH, "xoxoxoxoxoxoxoxo")
                    hit(Drum.TAMB, "....x.......x...")
                    bass("0 ~ 0 ~ ~ ~ ~ 0 ~ ~ 0 ~ ~ ~ ~ ~ | 0 ~ 0 ~ ~ ~ ~ 7 ~ ~ 5 ~ 3 ~ ~ ~")
                },
            ),
        ),
        BeatStyle(
            "rnb", "R&B", "Late-night silk", 76, Kit.ELECTRONIC, Color(0xFFE76F7E), swing = 55,
            bassMode = 0,
            variations = listOf(
                pattern(2) {
                    hit(Drum.KICK, "X......x.x...... | X.....x..x....x.")
                    hit(Drum.CLAP, "....X.......X...")
                    hit(Drum.RIM, "..........o..... | ..o.......o.....")
                    hit(Drum.CHH, "xogoxogoxogoxogo")
                    bass("0 ~ ~ ~ ~ ~ ~ 0 ~ 0 ~ ~ ~ ~ ~ ~ | 0 ~ ~ ~ ~ ~ 5 ~ ~ 7 ~ ~ ~ ~ 3 ~")
                },
                pattern(2) {
                    hit(Drum.KICK, "X..x...x.x...... | X.....x..x..x...")
                    hit(Drum.CLAP, "....X.......X...")
                    hit(Drum.SNARE2, "...........g...g")
                    hit(Drum.CHH, "xoxoxoxoxxxoxoxo", len = 24)
                    hit(Drum.OHH, "..............o.")
                    bass("0 ~ ~ 0 ~ ~ ~ 0 ~ 0 ~ ~ ~ ~ ~ ~ | 0 ~ ~ ~ ~ ~ 5 ~ ~ 7 ~ ~ 3 ~ ~ ~")
                },
            ),
        ),
        BeatStyle(
            "pop", "Pop", "Radio-ready drive", 116, Kit.STANDARD2, Color(0xFFFF8FB1),
            variations = listOf(
                pattern(2) {
                    hit(Drum.KICK, "X.......X.x..... | X.......X.....x.")
                    hit(Drum.SNARE, "....X.......X...")
                    hit(Drum.CLAP, "....o.......o...")
                    hit(Drum.CHH, "x.x.x.x.x.x.x.x. | x.x.x.x.x.x.x...")
                    hit(Drum.OHH, "................ | ..............x.")
                },
                pattern(1) {
                    hit(Drum.KICK, "X...X...X...X...")
                    hit(Drum.CLAP, "....X.......X...")
                    hit(Drum.CHH, "..x...x...x...x.")
                    hit(Drum.SHAKER, "xoxoxoxoxoxoxoxo")
                },
            ),
        ),
        BeatStyle(
            "ballad", "Slow Ballad", "Soft sticks for slow songs", 66, Kit.ROOM, Color(0xFFC9B6FF),
            variations = listOf(
                pattern(1) {
                    hit(Drum.KICK, "X.......x.o.....")
                    hit(Drum.RIM, "....x.......x...")
                    hit(Drum.CHH, "x.o.x.o.x.o.x.o.")
                },
                pattern(2) {
                    hit(Drum.KICK, "X.......x.o..... | X.......x...o...")
                    hit(Drum.SNARE, "....x.......x... | ....x.......x..g")
                    hit(Drum.RIDE, "x.o.x.o.x.o.x.o.")
                    hit(Drum.CRASH, "x............... | ................", len = 192)
                },
            ),
        ),
        BeatStyle(
            "dembow", "Reggaeton", "Dembow riddim", 94, Kit.TR808, Color(0xFFFFD36B),
            bassMode = 1,
            variations = listOf(
                pattern(1) {
                    hit(Drum.KICK, "X...X...X...X...")
                    hit(Drum.SNARE, "...x..x....x..x.")
                    hit(Drum.CHH, "x.x.x.x.x.x.x.x.")
                    bass("0 ~ ~ 0 ~ ~ 0 ~ 0 ~ ~ 0 ~ ~ 0 ~")
                },
                pattern(2) {
                    hit(Drum.KICK, "X...X...X...X...")
                    hit(Drum.SNARE, "...x..x....x..x. | ...x..x....x.xxx")
                    hit(Drum.CHH, "xoxoxoxoxoxoxoxo")
                    hit(Drum.TAMB, "..x...x...x...x.")
                    bass("0 ~ ~ 0 ~ ~ 0 ~ 0 ~ ~ 0 ~ ~ 0 ~ | 0 ~ ~ 0 ~ ~ 5 ~ 5 ~ ~ 7 ~ ~ 7 ~")
                },
            ),
        ),
        BeatStyle(
            "house", "House", "Four on the floor", 124, Kit.TR808, Color(0xFF8E9BFF),
            bassMode = 3, bassPreset = 38,
            variations = listOf(
                pattern(1) {
                    hit(Drum.KICK, "X...X...X...X...")
                    hit(Drum.CLAP, "....X.......X...")
                    hit(Drum.CHH, "gogogogogogogogo", len = 24)
                    hit(Drum.OHH, "..x...x...x...x.")
                    bass("- - 0 - - - 0 - - - 0 - - - 12 -")
                },
                pattern(2) {
                    hit(Drum.KICK, "X...X...X...X...")
                    hit(Drum.CLAP, "....X.......X...")
                    hit(Drum.CHH, "xoxoxoxoxoxoxoxo", len = 24)
                    hit(Drum.OHH, "..x...x...x...x.")
                    hit(Drum.SHAKER, "xoxoxoxoxoxoxoxo")
                    hit(Drum.CRASH, "X............... | ................", len = 192)
                    bass("- - 0 ~ - - 0 ~ - - 0 ~ - - 12 - | - - 0 ~ - - 0 ~ - - 5 ~ - - 7 -")
                },
            ),
        ),
        BeatStyle(
            "lofi", "Lo-Fi", "Lazy, dusty, late-night", 78, Kit.JAZZ, Color(0xFFBDB3A1), swing = 62,
            variations = listOf(
                pattern(2) {
                    hit(Drum.KICK, "X.......x.x..... | X.....x...x.....")
                    hit(Drum.SNARE, "....x.......x... | ....x.......x..g")
                    hit(Drum.CHH, "x.o.x.o.x.o.x.o.")
                    hit(Drum.RIM, "..........g.....")
                },
                pattern(2) {
                    hit(Drum.KICK, "X.....x...x..... | X.......x.x...x.")
                    hit(Drum.SNARE, "....x..g....x... | ....x..g....x.g.")
                    hit(Drum.RIDE, "x.o.x.o.x.o.x.o.")
                    hit(Drum.SHAKER, "o.g.o.g.o.g.o.g.")
                },
            ),
        ),
        BeatStyle(
            "funk", "Funk", "Tight 16th pocket", 102, Kit.STANDARD, Color(0xFFFF9A62), swing = 52,
            bassMode = 3, bassPreset = 36,
            variations = listOf(
                pattern(2) {
                    hit(Drum.KICK, "X..x......x..x.. | X.....x...x.x...")
                    hit(Drum.SNARE, "..g.X..g.g..X..g")
                    hit(Drum.CHH, "xoxoXoxoxoxoXoxo", len = 24)
                    hit(Drum.OHH, "................ | ..............x.")
                    bass("0 - - 0 - - 12 - - - 0 - - 0 12 - | 0 - - - - - 0 - - 12 0 - 10 - 12 -")
                },
                pattern(2) {
                    hit(Drum.KICK, "X..x..x...x..x.. | X..x......x.x.x.")
                    hit(Drum.SNARE, "..g.X.gg.g..X.gg")
                    hit(Drum.CHH, "xoxoxoxoxoxoxoxo", len = 24)
                    hit(Drum.COWBELL, "x...x...x...x...")
                    bass("0 - 0 12 - - 0 - - 10 - 12 0 - 12 - | 0 - 0 12 - - 0 - 7 - 10 - 12 - 0 -")
                },
            ),
        ),
        BeatStyle(
            "rock", "Rock", "Big room backbeat", 120, Kit.POWER, Color(0xFFE05A47),
            variations = listOf(
                pattern(2) {
                    hit(Drum.KICK, "X.......X.X..... | X.......X.X...x.")
                    hit(Drum.SNARE, "....X.......X... | ....X.......X.oo")
                    hit(Drum.CHH, "X.x.X.x.X.x.X.x.")
                    hit(Drum.CRASH, "X............... | ................", len = 192)
                },
                pattern(1) {
                    hit(Drum.KICK, "X.x.....X.x.x...")
                    hit(Drum.SNARE, "....X.......X...")
                    hit(Drum.RIDE, "X.x.X.x.X.x.X.x.")
                    hit(Drum.BELL, "x.......x.......")
                },
            ),
        ),
        BeatStyle(
            "swing", "Jazz Swing", "Ride cymbal spang-a-lang", 132, Kit.JAZZ, Color(0xFF6BA8FF),
            variations = listOf(
                pattern(2) {
                    hit(Drum.RIDE, "x..x.xx..x.x", step = T8)
                    hit(Drum.PHH, "...x.....x..", step = T8)
                    hit(Drum.KICK, "g..g..g..g..", step = T8)
                    hit(Drum.SNARE, "........g... | .....o....g.", step = T8)
                },
                pattern(2) {
                    hit(Drum.RIDE, "x..x.xx..x.x", step = T8)
                    hit(Drum.PHH, "...x.....x..", step = T8)
                    hit(Drum.KICK, "g..g..g..g.. | g..g..o..g..", step = T8)
                    hit(Drum.SNARE, "..g.....o..g | .....g..g.oo", step = T8)
                },
            ),
        ),
        BeatStyle(
            "bossa", "Bossa Nova", "Rio side-stick clave", 128, Kit.BRUSH, Color(0xFF9EE6A0),
            variations = listOf(
                pattern(2) {
                    hit(Drum.KICK, "X..xX..xX..xX..x")
                    hit(Drum.RIM, "x..x..x...x..x.. | ..x..x...x..x...")
                    hit(Drum.CHH, "xoxoxoxoxoxoxoxo")
                },
                pattern(2) {
                    hit(Drum.KICK, "X..xX..xX..xX..x")
                    hit(Drum.RIM, "x..x..x...x..x.. | ..x..x...x..x...")
                    hit(Drum.RIDE, "x.o.x.o.x.o.x.o.")
                    hit(Drum.SHAKER, "xoxoxoxoxoxoxoxo")
                },
            ),
        ),
        BeatStyle(
            "reggae", "Reggae", "One-drop riddim", 76, Kit.STANDARD, Color(0xFFF4D35E), swing = 62,
            variations = listOf(
                pattern(1) {
                    hit(Drum.KICK, "........X.......")
                    hit(Drum.RIM, "........x.......")
                    hit(Drum.CHH, "x.x.x.x.x.x.x.x.")
                },
                pattern(2) {
                    hit(Drum.KICK, "........X....... | ........X.......")
                    hit(Drum.SNARE, "........x....... | ........x.....oo")
                    hit(Drum.CHH, "x.xox.xox.xox.xo")
                    hit(Drum.OHH, "......x.......x.")
                },
            ),
        ),
    )

    fun byId(id: String?) = styles.firstOrNull { it.id == id }

    val keyNames = listOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")
}
