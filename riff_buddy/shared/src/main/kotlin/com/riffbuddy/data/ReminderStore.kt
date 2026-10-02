package com.riffbuddy.data

import com.riffbuddy.domain.ReminderSchedule
import com.riffbuddy.domain.ReminderSettings
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** The practice reminder's settings, kept in the settings table like every other setting. */
object ReminderStore {

    suspend fun load(repo: Repo): ReminderSettings = ReminderSettings(
        enabled = repo.setting(Repo.REMINDER_ENABLED) == "true",
        time = repo.setting(Repo.REMINDER_TIME)?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: LocalTime.of(18, 30),
        days = ReminderSettings.decodeDays(repo.setting(Repo.REMINDER_DAYS)),
        skipIfPracticed = repo.setting(Repo.REMINDER_SKIP_PRACTICED) != "false",
    )

    suspend fun save(repo: Repo, settings: ReminderSettings) {
        repo.putSetting(Repo.REMINDER_ENABLED, settings.enabled.toString())
        repo.putSetting(Repo.REMINDER_TIME, settings.time.toString())
        repo.putSetting(Repo.REMINDER_DAYS, settings.encodeDays())
        repo.putSetting(Repo.REMINDER_SKIP_PRACTICED, settings.skipIfPracticed.toString())
    }

    /** One notification to post: a short title and a line under it. */
    data class Notice(val kind: Kind, val title: String, val text: String) {
        enum class Kind { PRACTICE, BACKUP }
    }

    /**
     * What to post when the reminder goes off, if anything. The practice
     * reminder stays quiet on a day already practiced (when asked to), and the
     * backup nudge rides along at most weekly once a backup is 30 days old —
     * recorded here, so the next check knows it was given.
     */
    suspend fun dueNotices(repo: Repo, sessions: SessionRepo, nowMillis: Long = System.currentTimeMillis()): List<Notice> {
        val settings = load(repo)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val practicedToday = sessions.all().any { it.startedAt.atZone(zone).toLocalDate() == today }
        val notices = mutableListOf<Notice>()
        if (ReminderSchedule.shouldShow(settings, practicedToday)) {
            notices += Notice(Notice.Kind.PRACTICE, "Time to play", "Even five minutes keeps the streak going.")
        }
        val lastBackup = repo.setting(Repo.LAST_BACKUP_AT)?.toLongOrNull()
        val lastNudge = repo.setting(Repo.BACKUP_NUDGED_AT)?.toLongOrNull()
        if (settings.enabled && ReminderSchedule.backupNudgeDue(lastBackup, lastNudge, nowMillis)) {
            notices += Notice(
                Notice.Kind.BACKUP, "Back up your practice",
                "Songs, sessions, takes and tabs live only on this device. Me › Settings › Back up.",
            )
            repo.putSetting(Repo.BACKUP_NUDGED_AT, nowMillis.toString())
        }
        return notices
    }
}
