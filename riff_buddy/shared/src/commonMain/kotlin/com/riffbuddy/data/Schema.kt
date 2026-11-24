package com.riffbuddy.data

/**
 * The database as SQL, kept apart from [Db] so it can be read by a plain JVM
 * unit test — no device, no Robolectric. SchemaTest is what stops a column being
 * added here and forgotten in the export, or an API key ending up in a table
 * it has no business being in.
 *
 * Conventions, applied throughout:
 *  - Times are epoch milliseconds in INTEGER columns. The session timer has
 *    to survive the screen locking, so durations are always derived
 *    from two timestamps rather than counted up while the app happens to be
 *    awake.
 *  - Anything the AI produced, or that is genuinely free-form (a control list, a
 *    tone card), is JSON in a TEXT column. Values that get filtered or sorted on
 *    are real columns.
 *  - No table holds an API key. Those live in secure storage, and the backup
 *    export is built from this database — which is what keeps keys out of
 *    backups by construction rather than by remembering to strip them.
 */
internal object Schema {

    const val VERSION = 12

    /**
     * Keyed by the version being upgraded *from*: `MIGRATIONS[1]` takes a v1
     * database to v2. Applied in order, so upgrading a two-year-old install runs
     * the same code as upgrading yesterday's.
     *
     * Migrations only ever add. A column that turns out to be wrong stays and is
     * ignored — `ALTER TABLE … DROP COLUMN` needs SQLite 3.35, and Android 9
     * devices ship 3.22.
     */
    val MIGRATIONS: Map<Int, List<String>> = mapOf(
        1 to listOf(
            // ---- Tab library ----------------------------------------------------
            // One row per imported file. The bytes live in app storage under
            // stored_name; this row is everything the app knows about them.
            //
            // song_id is SET NULL on delete for the same reason session items
            // are: deleting a song should not throw away a file the player imported.
            //
            // The last four columns remember position, speed and loop per
            // file, and they are columns rather than JSON because the
            // player reads them on every open. Loop bars are 1-based, as a
            // player counts them; null means no loop.
            """
            CREATE TABLE tab_files (
                id               INTEGER PRIMARY KEY AUTOINCREMENT,
                song_id          INTEGER REFERENCES songs(id) ON DELETE SET NULL,
                name             TEXT    NOT NULL,
                original_name    TEXT    NOT NULL,
                stored_name      TEXT    NOT NULL UNIQUE,
                size_bytes       INTEGER NOT NULL,
                sha256           TEXT    NOT NULL,
                title            TEXT    NOT NULL DEFAULT '',
                artist           TEXT    NOT NULL DEFAULT '',
                track_count      INTEGER NOT NULL DEFAULT 0,
                bar_count        INTEGER NOT NULL DEFAULT 0,
                position_ms      INTEGER NOT NULL DEFAULT 0,
                speed_percent    INTEGER NOT NULL DEFAULT 100,
                loop_start_bar   INTEGER,
                loop_end_bar     INTEGER,
                created_at       INTEGER NOT NULL,
                updated_at       INTEGER NOT NULL
            )
            """,
            "CREATE INDEX idx_tab_files_song ON tab_files(song_id)",
        ),
        2 to listOf(
            // ---- Looper ---------------------------------------------------------
            // A *reference* to a recording on the player's device, not a copy:
            // tab files are kilobytes, songs are megabytes, and a second copy
            // of their music library inside the app
            // would be a bad trade for a practice tool. The cost is that a file
            // can move, which the looper detects and offers to re-find.
            //
            // uri is a content:// URI with a persisted read grant. The last
            // five columns are what the looper remembers per file.
            """
            CREATE TABLE audio_files (
                id               INTEGER PRIMARY KEY AUTOINCREMENT,
                song_id          INTEGER REFERENCES songs(id) ON DELETE SET NULL,
                name             TEXT    NOT NULL,
                original_name    TEXT    NOT NULL,
                uri              TEXT    NOT NULL,
                size_bytes       INTEGER NOT NULL DEFAULT 0,
                duration_ms      INTEGER NOT NULL DEFAULT 0,
                bpm              INTEGER,
                position_ms      INTEGER NOT NULL DEFAULT 0,
                speed_percent    INTEGER NOT NULL DEFAULT 100,
                loop_start_ms    INTEGER,
                loop_end_ms      INTEGER,
                volume_percent   INTEGER NOT NULL DEFAULT 100,
                created_at       INTEGER NOT NULL,
                updated_at       INTEGER NOT NULL
            )
            """,
            "CREATE INDEX idx_audio_files_song ON audio_files(song_id)",
            // ---- Saved loops ----------------------------------------------------
            // Named loops, several per file, for the quick-jump list. A loop
            // belongs to exactly one audio file or one tab file — the CHECK says
            // so — and goes when its file goes. Units follow the file: ms for
            // audio, 1-based bars for tabs, which is how each player counts.
            """
            CREATE TABLE saved_loops (
                id               INTEGER PRIMARY KEY AUTOINCREMENT,
                audio_id         INTEGER REFERENCES audio_files(id) ON DELETE CASCADE,
                tab_id           INTEGER REFERENCES tab_files(id) ON DELETE CASCADE,
                name             TEXT    NOT NULL,
                start_value      INTEGER NOT NULL,
                end_value        INTEGER NOT NULL,
                speed_percent    INTEGER NOT NULL DEFAULT 100,
                created_at       INTEGER NOT NULL,
                updated_at       INTEGER NOT NULL,
                CHECK ((audio_id IS NULL) <> (tab_id IS NULL))
            )
            """,
            "CREATE INDEX idx_saved_loops_audio ON saved_loops(audio_id)",
            "CREATE INDEX idx_saved_loops_tab ON saved_loops(tab_id)",
        ),
        3 to listOf(
            // ---- Recorder -------------------------------------------------------
            // One row per take. The WAV itself is in app storage under
            // stored_name; the row is created when recording *starts*,
            // so a take cut off by a crash still has a row, and its file is
            // repaired on the next launch rather than orphaned.
            //
            // latency_offset_ms is the calibration in force when the take was
            // made, stored with each take, so recalibrating later does
            // not silently move old takes. align_ms is where, in the take, a
            // play-along source began — null for a take made on its own.
            //
            // Song and session are SET NULL: deleting either should not delete
            // a take the player recorded.
            """
            CREATE TABLE recordings (
                id                INTEGER PRIMARY KEY AUTOINCREMENT,
                song_id           INTEGER REFERENCES songs(id) ON DELETE SET NULL,
                session_id        INTEGER REFERENCES sessions(id) ON DELETE SET NULL,
                name              TEXT    NOT NULL,
                stored_name       TEXT    NOT NULL UNIQUE,
                duration_ms       INTEGER NOT NULL DEFAULT 0,
                size_bytes        INTEGER NOT NULL DEFAULT 0,
                sample_rate       INTEGER NOT NULL,
                latency_offset_ms INTEGER NOT NULL DEFAULT 0,
                align_ms          INTEGER,
                source            TEXT    NOT NULL DEFAULT 'none',
                starred           INTEGER NOT NULL DEFAULT 0,
                rating            INTEGER,
                notes             TEXT    NOT NULL DEFAULT '',
                created_at        INTEGER NOT NULL,
                updated_at        INTEGER NOT NULL
            )
            """,
            "CREATE INDEX idx_recordings_song ON recordings(song_id)",
        ),
        4 to listOf(
            // ---- Backing tracks -------------------------------------------------
            // Only the recipe is stored — key, chart, style, tempo — as JSON, and
            // the score is regenerated from it. The generator is deterministic,
            // so a saved track reloads identically, and a better
            // generator later improves every saved track at once.
            """
            CREATE TABLE backing_tracks (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                song_id     INTEGER REFERENCES songs(id) ON DELETE SET NULL,
                name        TEXT    NOT NULL,
                spec_json   TEXT    NOT NULL,
                created_at  INTEGER NOT NULL,
                updated_at  INTEGER NOT NULL
            )
            """,
            "CREATE INDEX idx_backing_tracks_song ON backing_tracks(song_id)",
        ),
        5 to listOf(
            // ---- Lessons and homework -------------------------------------------
            """
            CREATE TABLE lessons (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                lesson_date INTEGER NOT NULL,
                teacher     TEXT    NOT NULL DEFAULT '',
                notes       TEXT    NOT NULL DEFAULT '',
                created_at  INTEGER NOT NULL,
                updated_at  INTEGER NOT NULL
            )
            """,
            // Homework outlives both its lesson and its song, SET NULL on each:
            // deleting a linked song keeps the homework text, and an
            // item still open should not vanish because an old lesson was
            // tidied away — unfinished work carries over.
            """
            CREATE TABLE homework (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                lesson_id   INTEGER REFERENCES lessons(id) ON DELETE SET NULL,
                song_id     INTEGER REFERENCES songs(id) ON DELETE SET NULL,
                text        TEXT    NOT NULL,
                due_at      INTEGER,
                done_at     INTEGER,
                created_at  INTEGER NOT NULL,
                updated_at  INTEGER NOT NULL
            )
            """,
            "CREATE INDEX idx_homework_open ON homework(done_at)",
            // ---- Routines -------------------------------------------------------
            // Only the player's own routines are rows. The three starter
            // templates live in code and are *copied* in when chosen, as
            // editable copies, so editing one never changes the template.
            """
            CREATE TABLE routines (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                name        TEXT    NOT NULL,
                created_at  INTEGER NOT NULL,
                updated_at  INTEGER NOT NULL
            )
            """,
            // link is the step's optional tool: "song:<id>", "scale:<root>:<SCALE>",
            // "preset:<id>" or "" — a string, so a link to something deleted
            // simply fails to resolve instead of breaking the step.
            """
            CREATE TABLE routine_steps (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                routine_id  INTEGER NOT NULL REFERENCES routines(id) ON DELETE CASCADE,
                position    INTEGER NOT NULL,
                kind        TEXT    NOT NULL,
                title       TEXT    NOT NULL,
                minutes     INTEGER NOT NULL,
                link        TEXT    NOT NULL DEFAULT ''
            )
            """,
            "CREATE INDEX idx_routine_steps_routine ON routine_steps(routine_id, position)",
        ),
        6 to listOf(
            // ---- Session pause --------------------------------------------------
            // The timer is two timestamps; a pause is two more. Time
            // paused is kept as a running total plus the start of a pause in
            // progress, so a session paused when the app was killed is still
            // paused, correctly, on the next launch. ADD COLUMN only.
            "ALTER TABLE sessions ADD COLUMN paused_seconds INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE sessions ADD COLUMN paused_at INTEGER",
        ),
        7 to listOf(
            // ---- Song mastery and stats -----------------------------------------
            // "Retired" became "Mastered". No UPDATE here: migrations only
            // add (SchemaTest holds that line). Old rows still say RETIRED and
            // SongStatus.fromName reads that as MASTERED; saving a song writes
            // the new name.
            // When it was mastered, for "days to master" and the celebration.
            "ALTER TABLE songs ADD COLUMN mastered_at INTEGER",
            // The parts of a song (Intro, Solo…), each moving through stages
            // from not started to mastered. Customizable per song, so a table.
            """
            CREATE TABLE song_parts (
                id               INTEGER PRIMARY KEY AUTOINCREMENT,
                song_id          INTEGER NOT NULL REFERENCES songs(id) ON DELETE CASCADE,
                name             TEXT    NOT NULL,
                position         INTEGER NOT NULL DEFAULT 0,
                stage            TEXT    NOT NULL DEFAULT 'NOT_STARTED',
                stage_changed_at INTEGER,
                mastered_at      INTEGER,
                created_at       INTEGER NOT NULL,
                updated_at       INTEGER NOT NULL
            )
            """,
            "CREATE INDEX idx_song_parts_song ON song_parts(song_id, position)",
            // Session time can be tagged with the part it went on. SET NULL, so
            // deleting a part keeps the minutes on the song.
            "ALTER TABLE session_items ADD COLUMN part_id INTEGER REFERENCES song_parts(id) ON DELETE SET NULL",
            // Milestones earned, so each is celebrated once.
            """
            CREATE TABLE milestones (
                key         TEXT    PRIMARY KEY,
                earned_at   INTEGER NOT NULL,
                celebrated  INTEGER NOT NULL DEFAULT 0
            )
            """,
        ),
        8 to listOf(
            // How each tab file's player was left: track mutes, solos and
            // volumes, the shown track, zoom, layout, count-in and click
            // settings. TabPrefs' own short text; null for an untouched file.
            "ALTER TABLE tab_files ADD COLUMN player_prefs TEXT",
        ),
        9 to listOf(
            // What a tone card needs to know about an amp beyond its controls:
            // the app that edits its presets (so the card can say where a
            // display-only setting lives) and how many effects of one kind a
            // preset holds. JSON, read with defaults, so an older row is fine.
            "ALTER TABLE amps ADD COLUMN details_json TEXT",
        ),
        10 to listOf(
            // ---- The player's own scales and shapes -----------------------------
            // kind is SCALE (a set of notes, drawn everywhere on the neck) or
            // DOTS (exact strings and frets). notes is CustomShapes' own short
            // text. A table rather than a setting, so a backup merge can tell
            // one shape from another by its creation time and carry an edit
            // made on another device.
            """
            CREATE TABLE custom_shapes (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                name        TEXT    NOT NULL,
                kind        TEXT    NOT NULL,
                root        INTEGER NOT NULL,
                notes       TEXT    NOT NULL,
                tuning      TEXT,
                created_at  INTEGER NOT NULL,
                updated_at  INTEGER NOT NULL
            )
            """,
        ),
        11 to listOf(
            // ---- Deletions that travel ----------------------------------------
            // A merge only ever adds, so an item deleted here would come back
            // from the next device that still had it. Each deletion leaves a
            // note of what was deleted (by the same identity a merge matches
            // on) and when, and the note travels in backups and syncs so the
            // other device can delete its copy too.
            //
            // Written by triggers rather than by each repository's delete, so
            // no delete path can forget, including rows removed by a cascade.
            """
            CREATE TABLE deletions (
                table_name  TEXT    NOT NULL,
                identity    TEXT    NOT NULL,
                deleted_at  INTEGER NOT NULL,
                PRIMARY KEY (table_name, identity)
            )
            """,
            // A session's items change after it starts (the session ends,
            // songs are tagged, the log is edited), and a merge only carries a
            // change it can see is newer. Null on older rows, which a merge
            // reads as "no newer version", as it did before.
            "ALTER TABLE sessions ADD COLUMN updated_at INTEGER",
        ) + DELETION_TRIGGERS_V12.flatMap { (table, column) -> deletionTriggers(table, column) },
    )

