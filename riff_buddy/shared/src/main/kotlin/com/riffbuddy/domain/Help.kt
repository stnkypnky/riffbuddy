package com.riffbuddy.domain

/**
 * The "?" helper's answers: an FAQ for every screen.
 *
 * Screens that teach — theory, tone, timing — get fuller answers than the
 * ones that only need "where's the button". Paragraphs are split with a
 * blank line and lists use "• " or "1. ", which the sheet shows as they are.
 *
 * Plain Kotlin rather than the database or a downloaded file, so it works
 * offline from the first launch, needs no migration, and every answer can be
 * read and edited in one place. Each answer was written against what the
 * screen actually does — its real button names and defaults — so when a
 * screen changes, its answers here should change with it.
 *
 * Frets and strings first, theory words second: tab is what most players read.
 */
enum class HelpTopic(val title: String) {
    HOME("Home"),
    SONGS("Songs"),
    SONG_PAGE("A song's page"),
    METRONOME("Metronome"),
    TUNER("Tuner"),
    THEORY("Theory"),
    TABS("Tabs"),
    LOOPER("Looper"),
    RECORDER("Recorder"),
    BACKING("Backing tracks"),
    COACH("Coach"),
    PLAN("Lessons and routines"),
    LOG("Practice log"),
    STATS("Stats"),
    GEAR("Gear"),
    SETTINGS("Settings"),
}

data class Faq(val question: String, val answer: String)

object HelpCatalog {

    fun forTopic(topic: HelpTopic): List<Faq> = ALL.getValue(topic)

