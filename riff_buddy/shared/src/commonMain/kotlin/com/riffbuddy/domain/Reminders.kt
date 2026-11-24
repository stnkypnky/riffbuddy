package com.riffbuddy.domain

import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toInstant

/** The practice reminder, as the player sets it. */
data class ReminderSettings(
    val enabled: Boolean = false,
    val time: LocalTime = LocalTime(18, 30),
    val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    /** No reminder fires if a session was already logged that day. */
    val skipIfPracticed: Boolean = true,
) {
    fun encodeDays(): String = days.sortedBy { it.isoDayNumber }.joinToString(",") { it.isoDayNumber.toString() }

    companion object {
        fun decodeDays(text: String?): Set<DayOfWeek> =
            text?.split(",")?.mapNotNull { it.trim().toIntOrNull()?.takeIf { d -> d in 1..7 }?.let(::DayOfWeek) }?.toSet()
                ?: DayOfWeek.entries.toSet()
    }
}

/**
 * When the reminder should next go off. Pure, so the day and clock
 * arithmetic — the part that is easy to get subtly wrong — is tested.
 *
 * Worked in local dates and times, then turned into an instant: "18:30 on
 * Tuesdays" means 18:30 on the wall clock, including in the week the clocks
 * change. If a daylight-saving jump skips the chosen time outright, the
 * reminder comes at the first valid moment after it rather than not at all.
 */
object ReminderSchedule {

    fun nextFire(settings: ReminderSettings, now: Instant, zone: TimeZone): Instant? {
        if (!settings.enabled || settings.days.isEmpty()) return null
        for (offset in 0..7L) {
            val date: LocalDate = now.localDate(zone).plusDays(offset)
            if (date.dayOfWeek !in settings.days) continue
            // toInstant resolves a gap by moving forward — the "first valid moment".
            val candidate = date.atTime(settings.time).toInstant(zone)
            if (candidate > now) return candidate
        }
        return null
    }

    /** Whether a reminder that has just come due should actually show. */
    fun shouldShow(settings: ReminderSettings, practicedToday: Boolean): Boolean =
        settings.enabled && !(settings.skipIfPracticed && practicedToday)

    /**
     * The backup reminder: nudged alongside a practice reminder
     * once the last backup is 30 days old — and then no more than weekly, so
     * a nudge does not become a nag.
     */
    fun backupNudgeDue(lastBackupMillis: Long?, lastNudgeMillis: Long?, nowMillis: Long): Boolean {
        val day = 86_400_000L
        if (lastBackupMillis != null && nowMillis - lastBackupMillis < 30 * day) return false
        return lastNudgeMillis == null || nowMillis - lastNudgeMillis >= 7 * day
    }

    fun zone(): TimeZone = localZone()
}
