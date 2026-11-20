package app.breakpiano.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreParserTest {
    private fun near(a: Float, b: Float) = assertEquals(b, a, 0.002f)

    @Test fun breakSongIsExact() {
        val s = ScoreParser.parse("""
            {"format":"break-song","title":"Test","tempo":120,"timeSignature":"4/4","key":"C major",
             "right":"C4:1 D4:1 E4:2 | F4+A4:2 R:1 G4:0.5 G4:0.5 |",
             "left":"C3:4 | F3:4 |"}
        """.trimIndent())
        assertEquals("Test", s.title); assertEquals(120f, s.bpm); assertEquals(4, s.meterNum); assertEquals("C major", s.key)
        assertTrue(s.warnings.toString(), s.warnings.isEmpty())
        val right = s.notes.filter { it.hand == 0 }
        assertEquals(listOf(60, 62, 64, 65, 69, 67, 67), right.map { it.pitch })
        near(right[0].start, 0f); near(right[1].start, 0.5f); near(right[2].start, 1.0f)
        near(right[3].start, 2.0f); near(right[4].start, 2.0f)          // chord: together
        near(right[5].start, 3.5f); near(right[6].start, 3.75f)         // after a 1-beat rest
        assertTrue(right[2].end > 1.9f)                                 // E4 lasts 2 beats (1 s)
        val left = s.notes.filter { it.hand == 1 }
        assertEquals(listOf(48, 53), left.map { it.pitch }); near(left[1].start, 2.0f)
    }

    @Test fun realAiReplyWithChatterFencesCommentsAndTrailingCommas() {
        val reply = """
            Sure! Here is "Ode to Joy" for Break:

            ```json
            {
              "format": "break-song",
              "title": "Ode to Joy",   // Beethoven
              "tempo": 100,
              "timeSignature": "4/4",
              "key": "C major",
              "right": "E4 E4 F4 G4 | G4 F4 E4 D4 | C4 C4 D4 E4 | E4:1.5 D4:0.5 D4:2 |",
              "left": "",
            }
            ```
            Enjoy playing!
        """.trimIndent()
        val s = ScoreParser.parse(reply)
        assertEquals("Ode to Joy", s.title)
        assertEquals(listOf(64, 64, 65, 67, 67, 65, 64, 62, 60, 60, 62, 64, 64, 62, 62), s.notes.map { it.pitch })
        assertTrue(s.warnings.isEmpty())
    }

    @Test fun wrongBarLengthIsReported() {
        val s = ScoreParser.parse("""{"tempo":90,"timeSignature":"4/4","right":"C4 D4 E4 F4 | G4 G4 G4 | C5:4 |"}""")
        assertTrue(s.warnings.toString(), s.warnings.any { "bar 2" in it && "3 beats" in it })
    }

    @Test fun tiesAndTriplets() {
        val s = ScoreParser.parse("""{"tempo":60,"right":"C4:1/3 D4:1/3 E4:1/3 F4:1~ F4:1 | G4:4 |"}""")
        assertEquals(listOf(60, 62, 64, 65, 67), s.notes.map { it.pitch })
        near(s.notes[1].start, 1f / 3); near(s.notes[3].start, 1f)
        assertTrue("tied F lasts 2 beats", s.notes[3].end > 1.95f)
        near(s.notes[4].start, 3f)
    }

    @Test fun noteNames() {
        assertEquals(60, ScoreParser.noteNumber("C4")); assertEquals(58, ScoreParser.noteNumber("Bb3"))
        assertEquals(73, ScoreParser.noteNumber("C#5")); assertEquals(65, ScoreParser.noteNumber("E#4"))
        assertEquals(59, ScoreParser.noteNumber("Cb4")); assertEquals(67, ScoreParser.noteNumber("Sol4"))
        assertEquals(60, ScoreParser.noteNumber("Do4")); assertEquals(63, ScoreParser.noteNumber("Re#4"))
        assertEquals(null, ScoreParser.noteNumber("H4"))
    }

    @Test fun abcWithKeyRepeatsEndingsChordsAndBrokenRhythm() {
        val s = ScoreParser.parse("""
            X:1
            T:Waltz in G
            M:3/4
            L:1/8
            Q:1/4=90
            K:G
            |: G2 B2 d2 | c>B A2 [DF]2 |1 G6 :|2 G4 z2 |]
        """.trimIndent())
        assertEquals("Waltz in G", s.title); assertEquals(3, s.meterNum); assertEquals(90f, s.bpm)
        assertEquals(
            listOf(67, 71, 74, 72, 71, 69, 62, 66, 67, 67, 71, 74, 72, 71, 69, 62, 66, 67),
            s.notes.sortedWith(compareBy({ it.start }, { it.pitch })).map { it.pitch },
        )
        val eighth = 60f / 90f / 2f
        val sorted = s.notes.sortedWith(compareBy({ it.start }, { it.pitch }))
        near(sorted[4].start, sorted[3].start + eighth * 1.5f)          // c> B: dotted
        near(sorted[9].start, 18 * eighth)                              // second time through starts after ending 1
        near(sorted.last().start, 30 * eighth)                          // ending 2
    }

    @Test fun abcTwoHandsAndBarAccidentals() {
        val s = ScoreParser.parse("""
            X:1
            T:Two hands
            M:4/4
            L:1/4
            Q:1/4=60
            K:C
            V:1
            ^F F G A | F4 |]
            V:2 clef=bass
            C,4 | G,,4 |]
        """.trimIndent())
        val right = s.notes.filter { it.hand == 0 }.map { it.pitch }
        assertEquals(listOf(66, 66, 67, 69, 65), right)                 // sharp carries in the bar, resets after
        val left = s.notes.filter { it.hand == 1 }
        assertEquals(listOf(48, 43), left.map { it.pitch }); near(left[1].start, 4f)
    }
}
