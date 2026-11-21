package ca.sleepnoise

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Daily auto-start / auto-stop with AlarmManager.setAlarmClock, so they fire even if the screen is off or the app was killed. */
object Scheduler {
    private const val REQ_START = 101
    private const val REQ_STOP = 102

    fun reschedule(ctx: Context, s: Settings) {
        set(ctx, REQ_START, SleepService.ACTION_ALARM_START, s.str("autoStart"))
        set(ctx, REQ_STOP, SleepService.ACTION_ALARM_STOP, s.str("autoStop"))
    }

    fun nextMillis(hm: String, now: ZonedDateTime = ZonedDateTime.now()): Long? {
        val t = runCatching { LocalTime.parse(hm) }.getOrNull() ?: return null
        var at = ZonedDateTime.of(LocalDate.now(now.zone), t, now.zone)
        if (!at.isAfter(now.plusSeconds(5))) at = at.plusDays(1)
        return at.toInstant().toEpochMilli()
    }

    private fun set(ctx: Context, req: Int, action: String, hm: String) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getForegroundService(ctx, req, Intent(ctx, SleepService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val at = nextMillis(hm, ZonedDateTime.now(ZoneId.systemDefault()))
        if (at == null) { am.cancel(pi); return }
        val show = PendingIntent.getActivity(ctx, 9, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}