    /**
     * Every answer, from every topic, that contains all the words typed —
     * in the question or the answer, in any order, ignoring case. "drop d"
     * finds "Drop D" but "drop jazz" finds nothing, which is what a search
     * box should do with two words.
     */
    fun search(query: String): List<Pair<HelpTopic, Faq>> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return HelpTopic.entries.flatMap { topic ->
            forTopic(topic).filter { faq ->
                val text = (faq.question + " " + faq.answer).lowercase()
                words.all { it in text }
            }.map { topic to it }
        }
    }

    private val ALL: Map<HelpTopic, List<Faq>> = mapOf(
        HelpTopic.HOME to listOf(
            Faq(
                "How do I start practicing?",
                "Tap Start session. The timer runs off the clock, so it keeps counting with the screen off " +
                    "or while you use other tools. Tap End session when you put the guitar down.",
            ),
            Faq(
                "What counts toward my streak?",
                "Any day with at least 5 minutes of practice — five minutes counts. You can change how many " +
                    "minutes a streak day needs in Me › Settings.",
            ),
            Faq(
                "How do I set a weekly goal?",
                "In Me › Settings, under Practice and audio, pick a Weekly goal. Home then shows a ring of " +
                    "your minutes over the last seven days.",
            ),
            Faq(
                "I forgot to start a session. Can I still log it?",
                "Yes. Go to Me › Log and tap Log a past session. Enter when it started and how many minutes.",
            ),
            Faq(
                "What is Today's plan?",
                "Open homework from your lessons, plus the routine you picked. Tap Start routine to be walked " +
                    "through it step by step, or Lessons and Routines to set them up.",
            ),
            Faq(
                "How do I tag what I worked on?",
                "When you end a session, tap What did you work on? and pick songs, and their parts if you like. " +
                    "That's what fills in time per song and your Stats.",
            ),
        ),

        HelpTopic.SONGS to listOf(
            Faq(
                "How do I add a song?",
                "Tap Add at the top, type the title and artist, and save. Tempo, tuning, tone cards and " +
                    "practice history all hang off the song after that.",
            ),
            Faq(
                "How do I find a song fast?",
                "Type in the search box — it matches title, artist or tag. The filters narrow by tuning, " +
                    "and Sort changes the order.",
            ),
            Faq(
                "What does the percent under a song mean?",
                "How much of the song is mastered, counted by its parts. Break the song into parts on its " +
                    "page, like Intro, Verse and Solo, and move each one to Mastered as you get it.",
            ),
            Faq(
                "Why does it say Not practiced yet?",
                "No session has been tagged with that song. When you end a session, tap What did you work " +
                    "on? and pick it.",
            ),
            Faq(
                "Can I add the same song twice?",
                "Yes. Riff Buddy warns you it's already in your songbook, but lets you add it again if you mean to.",
            ),
        ),

        HelpTopic.SONG_PAGE to listOf(
            Faq(
                "How do I practice this song at its tempo?",
                "Set a Target BPM under Tempo and feel, then tap Open in the metronome. The metronome opens " +
                    "at that tempo and time signature.\n\n" +
                    "Don't start at the target. Start where you can play it cleanly, even if that's half " +
                    "speed, and let the speed trainer walk you up.",
            ),
            Faq(
                "How do I tune for this song?",
                "Pick its Tuning and Capo, then tap Tune to this. The tuner opens with the song's tuning " +
                    "and capo already set.",
            ),
            Faq(
                "What are parts, and what do the stages mean?",
                "Parts are pieces of the song you learn one at a time, like Intro, Verse or Solo. Each " +
                    "moves through four stages, and a good rule of thumb for each is:\n\n" +
                    "• Learning — you're still finding the frets and working out the fingering.\n" +
                    "• Clean but slow — every note is right, but only below the song's tempo.\n" +
                    "• Up to speed — clean at the song's real tempo, with the metronome.\n" +
                    "• Mastered — you can play it along with the record without thinking about it.\n\n" +
                    "When every part is mastered, so is the song.",
            ),
            Faq(
                "How should I break a song into parts?",
                "By the sections you'd practice on their own: the main riff, the verse chords, the chorus, " +
                    "the solo. A long solo can be two or three parts.\n\n" +
                    "Small parts move through the stages faster, which is more motivating than one big part " +
                    "that stays at Learning for weeks.",
            ),
            Faq(
                "How do I get the tab?",
                "Find tab searches Songsterr in your browser or the Songsterr app. Paste the song's own " +
                    "Songsterr link once, and it opens straight there next time. Tab files you own go in " +
                    "Tools › Tabs.",
            ),
            Faq(
                "What is How hard is it?",
                "The coach's estimate of the song's difficulty out of 10, and the techniques it needs. It's " +
                    "a guess, useful for choosing what to learn next. If it seems off, tap Easier than that " +
                    "or Harder than that.",
            ),
            Faq(
                "How do I get my amp set up for this song?",
                "Go to the Coach tab, pick this song and a part, and tap Set up my tone. Save the card to " +
                    "the song, and it shows under Tone cards here.",
            ),
            Faq(
                "What happens to my practice time if I delete a song?",
                "It stays. Practice time already logged against the song stays in your history. Deleting a " +
                    "song doesn't rewrite what you did.",
            ),
        ),

        HelpTopic.METRONOME to listOfNotNull(
            if (Device.onComputer) Faq(
                "Are there keyboard shortcuts?",
                "Space starts and stops the metronome from anywhere in the app. T taps the tempo, the arrow " +
                    "keys change it by 1 BPM (by 5 with Shift held), and Escape goes back.",
            ) else null,
            Faq(
                "How do I set the tempo?",
                "Use Slower and Faster or the slider, or tap Tap tempo four times along with a song or your foot.",
            ),
            Faq(
                "What does BPM mean?",
                "Beats per minute. At 60 BPM there's one click every second, and at 120 there are two. " +
                    "\"♩ = 100\" means each click is a quarter note, the beat you'd tap your foot to.",
            ),
            Faq(
                "How should I practice with a metronome?",
                "1. Find a tempo where you can play the part cleanly three times in a row. That's often much " +
                    "slower than feels comfortable.\n" +
                    "2. Go up 5 BPM and do it again.\n" +
                    "3. When it gets sloppy, drop back 10 BPM and build up again.\n\n" +
                    "Playing it clean and slow teaches your hands the right moves. Playing it fast and sloppy " +
                    "teaches them the mistakes.",
            ),
            Faq(
                "What does the speed trainer do?",
                "It does the steps above for you. It starts at your tempo and goes up 5 BPM every 4 bars, up " +
                    "to 30 BPM faster.\n\n" +
                    "Tap Nailed it to jump up a step early. Tap Stop ramp to stay where you are. When a run " +
                    "ends, Riff Buddy offers to add it to today's log, so the climb shows up in Stats.",
            ),
            Faq(
                "What do time signatures mean?",
                "The top number is how many beats are in a bar.\n\n" +
                    "• 4/4 — count 1 2 3 4. Most rock and pop.\n" +
                    "• 3/4 — count 1 2 3, a waltz feel.\n" +
                    "• 6/8 — six quick beats in two groups: 1 2 3 4 5 6. It sways.\n" +
                    "• 12/8 — four groups of three, the feel of a slow blues.\n\n" +
                    "The metronome makes beat one sound different, so you always know where the bar starts.",
            ),
            Faq(
                "What is a subdivision?",
                "Extra quiet clicks between the beats, to keep strumming and picking even.\n\n" +
                    "• Eighths — two per beat. Count \"1 & 2 &\". Strum down on the number, up on the &.\n" +
                    "• Triplets — three per beat. Count \"1-trip-let\".\n" +
                    "• Sixteenths — four per beat. Count \"1 e & a\". Used in fast riffs and funk strumming.",
            ),
            Faq(
                "How do I make beat one louder?",
                "Under Accents, tap a beat to change it. The accent pattern follows the time signature.",
            ),
            Faq(
                "How do I save a tempo I use a lot?",
                "Set it up, tap Save current under Presets, and give it a name like Warm-up or Verse.",
            ),
            Faq(
                "Does it keep playing if I leave this screen?",
                "Yes. The quick bar at the bottom shows the BPM and beat and has a stop button, on every screen.",
            ),
        ),

        HelpTopic.TUNER to listOf(
            Faq(
                "Why won't the needle settle?",
                "Pick one string at a time and let it ring. Mute the others with your fretting hand. If it " +
                    "says Too quiet, play a little harder or bring ${Device.mic} closer to the guitar or amp.\n\n" +
                    "Read it a second after you pick. The first moment of a note is always a little sharp.",
            ),
            Faq(
                "Which way do I turn the peg?",
                "FLAT — tighten means the note is too low, so tighten the string. SHARP — loosen means " +
                    "it's too high.\n\n" +
                    "Always finish by tuning up to the note. If you're sharp, go a little below, then come " +
                    "back up. The string then holds its tuning better.",
            ),
            Faq(
                "What do the cents mean?",
                "A cent is a tiny slice of pitch. There are 100 cents between one fret and the next. Within " +
                    "3 cents counts as in tune, and the tuner shows it in green with words, not only color.",
            ),
            Faq(
                "What are the notes of standard tuning?",
                "From the thickest string to the thinnest: E A D G B E. One way to remember it: Eddie Ate " +
                    "Dynamite, Good Bye Eddie.",
            ),
            Faq(
                "How do I tune to Drop D or another tuning?",
                "Pick it from the tuning list above the needle. The targets for every string change to match.\n\n" +
                    "Drop D only lowers the thickest string, from E down to D, two frets lower. Then a " +
                    "power chord is one finger across the three thickest strings. Half-step down (E♭) " +
                    "lowers every string by one fret. Many classic rock songs use it.",
            ),
            Faq(
                "Why does my guitar keep going out of tune?",
                "New strings stretch for a day or two, so tune them often. Temperature changes, bends and " +
                    "heavy strumming pull it too. Tune every time you pick the guitar up, and check again " +
                    "after a few minutes of playing.",
            ),
            Faq(
                "What does Guided do?",
                "It walks you through the strings in order, low to high. Once one holds in tune, it moves to " +
                    "the next by itself.",
            ),
            Faq(
                "I'm using a capo. What do I set?",
                "Set the capo fret. The targets shift up to match, so you tune the open strings correctly for it.",
            ),
            Faq(
                "Can I tune by ear?",
                "Yes. Tap Hear it on a string to hear its note, or hold it for a steady drone while you turn " +
                    "the peg. When it's close, the wobble you hear slows down. When the wobble stops, it's in tune.",
            ),
            Faq(
                "Can I make my own tuning?",
                "Tap New tuning, set each string with + and −, low string first, and save it.",
            ),
            Faq(
                "What does A4 = 440 mean?",
                "It's the pitch everything is tuned against. Leave it at 440 unless you're playing along with " +
                    "a recording that's slightly off. You can change it in Me › Settings.",
            ),
        ),

        HelpTopic.THEORY to listOf(
            Faq(
                "Where do I start with theory?",
                "With one shape you'll use forever: A minor pentatonic, Box 1.\n\n" +
                    "1. Open Scales. Set the chips at the top to Root: A and Minor pentatonic.\n" +
                    "2. Under the neck, tap ▶ until it says Box 1. Every dot sits between frets 5 and 8.\n" +
                    "3. Play it low to high, two notes per string: 5 8 on the thickest string, 5 7 on the " +
                    "next three, then 5 8 on the two thinnest.\n\n" +
                    "That's most of the rock and blues solos you've heard. Everything else here builds on it.",
            ),
            Faq(
                "What is a scale?",
                "A set of notes that sound good together. On the neck it's a pattern of dots.\n\n" +
                    "The ringed dots are the root, the note the scale is named after and the one that sounds " +
                    "like home. Start and end a phrase on a root, and it sounds finished.\n\n" +
                    "Tap any dot to hear it. Play, under the neck, plays the position you picked up and back " +
                    "down, lighting each dot with a ring as you hear it. On Whole neck it plays one octave up " +
                    "from the lowest root. Start on Slow and play along.",
            ),
            Faq(
                "How do I play a scale in a different key?",
                "Slide the whole shape up or down the neck. The shape stays the same, and only where the root " +
                    "lands changes.\n\n" +
                    "For shapes rooted on the thickest string, the fret you start on names the key: 3 is G, " +
                    "5 is A, 7 is B, 8 is C, 10 is D, 12 is E. So G minor pentatonic is Box 1 starting at fret 3.",
            ),
            Faq(
                "What are Box, 3NPS and CAGED?",
                "Three ways of cutting the whole neck into hand-sized pieces. Step through them with ◀ and ▶ under " +
                    "the neck, or tap the chip between them for the whole list.\n\n" +
                    "• Box — for pentatonic scales. Five boxes, each about four frets wide, joined end to end. " +
                    "Learn Box 1, then Box 2 just above it, and you can move between them.\n" +
                    "• 3NPS — three notes per string, for seven-note scales like major and minor. Even " +
                    "fingering, good for speed.\n" +
                    "• CAGED — the scale around the five open-chord shapes C, A, G, E and D, moved up the neck. " +
                    "It ties scales to chords you already know. Built around the minor chord's shape for a " +
                    "minor-sounding scale, and the major chord's otherwise. Standard tuning, or any tuning " +
                    "tuned down from it, like E♭.\n\n" +
                    "In every position, only its own notes stay bright; the rest of the scale dims, so you can " +
                    "see exactly what's in the shape. Some have one note a stretch away, past the fourth fret.",
            ),
            Faq(
                "What do Fret numbers, Note names and Scale degrees show?",
                "Three ways of labeling the same dots. Switch with the Dots chip under the neck.\n\n" +
                    "• Fret numbers — where to put your finger. Start here.\n" +
                    "• Note names — the actual notes, like A C D E G.\n" +
                    "• Scale degrees — each note's job in the scale, counted from the root: 1 is the root, " +
                    "b3 is the minor third, 5 is the fifth. The same numbers work in every key.",
            ),
            Faq(
                "What are intervals, in frets?",
                "An interval is the distance between two notes. On one string, each fret is a half step.\n\n" +
                    "• 3 frets up from the root: minor third (b3), the sad sound.\n" +
                    "• 4 frets up: major third (3), the happy sound.\n" +
                    "• 7 frets up: fifth (5). On the next thinner string it's two frets up, the power-chord shape.\n" +
                    "• 12 frets up: the same note an octave higher.\n\n" +
                    "The B string is the exception. Shapes that cross from G to B shift up one fret.",
            ),
            Faq(
                "What makes a chord major or minor?",
                "One note: the third. Major has the note 4 frets above the root, minor has it 3 frets above. " +
                    "Compare open E and open Em in Chords: only one finger moves, one fret, and the chord goes " +
                    "from bright to sad.",
            ),
            Faq(
                "How are chords built?",
                "By stacking notes from the root, counted in frets:\n\n" +
                    "• Major: root, +4, +7\n" +
                    "• Minor: root, +3, +7\n" +
                    "• 7 (dominant): major plus +10, the bluesy chord\n" +
                    "• Power chord (5): only root and +7\n\n" +
                    "A power chord has no third, so it's neither major nor minor. That's why it sounds clean " +
                    "with lots of distortion and fits almost any rock song.",
            ),
            Faq(
                "How do I read a chord box?",
                "The chord boxes here are drawn the same way up as tab: the thickest string at the bottom, " +
                    "and the nut on the left.\n\n" +
                    "• A dot is where to press. The number in it is the finger: 1 index, 2 middle, 3 ring, 4 pinky.\n" +
                    "• ○ before the nut: play that string open.\n" +
                    "• × before the nut: don't play that string.\n" +
                    "• \"5fr\" under the box: the shape starts at fret 5, not at the nut.\n\n" +
                    "Tap ▶ under a chord to hear it: strummed, then one string at a time, then strummed again. " +
                    "Play it along with the one-at-a-time part; any string that sounds different is the one to " +
                    "fix. Tap the star to keep it in Favorites.",
            ),
            Faq(
                "What is a key?",
                "A home base for a song: seven notes, and the chords built from them, that sound like they " +
                    "belong together. Pick a key in Keys to see its chords.\n\n" +
                    "For example, the key of G has G, Am, Bm, C, D, Em and F#dim. A song in G mostly uses " +
                    "those chords and feels settled when it lands on G.",
            ),
            Faq(
                "What do the Roman numerals mean?",
                "Each chord's place in the key. Uppercase is major, lowercase is minor, and ° is diminished.\n\n" +
                    "In G: I is G, IV is C, V is D, and vi is Em. So I–V–vi–IV in G is G D Em C. In A, the same " +
                    "numerals are A E F#m D.\n\n" +
                    "That's why progressions are written in numerals: learn one, and you can play it in any key. " +
                    "The −1 and +1 semitone buttons move the whole key.\n\n" +
                    "Tap ▶ beside a progression to hear it, a bar per chord, with the chord you're hearing in " +
                    "bold. The ▶ by Chords in this key plays all seven, I to vii°.",
            ),
            Faq(
                "How do I work out what key a song is in?",
                "Usually it's the chord the song starts on, and almost always the one it ends on, the one " +
                    "that feels like home. To check, find the key in Keys whose chord list holds the song's chords.",
            ),
            Faq(
                "Which scale do I solo with?",
                "Solo with in Keys suggests scales for the key you've picked. For a first try:\n\n" +
                    "• A minor key or a blues: minor pentatonic from the key's root.\n" +
                    "• A major key: major pentatonic from the root.\n\n" +
                    "A shortcut: the major pentatonic uses the same frets as the minor pentatonic three frets " +
                    "lower. G major pentatonic is E minor pentatonic, and E is 3 frets below G. Jam in this " +
                    "key starts a backing track to try it on.",
            ),
            Faq(
                "What's a relative minor?",
                "A minor key with exactly the same notes as a major one, starting from a different root. " +
                    "A minor and C major share every note, but A minor feels like home on A. The relative minor " +
                    "is always three frets below the major root.",
            ),
            Faq(
                "What are modes?",
                "The major scale started from a different note. Each gives the same notes a different mood. " +
                    "The two worth knowing first:\n\n" +
                    "• Dorian — minor, but brighter. Think Santana, and funk.\n" +
                    "• Mixolydian — major with a bluesy flat 7th. Classic rock and blues.\n\n" +
                    "Get comfortable with the pentatonics first. Modes make more sense after.",
            ),
            Faq(
                "Do the shapes work in other tunings?",
                "The scale dots follow whatever tuning you pick. Chord shapes are for standard tuning, and so is " +
                    "what ▶ plays for them. CAGED works in standard and in tunings tuned down from it (E♭, D, C). " +
                    "In other tunings, use the Scales tab to see where a chord's notes land.",
            ),
        ),

        HelpTopic.TABS to listOf(
            Faq(
                "How do I read tab?",
                "Each line is a string, with the thickest string at the bottom, as you look down at the guitar. " +
                    "Each number is a fret: 0 means play it open, 3 means the third fret.\n\n" +
                    "Read left to right. Numbers stacked on top of each other are played together, as a chord.",
            ),
            Faq(
                "What do the other marks in tab mean?",
                "The common ones:\n\n" +
                    "• H or a curve up — hammer-on: pick the first note, then press the second without picking.\n" +
                    "• P or a curve down — pull-off: the opposite, lift a finger to sound a lower note.\n" +
                    "• A slanted line — slide from one fret to the other.\n" +
                    "• An arrow with \"full\" or \"½\" — bend the string up a whole or half step.\n" +
                    "• A wavy line — vibrato.\n" +
                    "• x — a muted, dead note.\n" +
                    "• P.M. — palm mute: rest the edge of your picking hand on the strings by the bridge.",
            ),
            Faq(
                "How do I add a tab file?",
                "Tap Import a tab file and pick a Guitar Pro, MusicXML or alphaTex file from your device. " +
                    "It's copied in, so it keeps working offline.",
            ),
            Faq(
                "How do I learn a hard part with the player?",
                "1. Tap Loop bars, tap the first bar, then the last. Keep it short, 1 to 4 bars.\n" +
                    "2. Open Playback speed and drop it to 50–70%.\n" +
                    "3. When you've played it clean three times, go up 10%.\n" +
                    "4. Tap Save this loop so it's there next time.\n\n" +
                    "The pitch stays the same when you slow it down. Only the speed changes.",
            ),
            Faq(
                "Can I hear only the guitar?",
                "Open Tracks, then Solo the guitar, or Mute the parts you don't want.",
            ),
            Faq(
                "Why am I only seeing tab and not standard notation?",
                "Tab only is the default. To show standard notation above the tab, turn it on in View, or " +
                    "in Me › Settings.",
            ),
            Faq(
                "Can I play Songsterr tabs here?",
                "No. Songsterr opens in its own app or browser from the song's page. This library is for tab " +
                    "files you've bought, downloaded or written yourself.",
            ),
            Faq(
                "How do I link a tab to a song?",
                "Tap Link to song on the file. It then opens from that song's page.",
            ),
        ),

        HelpTopic.LOOPER to listOf(
            Faq(
                "What is the looper for?",
                "Learning from the real recording. Slow a song down without changing its pitch, and loop the " +
                    "bit you're working on until your fingers have it.",
            ),
            Faq(
                "How do I add a song to loop?",
                "Tap Add a recording and pick an mp3, m4a, wav or ogg from your device. Riff Buddy only " +
                    "remembers where it is — the file isn't moved.",
            ),
            Faq(
                "How do I mark a loop?",
                "Drag across the waveform, or tap Set A and then Set B while it plays. Tap Save loop to keep it.\n\n" +
                    "Start the loop just before the part and end it just after, so you get a moment to get ready.",
            ),
            Faq(
                "How slow should I go?",
                "Slow enough to play every note clean, usually 50–70% for something new. Stay there until " +
                    "it's easy, then go up 5–10% at a time. The loop trainer can do this for you.",
            ),
            Faq(
                "What does the loop trainer do?",
                "It starts the loop slow and speeds it up after a set number of loops, until it reaches full " +
                    "speed. Pick I'll say when it's clean to go up only when you tap Clean run.",
            ),
            Faq(
                "How do I get a click over the music?",
                "Use Tap along at full speed to teach it the song's tempo. Then turn on Metronome over the " +
                    "music, and the clicks follow it, slowing when it slows.",
            ),
            Faq(
                "Why does it say Can't find this recording?",
                "The file was moved, renamed or deleted since you added it. Tap Find it again and point Riff " +
                    "Buddy at it. Your saved loops come back with it.",
            ),
        ),

        HelpTopic.RECORDER to listOf(
            Faq(
                "Can I get a countdown, or start over or delete a take if I mess up?",
                "Both, next to Record.\n\n" +
                    "• Countdown — tap No countdown and pick 3, 5 or 10 seconds. After you tap Record it " +
                    "counts down with a beep each second, so you have time to get your hand back on the neck. " +
                    "Cancel stops it. The beeps finish before recording starts, so they're not in the take.\n" +
                    "• Start over — while recording, tap Start over. The take so far is thrown away and a new " +
                    "one starts, after the countdown if you've set one. Anything playing along starts again too.\n" +
                    "• Stop & delete — ends the take and throws it away, so nothing is kept.",
            ),
            Faq(
                "Why record myself?",
                "While you play, your attention is on your hands. Listening back, you hear what everyone else " +
                    "hears: rushed notes, buzzing strings, chord changes that come in late.\n\n" +
                    "Record the same part once a week, and compare takes to hear how far you've come.",
            ),
            Faq(
                "How do I record myself?",
                "Put ${Device.mic} 20–30 cm (8–12 inches) in front of the amp's speaker, pointing at it, and tap " +
                    "Record. Every take is kept until you delete it.",
            ),
            Faq(
                "It says CLIPPING. What do I do?",
                "It's too loud for the mic, and the take will crackle. Turn the amp down or move ${Device.mic} back, " +
                    "then use Set level.",
            ),
            Faq(
                "How do I record along with the metronome or a song?",
                "Pick it under Play along with, and use wired headphones. Through the speaker, the backing " +
                    "gets recorded too. Bluetooth adds a delay.",
            ),
            Faq(
                "What is Calibrate timing?",
                "Every ${Device.noun} has a small delay between playing a sound and recording one, called latency. " +
                    "Calibrating measures that delay, so your takes can be moved to line up with the backing.\n\n" +
                    "Riff Buddy plays ten clicks and listens for them. It takes about six seconds. Do it in a " +
                    "quiet room with no headphones plugged in.",
            ),
            Faq(
                "How do I compare two takes?",
                "Tick two takes, then tap Compare these two to switch between A and B while they play.",
            ),
            Faq(
                "How do I keep just the good part of a take?",
                "Open the take, drag across the waveform to select it, and tap Save as new clip. The original " +
                    "take is kept as it is.",
            ),
            Faq(
                "How do I send a take to someone?",
                "Tap Share on the take. m4a is small and plays almost anywhere, and wav is the original, uncompressed.",
            ),
        ),

        HelpTopic.BACKING to listOf(
            Faq(
                "How do I make a backing track?",
                "Pick a key, a Preset or your own Progression, a Style and a Tempo, then tap Play. Changing " +
                    "the tempo or style rebuilds it right away.",
            ),
            Faq(
                "What do I, IV and V mean in the chords?",
                "They count chords from the key's root. I is the root chord, IV is the chord four notes up the " +
                    "scale, and V is five up.\n\n" +
                    "In A, I IV V is A, D and E. Change the key to G, and the same I IV V becomes G, C and D. " +
                    "Uppercase numerals are major chords and lowercase are minor, so vi in G is Em.",
            ),
            Faq(
                "How do I type my own chords?",
                "In Chords, use numerals like I IV V, or names like A7 D7 E7, separated by spaces. Numerals " +
                    "follow the key when you change it, and names stay as typed.",
            ),
            Faq(
                "What is a 12-bar blues?",
                "The most played progression there is: twelve bars that repeat.\n\n" +
                    "I I I I | IV IV I I | V IV I V\n\n" +
                    "In A: four bars of A, two of D, two of A, then E, D, A, E, and back to the start. The last " +
                    "E is the turnaround that pulls you into the next round. The quick change version goes to " +
                    "IV in bar 2.",
            ),
            Faq(
                "What should I play over it?",
                "Look at What to play. It shows the scale on the neck, and the notes of the chord playing " +
                    "right now as the big dots.\n\n" +
                    "Noodle in the scale, but land on a big dot when the chord changes. Landing on chord notes " +
                    "is what makes a solo sound like it's following the music.",
            ),
            Faq(
                "Where do I start soloing over a blues in A?",
                "Pick 12-bar blues in A, Blues shuffle, around 80 BPM. Play A minor pentatonic, Box 1 at fret 5.\n\n" +
                    "Start with just three notes and a lot of space: play a short phrase, then rest for a bar. " +
                    "Space sounds more musical than filling every beat.",
            ),
            Faq(
                "What does Swing the eighths do?",
                "It plays pairs of eighth notes long-short instead of even: \"daa-da daa-da\", the bouncy " +
                    "shuffle feel of blues. Leave it off for straight rock.",
            ),
            Faq(
                "What does Bars per chord do?",
                "How long each chord lasts before the next one. In 4/4, one bar is four beats. Use 2 for a " +
                    "slower, relaxed change while you're learning to follow chords.",
            ),
            Faq(
                "Can I save a track for a song?",
                "Tap Save this track and pick the song. It then shows on that song's page.",
            ),
        ),

        HelpTopic.COACH to listOf(
            Faq(
                "What is a tone card?",
                "Settings for your actual amp for a song and part: amp model, knobs and effects, plus which " +
                    "pickup to use and how to play it. It's a starting point, so finish it by ear at your own volume.",
            ),
            Faq(
                "Why can't I make a tone card?",
                "The coach needs an amp in Me › Gear, a song in your songbook, and an AI key in Me › Settings.",
            ),
            Faq(
                "The tone isn't right. What do I do?",
                "Tap a Not quite? chip, like Too muddy or Too much gain, and the coach adjusts the card.",
            ),
            Faq(
                "What does Gain do?",
                "How much the amp distorts. Low is clean, the middle is crunch, and high is heavy distortion.\n\n" +
                    "Most people use more than the record does. Too much gain turns chords to mush and hides " +
                    "mistakes. If a riff sounds messy, turn the gain down before anything else.",
            ),
            Faq(
                "What do Treble, Middle and Bass do?",
                "They shape the sound, like the EQ on a stereo.\n\n" +
                    "• Treble — bite and sparkle. Too much is harsh.\n" +
                    "• Middle — what makes a guitar cut through a band. Too little sounds scooped and thin.\n" +
                    "• Bass — weight. Too much is boomy.\n\n" +
                    "Some modeling amps have no Middle knob on the panel, but every amp model still has one in " +
                    "its settings. In your amp's profile, such a control is marked \"in the display\", and the " +
                    "tone card says where to find it (the amp's screen or its editor app).",
            ),
            Faq(
                "What's the difference between Volume and Master Volume?",
                "On many amps, Volume (or a channel's volume) is part of the sound: it's the preset's own " +
                    "level. Master Volume is how loud the amp is in the room, whichever preset is on. Set the " +
                    "tone with Volume, and the loudness with Master.\n\n" +
                    "In your amp's profile, a knob marked \"loudness only\" is never set by a tone card, since " +
                    "only you know how loud your room should be.",
            ),
            Faq(
                "Which pickup should I use?",
                "The bridge pickup is tighter and brighter, good for riffs and lead. The neck pickup is warmer " +
                    "and thicker, good for clean and smooth lead. Both together is rounder, good for rhythm. The " +
                    "tone card's Pickup line says which one to start with.",
            ),
            Faq(
                "Can I get longer answers, or have it look things up online?",
                "Yes, both under the question box in Ask anything.\n\n" +
                    "• Answer — Short, Standard or In depth. In depth explains the why, goes step by step and " +
                    "adds an exercise. It's remembered.\n" +
                    "• Search the web — needs a free Gemini key (Google allows about 500 searches a day). The " +
                    "answer lists its sources, which you can tap. Good for current things like firmware, new " +
                    "gear or a song's details. Groq can't be used for this: its search used most of a day's " +
                    "free allowance at once and wasn't reliable.\n\n" +
                    "Follow-up questions carry on the same chat, so the coach knows what you mean. New chat " +
                    "starts over, and the chat's name at the top opens your past chats.",
            ),
            Faq(
                "What can I ask in Ask anything?",
                "Gear, scales, technique — anything. Turn on My rig, This song or My level to give it more to go on. " +
                    "Turning on This song opens your song list, and the song you pick stays next to it.",
            ),
            Faq(
                "Does it cost anything?",
                "No. It uses free tiers: Groq, Google's Gemini, Mistral and OpenRouter, none of which need a " +
                    "card. If one is busy, Riff Buddy asks the next one you have a key for.",
            ),
            Faq(
                "Can I trust its answers?",
                "Mostly, but check anything that matters. It can be wrong, and it never gives lyrics or full tab.",
            ),
            Faq(
                "What gets sent over the internet?",
                "Only what the coach needs: the song's title and artist, the part, your tuning and your gear. " +
                    "Never your recordings, tab files, notes or history. Tap What I sent to see it.",
            ),
        ),

        HelpTopic.PLAN to listOf(
            Faq(
                "How do I log a lesson?",
                "Tap Log a lesson, write what was covered, and add homework items. Open homework shows on " +
                    "Home until you tick it done.",
            ),
            Faq(
                "What's a routine?",
                "A practice plan made of timed steps, like warm up, then scales, then a song. Start one from " +
                    "Today's plan on Home, and it walks you through each step.",
            ),
            Faq(
                "What makes a good routine?",
                "A good 30 minutes might be:\n\n" +
                    "• 5 min warm-up — slow chromatic runs or easy chords.\n" +
                    "• 10 min technique — a scale box or chord changes, with the metronome.\n" +
                    "• 10 min a song part — the one you're working on, looped and slowed.\n" +
                    "• 5 min just play — something you already know, for fun.\n\n" +
                    "Short and daily beats long and weekly.",
            ),
            Faq(
                "How do I make a routine?",
                "Start from one of the starter templates with Add to my routines, then edit the steps and times " +
                    "to suit you.",
            ),
            Faq(
                "Which routine does Today's plan use?",
                "The one you tapped Use today on. Your first routine is picked automatically.",
            ),
            Faq(
                "If I delete a lesson, does the homework go too?",
                "No. The lesson's notes go, but its homework stays, and open items still show on Home until done.",
            ),
        ),

        HelpTopic.LOG to listOf(
            Faq(
                "How do I fix a session's time or notes?",
                "Tap the session in Recent sessions to edit or delete it.",
            ),
            Faq(
                "How do I add practice I did without the app?",
                "Tap Log a past session and enter when it started and how many minutes.",
            ),
            Faq(
                "Why is a song missing from Time per song?",
                "Only sessions tagged with a song count there. Edit the session and pick what you worked on.",
            ),
            Faq(
                "Where does Tempo over time come from?",
                "Speed trainer and loop trainer runs you add to the log. Use either on a song to watch its tempo climb.",
            ),
        ),

        HelpTopic.STATS to listOf(
            Faq(
                "How do I earn milestones?",
                "By practicing — time played, streaks, songs mastered, tempo climbs and more. Tap a badge to " +
                    "see what it takes and how close you are.",
            ),
            Faq(
                "Why are my Songs stats empty?",
                "Tag a session with a song when you end it, or in the Log, to see your top songs, tunings and keys.",
            ),
            Faq(
                "How do I get songs into Mastered?",
                "Break a song into parts on its page and move each one to Mastered.",
            ),
            Faq(
                "Where does the Tempo chart come from?",
                "Runs from the metronome's speed trainer and the looper's trainer that you added to the log.",
            ),
            Faq(
                "Can I look at a different time range?",
                "Yes. Pick a range at the top, and everything below follows it.",
            ),
        ),

        HelpTopic.GEAR to listOf(
            Faq(
                "Why do I need to add my gear?",
                "The coach makes tone cards for your actual amp's knobs and your guitar's pickups, not for gear in general.",
            ),
            Faq(
                "How do I add my guitar and amp?",
                "Tap + Add next to Guitars, and later next to Amps.\n\n" +
                    "1. Type the make and model, for example Boss and Katana 50 MkII.\n" +
                    "2. Tap Fill in with the coach. It drafts the knobs in panel order, which knob is only " +
                    "loudness, settings that live in the amp's screen, its amp models and effects, and your " +
                    "guitar's pickups.\n" +
                    "3. Check the draft against the real thing and tap Use these. Fix anything that's off.\n" +
                    "4. Tap Save.\n\n" +
                    "No AI key? Start from a known model if yours is listed, or type the knobs in yourself. " +
                    "The first guitar and amp you save become your rig.",
            ),
            Faq(
                "How do I see my gear's details?",
                "Tap any card: the rig, a guitar, the amp or a pedal. Its page shows everything Riff Buddy knows " +
                    "about it: the amp's knobs as a panel, its amp models and effects, the guitar's pickups.\n\n" +
                    "The pencil at the top edits it, and ⋮ has Delete (and Make default, for a rig).",
            ),
            Faq(
                "What's a rig?",
                "A guitar, an amp and any pedals you play together, drawn as a chain in the order the signal runs. " +
                    "Tone cards are made for your default rig, the one marked DEFAULT. Tap a box in the rig's chain " +
                    "to open that piece of gear.",
            ),
            Faq(
                "My amp has different knobs. How do I fix them?",
                "Open the amp and tap the pencil. Add, remove or reorder its controls, left to right as they are on the panel. " +
                    "Fill in with the coach can draft them from the model name if you have an AI key.",
            ),
            Faq(
                "Do pedals go in a certain order?",
                "Yes. Tick them in the order your signal goes through them, first to last.",
            ),
            Faq(
                "What happens to tone cards if I delete a rig?",
                "Riff Buddy asks. Keep the cards archived on their songs, or delete them with the rig.",
            ),
        ),

        HelpTopic.SETTINGS to listOf(
            Faq(
                "How do I get a free AI key?",
                "In AI coach, pick a provider and tap Add a key, then Get a free key. It opens the right page; " +
                    "sign in, create a key, copy it, come back and paste it. Then tap Test connection.\n\n" +
                    "• Groq — the easiest start, and its recommended model (gpt-oss-120b) is the smartest free one.\n" +
                    "• Gemini — Google's; a good second key.\n" +
                    "• Mistral — free Experiment plan; asks to verify a phone number.\n" +
                    "• OpenRouter — 50 free requests a day across many free models.\n\n" +
                    "None needs a card. One key is enough; two lets the coach switch when one is busy.",
            ),
            Faq(
                "Does the app need the internet?",
                "Only the coach and the Songsterr link. Everything else — metronome, tuner, songbook, log — " +
                    "works with no network at all.",
            ),
            Faq(
                "How do I back up my practice history?",
                "Under Your data, tap Export and save the file somewhere safe. Import restores it. Your API " +
                    "keys are never in the file.",
            ),
            Faq(
                if (Device.onComputer) "How do I keep this computer and a phone in step?"
                else "How do I keep a tablet and a phone in step?",
                "Export on the device you practice on, send the file across " +
                    (if (Device.onComputer) "(a USB cable, email or a shared folder works)" else "(Quick Share works)") +
                    ", and Import " +
                    "it on the other.\n\n" +
                    "• Replace all makes the other device an exact copy. Best when you only log " +
                    "practice on one of them.\n" +
                    "• Add to what is here keeps what's already there, adds what's new, and takes the backup's " +
                    "version of anything edited more recently in it.\n\n" +
                    "Adding songs, tabs or sessions on both devices is fine: Add to what is here keeps both " +
                    "devices' additions, and importing the same backup twice doesn't duplicate anything.",
            ),
            Faq(
                "How do I get a practice reminder?",
                "Turn on Practice reminder and pick a time. It can skip days you've already practiced.",
            ),
            Faq(
                "Can I turn the animations off?",
                if (Device.onComputer) "Yes. Your computer's own setting to reduce or turn off animations turns off " +
                    "Riff Buddy's motion too."
                else "Yes. Android's Remove animations setting turns off Riff Buddy's motion too.",
            ),
            Faq(
                "How do I keep the screen on while I play?",
                "Turn on Keep the screen awake. It applies while the metronome plays or a session runs.",
            ),
        ),
    )
}