    /**
     * The tables whose deletions are recorded, and the column a merge knows
     * each item by. Frozen as of this migration: a later table gets its own
     * trigger in its own migration, and `SchemaTest` checks none is missing.
     */
    val DELETION_TRIGGERS_V12: List<Pair<String, String>> get() = listOf(
        "tunings" to "name", "songs" to "created_at", "song_parts" to "created_at",
        "sessions" to "started_at", "tone_cards" to "created_at", "ai_messages" to "created_at",
        "tab_files" to "sha256", "audio_files" to "created_at", "saved_loops" to "created_at",
        "recordings" to "created_at", "backing_tracks" to "created_at", "lessons" to "created_at",
        "homework" to "created_at", "routines" to "created_at", "custom_shapes" to "created_at",
    )

    /**
     * Milliseconds since 1970 in SQLite's own terms, as the app writes its
     * times: julianday counts days from 4714 BC, and 2440587.5 is 1970.
     * INSERT OR REPLACE, so an item deleted, restored and deleted again
     * keeps the later time.
     *
     * The second trigger is for an identity that changes: a session whose
     * start time is corrected in the log, a tuning renamed. To another
     * device that is a new item, so the old one is noted as deleted, or it
     * would stay there beside the new one.
     */
    private fun deletionTriggers(table: String, column: String): List<String> {
        val note = """
            INSERT OR REPLACE INTO deletions (table_name, identity, deleted_at)
            VALUES ('$table', CAST(OLD.$column AS TEXT),
                    CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER));
        """
        return listOf(
            "CREATE TRIGGER ${table}_deleted AFTER DELETE ON $table BEGIN $note END",
            "CREATE TRIGGER ${table}_renamed AFTER UPDATE OF $column ON $table " +
                "WHEN OLD.$column IS NOT NEW.$column BEGIN $note END",
        )
    }

