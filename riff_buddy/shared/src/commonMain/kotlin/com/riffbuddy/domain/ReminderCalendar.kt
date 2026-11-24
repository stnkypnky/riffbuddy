package com.riffbuddy.domain

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/**
 * The practice reminder as a calendar event (an .ics file), repeating on the
 * chosen days at the chosen time with an alert. For an edition that can't
 * remind anyone while it is closed: the device's own calendar can, and keeps
 * doing it. The time is written as local wall-clock time, so it stays 18:30
 * through daylight saving, as the app's own reminder does.
 */
object ReminderCalendar {

    const val FILE_NAME = "riff-buddy-practice.ics"

    /** The event, or null when the reminder is off or has no days. */
    fun ics(settings: ReminderSettings, now: Instant, zone: TimeZone): String? {
        val first = ReminderSchedule.nextFire(settings, now, zone)?.local(zone) ?: return null
        val utc = now.local(TimeZone.UTC)
        val days = settings.days.sortedBy { it.ordinal }.joinToString(",") { CODES.getValue(it) }
        return listOf(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//Riff Buddy//Practice reminder//EN",
            "BEGIN:VEVENT",
            // The same UID every time, so a second export updates the event rather than adding another.
            "UID:practice-reminder@riffbuddy",
            "DTSTAMP:${utc.format("yyyyMMdd")}T${utc.format("HHmm")}00Z",
            "DTSTART:${first.format("yyyyMMdd")}T${first.format("HHmm")}00",
            "DURATION:PT15M",
            "RRULE:FREQ=WEEKLY;BYDAY=$days",
            "SUMMARY:Practice guitar",
            "DESCRIPTION:Open Riff Buddy and pick the guitar up. Five minutes counts.",
            "BEGIN:VALARM",
            "TRIGGER:PT0M",
            "ACTION:DISPLAY",
            "DESCRIPTION:Practice guitar",
            "END:VALARM",
            "END:VEVENT",
            "END:VCALENDAR",
        ).joinToString("\r\n", postfix = "\r\n")
    }

    private val CODES = mapOf(
        DayOfWeek.MONDAY to "MO", DayOfWeek.TUESDAY to "TU", DayOfWeek.WEDNESDAY to "WE", DayOfWeek.THURSDAY to "TH",
        DayOfWeek.FRIDAY to "FR", DayOfWeek.SATURDAY to "SA", DayOfWeek.SUNDAY to "SU",
    )
}
