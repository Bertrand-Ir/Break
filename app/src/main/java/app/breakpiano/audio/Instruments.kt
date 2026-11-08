package app.breakpiano.audio

import androidx.compose.ui.graphics.Color

enum class InstrumentFamily(val title: String, val tint: Color, val tint2: Color) {
    Piano("Pianos", Color(0xFFF4EFE6), Color(0xFFBDB3A1)),
    Electric("Electric Keys", Color(0xFFFFB86B), Color(0xFFE0664A)),
    Organ("Organs", Color(0xFFE9C46A), Color(0xFF9C6B2F)),
    Guitar("Guitars", Color(0xFFFF9A62), Color(0xFFB4462E)),
    Strings("Strings", Color(0xFFE76F7E), Color(0xFF7D2A4A)),
    Bass("Bass", Color(0xFF8E9BFF), Color(0xFF3B3F9E)),
    Winds("Brass & Winds", Color(0xFFFFD36B), Color(0xFFB07A1E)),
    Choir("Choir & Voices", Color(0xFFC9B6FF), Color(0xFF6A4FB3)),
    Synth("Synths & Pads", Color(0xFF6BE3FF), Color(0xFF2A6FB8)),
    Mallets("Bells & Mallets", Color(0xFF8FF0C4), Color(0xFF2E8A6A)),
    World("World", Color(0xFFFFA8D9), Color(0xFF9E3C78)),
}

data class Instrument(
    val id: String,
    val name: String,
    val family: InstrumentFamily,
    val bank: Int,
    val program: Int,
    val blurb: String,
) {
    /** Two-letter monogram shown on the instrument tile. */
    val monogram: String
        get() {
            val words = name.split(' ', '-').filter { it.isNotBlank() && it[0].isLetter() }
            return if (words.size >= 2) "${words[0][0]}${words[1][0].lowercaseChar()}"
            else name.take(2).replaceFirstChar { it.uppercase() }
        }
}

object Instruments {
    private fun i(id: String, name: String, f: InstrumentFamily, bank: Int, prog: Int, blurb: String) =
        Instrument(id, name, f, bank, prog, blurb)

