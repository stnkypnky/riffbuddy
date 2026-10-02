package com.riffbuddy.reminders

import android.app.AlarmManager
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.riffbuddy.MainActivity
import com.riffbuddy.R
import com.riffbuddy.RiffBuddyApp
import com.riffbuddy.data.Repo
import com.riffbuddy.domain.ReminderSchedule
import com.riffbuddy.domain.ReminderSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The reminders, on AlarmManager.
 *
 * **Inexact on purpose.** An exact alarm needs a special permission since
 * Android 12 and wakes the device at a precise second; a practice reminder
 * does not need either. `setWindow` asks for a ten-minute window, which the
 * system honors without that permission and which Doze respects — the
 * "fires on time (allowing for Android battery optimization)".
 *
 * **One alarm at a time.** Each firing schedules the next, and anything that
 * could have lost the alarm (a reboot, an app update, a settings change)
 * schedules it again from the saved settings. Turning reminders off cancels
 * the pending alarm — the spec AC: "turning reminders off cancels all scheduled
 * notifications".
 */
object Reminders {

    const val CHANNEL = "practice_reminders"
    private const val PRACTICE_ID = 1001
    private const val BACKUP_ID = 1002
    private const val TAG = "RiffReminders"

    suspend fun load(repo: Repo): ReminderSettings = com.riffbuddy.data.ReminderStore.load(repo)

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, ReminderReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Schedules the next reminder, or cancels it when there is none. Returns when it will fire. */
    fun schedule(context: Context, settings: ReminderSettings): ZonedDateTime? {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return null
        val next = ReminderSchedule.nextFire(settings, ZonedDateTime.now(ZoneId.systemDefault()))
        if (next == null) {
            alarms.cancel(pending(context))
            Log.i(TAG, "reminders off: alarm canceled")
            return null
        }
        alarms.setWindow(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), 10 * 60_000L, pending(context))
        Log.i(TAG, "next reminder at $next")
        return next
    }

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Practice reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "The reminder you set, and the occasional nudge to back up"
                }
            )
        }
    }

    internal fun notify(context: Context, id: Int, title: String, text: String) {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        // Checked, not assumed: the permission (Android 13+) can be withdrawn
        // in system settings at any time, and then there is nothing to post —
        // which is what the player asked for by withdrawing it.
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed || !NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Log.i(TAG, "notifications not allowed; nothing posted")
            return
        }
        NotificationManagerCompat.from(context).notify(id, n)
    }

    /** What a firing does, run off the main thread by the receiver. */
    internal suspend fun fire(context: Context) {
        val app = context.applicationContext as RiffBuddyApp
        val notices = com.riffbuddy.data.ReminderStore.dueNotices(app.services.repo, app.services.sessions)
        for (n in notices) {
            val id = if (n.kind == com.riffbuddy.data.ReminderStore.Notice.Kind.PRACTICE) PRACTICE_ID else BACKUP_ID
            notify(context, id, n.title, n.text)
        }
        Log.i(TAG, "reminder fired: ${notices.size} notice(s)")
        schedule(context, load(app.services.repo))
    }

    /** Re-arms from saved settings — after a reboot, an update, or on launch. */
    suspend fun rearm(context: Context) {
        val app = context.applicationContext as RiffBuddyApp
        schedule(context, load(app.services.repo))
    }
}

/** The alarm going off. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val done = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { Reminders.fire(context) } finally { done.finish() }
        }
    }
}

/**
 * Alarms do not survive a reboot or an app update, so both re-arm the
 * reminder from the saved settings.
 */
class RearmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val done = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { Reminders.rearm(context) } finally { done.finish() }
        }
    }
}
