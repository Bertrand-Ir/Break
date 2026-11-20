# Break song file

Break can import a song written out as a small text file and play it **exactly as written**: tempo, time signature, key, bars, every note, both hands.

Save the file as `something.json` and open it in Break with **Learn → Import**.

## The format

```json
{
  "format": "break-song",
  "title": "Twinkle Twinkle Little Star",
  "tempo": 100,
  "timeSignature": "4/4",
  "key": "C major",
  "right": "C4 C4 G4 G4 | A4 A4 G4:2 | F4 F4 E4 E4 | D4 D4 C4:2 |",
  "left": "C3+G3:4 | F3+A3:2 C3+G3:2 | F3+A3:2 C3+G3:2 | G2+G3:2 C3:2 |"
}
```

| Part | Meaning |
|---|---|
| `C4`, `F#4`, `Bb3` | A note: letter, optional `#` or `b`, octave. `C4` is middle C. |
| `:2` | Length in beats: `1` quarter, `0.5` eighth, `0.25` sixteenth, `2` half, `4` whole, `1.5` dotted quarter, `1/3` triplet eighth. No length = 1 beat. |
| `R:1` | A rest of 1 beat. |
| `C4+E4+G4:2` | A chord: notes played together. |
| `\|` | Bar line. Each bar should add up to the time signature. |
| `G4:4~ \| G4:2` | `~` holds a note into the next note of the same pitch. |
| `tempo` | Quarter-note beats per minute. |
| `right` / `left` | Right-hand and left-hand parts (`left` can be `""`). |

## Asking an AI for a song file

Copy this into ChatGPT, Claude, Gemini or any AI, change the song name, and save its answer as a `.json` file:

> Write the piano song "Happy Birthday" as a Break song file. Reply with only the JSON, in this format:
> `{"format":"break-song","title":"...","tempo":100,"timeSignature":"4/4","key":"C major","right":"...","left":"..."}`
> Notes are like C4 (middle C), F#4, Bb3, with the length in beats after a colon (1 = quarter, 0.5 = eighth, 2 = half, 4 = whole, 1.5 = dotted quarter). R:1 is a rest. Join chord notes with +. Put | between bars; each bar must add up to the time signature. "right" is the melody, "left" a simple accompaniment. Write the whole song with the correct notes, rhythm, key and tempo.

Break also opens **ABC notation** files (`.abc`) and **MIDI** files (`.mid`), which most AIs can produce too.