    /**
     * What a brand-new install runs: v1, then every migration.
     *
     * Not "the latest schema written out in full". If fresh installs had their
     * own CREATE statements, a fresh database and an upgraded one would be two
     * schemas that are *meant* to match — and nothing would check. Building one
     * from the other makes them the same SQL by construction, and
     * MigrationTest compares the two anyway.
     */
    fun fresh(): List<String> =
        V1 + (1 until VERSION).flatMap { MIGRATIONS[it] ?: error("no migration from v$it") }

    val V1: List<String> = listOf(
            // ---- Reference data -------------------------------------------------
            // The built-in tunings plus any the player defines.
            // Shipped rows are seeded from a bundled asset rather than written
            // here, so the same list can be unit-tested against interval math.
            """
            CREATE TABLE tunings (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                name          TEXT    NOT NULL,
                grp           TEXT    NOT NULL,
                strings       TEXT    NOT NULL,
                string_count  INTEGER NOT NULL,
                is_custom     INTEGER NOT NULL DEFAULT 0,
                UNIQUE(name)
            )
            """,

            // ---- Songbook -------------------------------------------------------
            """
            CREATE TABLE songs (
                id                INTEGER PRIMARY KEY AUTOINCREMENT,
                title             TEXT    NOT NULL,
                artist            TEXT    NOT NULL DEFAULT '',
                status            TEXT    NOT NULL DEFAULT 'wishlist',
                tuning_id         INTEGER REFERENCES tunings(id) ON DELETE SET NULL,
                capo              INTEGER NOT NULL DEFAULT 0,
                song_key          TEXT,
                time_sig          TEXT,
                target_bpm        INTEGER,
                current_bpm       INTEGER,
                self_difficulty   INTEGER,
                ai_difficulty_json TEXT,
                tags              TEXT    NOT NULL DEFAULT '',
                notes             TEXT    NOT NULL DEFAULT '',
                songsterr_url     TEXT,
                created_at        INTEGER NOT NULL,
                updated_at        INTEGER NOT NULL
            )
            """,
            "CREATE INDEX idx_songs_status ON songs(status)",

            // ---- Practice log ---------------------------------------------------
            // ended_at NULL is an in-flight session, which is how the app offers
            // to recover one after it is killed mid-practice.
            """
            CREATE TABLE sessions (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at   INTEGER NOT NULL,
                ended_at     INTEGER,
                duration_sec INTEGER NOT NULL DEFAULT 0,
                mode         TEXT    NOT NULL DEFAULT 'free',
                rating       INTEGER,
                notes        TEXT    NOT NULL DEFAULT ''
            )
            """,
            "CREATE INDEX idx_sessions_started ON sessions(started_at)",
            """
            CREATE TABLE session_items (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id    INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                song_id       INTEGER REFERENCES songs(id) ON DELETE SET NULL,
                exercise_name TEXT,
                seconds       INTEGER NOT NULL DEFAULT 0,
                bpm_start     INTEGER,
                bpm_end       INTEGER,
                notes         TEXT    NOT NULL DEFAULT ''
            )
            """,
            "CREATE INDEX idx_items_session ON session_items(session_id)",
            // Per-song totals and the BPM-over-time chart both read by song.
            "CREATE INDEX idx_items_song ON session_items(song_id)",

            // ---- Gear -----------------------------------------------------------
            // controls_json is an ordered list of {name, kind, min, max, options}.
            // Ordered because the amp faceplate is drawn in panel order: on a
            // real amp the knobs run left to right and the card should match
            // what the player is looking at.
            """
            CREATE TABLE guitars (
                id                INTEGER PRIMARY KEY AUTOINCREMENT,
                name              TEXT    NOT NULL,
                make              TEXT    NOT NULL DEFAULT '',
                model             TEXT    NOT NULL DEFAULT '',
                string_count      INTEGER NOT NULL DEFAULT 6,
                default_tuning_id INTEGER REFERENCES tunings(id) ON DELETE SET NULL,
                pickups_json      TEXT    NOT NULL DEFAULT '[]',
                controls_json     TEXT    NOT NULL DEFAULT '[]',
                notes             TEXT    NOT NULL DEFAULT ''
            )
            """,
            // type is 'analog' | 'modeling' | 'hybrid'. It is not decoration: a
            // modeling amp's tone card has to name an amp model and a preset
            // slot, which a knob amp has no room for.
            """
            CREATE TABLE amps (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                name          TEXT    NOT NULL,
                make          TEXT    NOT NULL DEFAULT '',
                model         TEXT    NOT NULL DEFAULT '',
                type          TEXT    NOT NULL DEFAULT 'analog',
                channels_json TEXT    NOT NULL DEFAULT '[]',
                controls_json TEXT    NOT NULL DEFAULT '[]',
                models_json   TEXT    NOT NULL DEFAULT '[]',
                effects_json  TEXT    NOT NULL DEFAULT '[]',
                notes         TEXT    NOT NULL DEFAULT ''
            )
            """,
            """
            CREATE TABLE pedals (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                name          TEXT    NOT NULL,
                make          TEXT    NOT NULL DEFAULT '',
                model         TEXT    NOT NULL DEFAULT '',
                type          TEXT    NOT NULL DEFAULT '',
                controls_json TEXT    NOT NULL DEFAULT '[]',
                notes         TEXT    NOT NULL DEFAULT ''
            )
            """,
            // A rig deleted out from under a tone card archives the card rather
            // than dropping it, so rig_id is ON DELETE SET NULL below
            // and the card keeps the gear description it was generated against.
            """
            CREATE TABLE rigs (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                name       TEXT    NOT NULL,
                guitar_id  INTEGER REFERENCES guitars(id) ON DELETE SET NULL,
                amp_id     INTEGER REFERENCES amps(id) ON DELETE SET NULL,
                pedal_ids  TEXT    NOT NULL DEFAULT '',
                is_default INTEGER NOT NULL DEFAULT 0
            )
            """,

            // ---- AI -------------------------------------------------------------
            // provider, model, prompt_version and response_ms are stored on every
            // card because when a card is wrong the first question is which
            // model produced it.
            """
            CREATE TABLE tone_cards (
                id             INTEGER PRIMARY KEY AUTOINCREMENT,
                song_id        INTEGER NOT NULL REFERENCES songs(id) ON DELETE CASCADE,
                rig_id         INTEGER REFERENCES rigs(id) ON DELETE SET NULL,
                part           TEXT    NOT NULL DEFAULT 'rhythm',
                version        INTEGER NOT NULL DEFAULT 1,
                is_starred     INTEGER NOT NULL DEFAULT 0,
                archived       INTEGER NOT NULL DEFAULT 0,
                prompt_version TEXT    NOT NULL DEFAULT '',
                provider       TEXT    NOT NULL DEFAULT '',
                model          TEXT    NOT NULL DEFAULT '',
                response_ms    INTEGER,
                content_json   TEXT    NOT NULL,
                feedback       TEXT    NOT NULL DEFAULT '',
                useful         INTEGER,
                created_at     INTEGER NOT NULL
            )
            """,
            "CREATE INDEX idx_cards_song ON tone_cards(song_id)",
            """
            CREATE TABLE ai_messages (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                conversation_id TEXT    NOT NULL,
                role            TEXT    NOT NULL,
                content         TEXT    NOT NULL,
                provider        TEXT    NOT NULL DEFAULT '',
                model           TEXT    NOT NULL DEFAULT '',
                created_at      INTEGER NOT NULL
            )
            """,
            "CREATE INDEX idx_msgs_convo ON ai_messages(conversation_id)",
            // An identical request must make no network call. The key is a
            // hash of what actually went into the prompt, prompt version included,
            // so editing a prompt template invalidates its cache for free.
            """
            CREATE TABLE ai_cache (
                cache_key    TEXT    PRIMARY KEY,
                feature      TEXT    NOT NULL,
                content_json TEXT    NOT NULL,
                provider     TEXT    NOT NULL DEFAULT '',
                model        TEXT    NOT NULL DEFAULT '',
                created_at   INTEGER NOT NULL
            )
            """,
            // Usage counters, per provider per local day. Free-tier limits
            // themselves are configuration, not constants, so they are not
            // in this table — only what has been spent.
            """
            CREATE TABLE ai_usage (
                provider TEXT    NOT NULL,
                day      TEXT    NOT NULL,
                requests INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (provider, day)
            )
            """,

            // ---- Tools ----------------------------------------------------------
            """
            CREATE TABLE metronome_presets (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                name        TEXT    NOT NULL,
                bpm         INTEGER NOT NULL,
                time_sig    TEXT    NOT NULL DEFAULT '4/4',
                accents     TEXT    NOT NULL DEFAULT '',
                subdivision TEXT    NOT NULL DEFAULT 'none',
                sound       TEXT    NOT NULL DEFAULT 'click'
            )
            """,

            // ---- Settings -------------------------------------------------------
            // Secrets excluded by design; see the class comment.
            """
            CREATE TABLE settings (
                key   TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """,
    )
}