    val all: List<Instrument> = listOf(
        // Pianos
        i("grand", "Concert Grand", InstrumentFamily.Piano, 0, 0, "Warm, full-bodied grand"),
        i("bright", "Bright Grand", InstrumentFamily.Piano, 0, 1, "Crisp attack, cuts through a mix"),
        i("egrand", "Electric Grand", InstrumentFamily.Piano, 0, 2, "Classic stage CP-style grand"),
        i("honky", "Honky-Tonk", InstrumentFamily.Piano, 0, 3, "Detuned saloon upright"),
        i("bellpiano", "Bell Piano", InstrumentFamily.Piano, 12, 0, "Grand with a glassy bell sheen"),
        i("pianostr", "Piano & Strings", InstrumentFamily.Piano, 11, 1, "Grand layered with a string bed"),
        i("harpsi", "Harpsichord", InstrumentFamily.Piano, 0, 6, "Plucked baroque keyboard"),
        i("harpsi2", "Coupled Harpsichord", InstrumentFamily.Piano, 8, 6, "Two choirs, bigger and brighter"),
        // Electric keys
        i("tine", "Tine Electric", InstrumentFamily.Electric, 0, 4, "Bell-like tine EP, soul & R&B"),
        i("tinech", "Chorus Tine", InstrumentFamily.Electric, 8, 4, "Tine EP with lush chorus"),
        i("fmep", "FM Electric", InstrumentFamily.Electric, 0, 5, "Glassy 80s digital EP"),
        i("fmepch", "Chorus FM", InstrumentFamily.Electric, 8, 5, "Shimmering FM ballad keys"),
        i("tinefm", "Tine & FM", InstrumentFamily.Electric, 11, 4, "Two EPs stacked — gospel favorite"),
        i("bellep", "Bell Tine", InstrumentFamily.Electric, 12, 4, "Tine EP with extra bell"),
        i("clav", "Clavinet", InstrumentFamily.Electric, 0, 7, "Funky, percussive clav"),
        // Organs
        i("tonewheel", "Tonewheel Organ", InstrumentFamily.Organ, 0, 16, "Drawbar organ for gospel & soul"),
        i("tonewheel2", "Detuned Tonewheel", InstrumentFamily.Organ, 8, 16, "Rotary-style swirl"),
        i("percorgan", "Percussive Organ", InstrumentFamily.Organ, 0, 17, "Organ with key-click percussion"),
        i("rockorgan", "Rock Organ", InstrumentFamily.Organ, 0, 18, "Gritty overdriven organ"),
        i("pipe", "Pipe Organ", InstrumentFamily.Organ, 0, 19, "Majestic church pipes"),
        i("pipe2", "Cathedral Organ", InstrumentFamily.Organ, 8, 19, "Full-stop cathedral registration"),
        i("reed", "Reed Organ", InstrumentFamily.Organ, 0, 20, "Soft pump-organ reeds"),
        i("accordion", "Accordion", InstrumentFamily.Organ, 0, 21, "French musette accordion"),
        i("accordion2", "Italian Accordion", InstrumentFamily.Organ, 8, 21, "Bright Italian tuning"),
        i("harmonica", "Harmonica", InstrumentFamily.Organ, 0, 22, "Blues harp"),
        // Guitars
        i("nylon", "Nylon Guitar", InstrumentFamily.Guitar, 0, 24, "Classical & bossa nova"),
        i("steel", "Steel Guitar", InstrumentFamily.Guitar, 0, 25, "Bright acoustic steel-string"),
        i("twelve", "12-String Guitar", InstrumentFamily.Guitar, 8, 25, "Doubled, jangly acoustic"),
        i("jazzgtr", "Jazz Guitar", InstrumentFamily.Guitar, 0, 26, "Mellow hollow-body"),
        i("clean", "Clean Electric", InstrumentFamily.Guitar, 0, 27, "Clean single-coil"),
        i("cleanch", "Chorus Electric", InstrumentFamily.Guitar, 8, 27, "Clean guitar with chorus"),
        i("funk", "Funk Guitar", InstrumentFamily.Guitar, 8, 28, "Tight, chicken-pickin' funk"),
        i("muted", "Muted Guitar", InstrumentFamily.Guitar, 0, 28, "Palm-muted plucks"),
        i("overdrive", "Overdrive Guitar", InstrumentFamily.Guitar, 0, 29, "Crunchy tube overdrive"),
        i("distortion", "Distortion Guitar", InstrumentFamily.Guitar, 0, 30, "High-gain rock lead"),
        i("ukulele", "Ukulele", InstrumentFamily.Guitar, 8, 24, "Sunny island strum"),
        i("mandolin", "Mandolin", InstrumentFamily.Guitar, 16, 25, "Bright doubled courses"),
        i("banjo", "Banjo", InstrumentFamily.Guitar, 0, 105, "Bluegrass twang"),
        // Strings
        i("violin", "Violin", InstrumentFamily.Strings, 0, 40, "Expressive solo violin"),
        i("viola", "Viola", InstrumentFamily.Strings, 0, 41, "Darker, warmer solo voice"),
        i("cello", "Cello", InstrumentFamily.Strings, 0, 42, "Rich, singing solo cello"),
        i("contrabass", "Double Bass", InstrumentFamily.Strings, 0, 43, "Bowed orchestral bass"),
        i("fiddle", "Fiddle", InstrumentFamily.Strings, 0, 110, "Folk & country fiddle"),
        i("ensemble", "String Ensemble", InstrumentFamily.Strings, 0, 48, "Full string section"),
        i("slowstr", "Slow Strings", InstrumentFamily.Strings, 0, 49, "Soft-attack cinematic strings"),
        i("tremolo", "Tremolo Strings", InstrumentFamily.Strings, 0, 44, "Tense, shimmering tremolo"),
        i("pizz", "Pizzicato", InstrumentFamily.Strings, 0, 45, "Plucked string section"),
        i("harp", "Harp", InstrumentFamily.Strings, 0, 46, "Concert pedal harp"),
        i("orchestra", "Full Orchestra", InstrumentFamily.Strings, 12, 48, "Strings, brass & winds together"),
        i("synthstr", "Synth Strings", InstrumentFamily.Strings, 0, 50, "Analog string machine"),
        // Bass
        i("upright", "Upright Bass", InstrumentFamily.Bass, 0, 32, "Plucked jazz upright"),
        i("finger", "Finger Bass", InstrumentFamily.Bass, 0, 33, "Round electric finger bass"),
        i("pick", "Pick Bass", InstrumentFamily.Bass, 0, 34, "Punchy picked bass"),
        i("fretless", "Fretless Bass", InstrumentFamily.Bass, 0, 35, "Singing, gliding fretless"),
        i("slap", "Slap Bass", InstrumentFamily.Bass, 0, 36, "Funky slap & pop"),
        i("synthbass", "Synth Bass", InstrumentFamily.Bass, 0, 38, "Fat analog synth bass"),
        i("acid", "Acid Bass", InstrumentFamily.Bass, 8, 38, "Squelchy 303-style bass"),
        // Brass & winds
        i("trumpet", "Trumpet", InstrumentFamily.Winds, 0, 56, "Bright solo trumpet"),
        i("trombone", "Trombone", InstrumentFamily.Winds, 0, 57, "Warm slide trombone"),
        i("horn", "French Horn", InstrumentFamily.Winds, 1, 60, "Noble solo horn"),
        i("brass", "Brass Section", InstrumentFamily.Winds, 0, 61, "Punchy horn section"),
        i("altosax", "Alto Sax", InstrumentFamily.Winds, 0, 65, "Smooth alto saxophone"),
        i("tenorsax", "Tenor Sax", InstrumentFamily.Winds, 0, 66, "Breathy tenor saxophone"),
        i("flute", "Flute", InstrumentFamily.Winds, 0, 73, "Airy concert flute"),
        i("clarinet", "Clarinet", InstrumentFamily.Winds, 0, 71, "Woody, round clarinet"),
        i("oboe", "Oboe", InstrumentFamily.Winds, 0, 68, "Reedy orchestral oboe"),
        i("panflute", "Pan Flute", InstrumentFamily.Winds, 0, 75, "Breathy pan pipes"),
        // Choir
        i("choir", "Concert Choir", InstrumentFamily.Choir, 0, 52, "Big 'aah' choir"),
        i("oohs", "Voice Oohs", InstrumentFamily.Choir, 0, 53, "Soft 'ooh' backing vocals"),
        i("synthvox", "Synth Voice", InstrumentFamily.Choir, 0, 54, "Airy synthetic choir"),
        // Synths
        i("warmpad", "Warm Pad", InstrumentFamily.Synth, 0, 89, "Soft, enveloping pad"),
        i("polysynth", "Polysynth", InstrumentFamily.Synth, 0, 90, "Classic brassy polysynth"),
        i("halo", "Halo Pad", InstrumentFamily.Synth, 0, 94, "Ethereal vocal pad"),
        i("fantasia", "Fantasia", InstrumentFamily.Synth, 0, 88, "Sparkling bell pad"),
        i("sawstack", "Saw Stack", InstrumentFamily.Synth, 11, 100, "Huge supersaw stack"),
        i("sawlead", "Saw Lead", InstrumentFamily.Synth, 0, 81, "Cutting sawtooth lead"),
        i("squarelead", "Square Lead", InstrumentFamily.Synth, 0, 80, "Hollow chiptune lead"),
        i("synthbrass", "Synth Brass", InstrumentFamily.Synth, 0, 62, "80s synth brass stabs"),
        // Mallets
        i("vibes", "Vibraphone", InstrumentFamily.Mallets, 0, 11, "Jazz vibes with tremolo"),
        i("marimba", "Marimba", InstrumentFamily.Mallets, 0, 12, "Warm wooden marimba"),
        i("celesta", "Celesta", InstrumentFamily.Mallets, 0, 8, "Delicate music-box celesta"),
        i("musicbox", "Music Box", InstrumentFamily.Mallets, 0, 10, "Tiny tinkling comb"),
        i("glock", "Glockenspiel", InstrumentFamily.Mallets, 0, 9, "Bright steel bells"),
        i("kalimba", "Kalimba", InstrumentFamily.Mallets, 0, 108, "African thumb piano"),
        i("steeldrum", "Steel Drums", InstrumentFamily.Mallets, 0, 114, "Caribbean steel pan"),
        i("tubular", "Tubular Bells", InstrumentFamily.Mallets, 0, 14, "Orchestral chimes"),
        // World
        i("sitar", "Sitar", InstrumentFamily.World, 0, 104, "Buzzing Indian sitar"),
        i("koto", "Koto", InstrumentFamily.World, 0, 107, "Japanese zither"),
        i("shamisen", "Shamisen", InstrumentFamily.World, 0, 106, "Snappy three-string lute"),
        i("bagpipe", "Bagpipes", InstrumentFamily.World, 0, 109, "Highland pipes with drone"),
    )

    val default = all.first()
    private val byId = all.associateBy { it.id }
    fun byId(id: String?): Instrument? = id?.let { byId[it] }
    fun of(family: InstrumentFamily) = all.filter { it.family == family }
}
