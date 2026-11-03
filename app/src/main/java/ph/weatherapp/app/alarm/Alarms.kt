package ph.weatherapp.app.alarm

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ph.weatherapp.app.AlarmActivity
import ph.weatherapp.app.MainActivity
import ph.weatherapp.app.WeatherRepository
import ph.weatherapp.core.engine.Lang
import ph.weatherapp.core.engine.MANILA
import ph.weatherapp.core.engine.Recommender
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

// ---------------------------------------------------------------------------
// Settings: two daily alarms (the free plan's limit), saved on the phone.
// ---------------------------------------------------------------------------

data class AlarmSpec(val id: Int, val hour: Int, val minute: Int, val enabled: Boolean) {
    /** MAIN = full weather alarm; MINI = short card before leaving for school or work. */
    val isMain get() = id == MAIN

    fun label(lang: Lang) = when {
        isMain -> if (lang == Lang.EN) "Weather alarm" else "Alarm ng panahon"
        else -> if (lang == Lang.EN) "Before you leave" else "Bago umalis"
    }

    fun timeText(): String = ZonedDateTime.now(MANILA).withHour(hour).withMinute(minute)
        .format(DateTimeFormatter.ofPattern("h:mm a"))

    companion object {
        const val MAIN = 0
        const val MINI = 1
    }
}

object AlarmPrefs {
    private const val FILE = "alarms"
    private val DEFAULTS = mapOf(AlarmSpec.MAIN to (5 to 30), AlarmSpec.MINI to (6 to 45))

    fun get(ctx: Context, id: Int): AlarmSpec {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val (h, m) = DEFAULTS.getValue(id)
        return AlarmSpec(id, p.getInt("h$id", h), p.getInt("m$id", m), p.getBoolean("on$id", false))
    }

    fun all(ctx: Context) = listOf(get(ctx, AlarmSpec.MAIN), get(ctx, AlarmSpec.MINI))

    fun save(ctx: Context, a: AlarmSpec) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt("h${a.id}", a.hour).putInt("m${a.id}", a.minute).putBoolean("on${a.id}", a.enabled)
            .apply()
    }

    fun lang(ctx: Context): Lang =
        if (ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("fil", false)) Lang.FIL else Lang.EN

    fun setLang(ctx: Context, lang: Lang) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("fil", lang == Lang.FIL).apply()
    }
}

// ---------------------------------------------------------------------------
// Scheduling with AlarmManager.setAlarmClock (shown in the status bar, works in Doze).
// ---------------------------------------------------------------------------

object AlarmScheduler {
    const val ACTION_FIRE = "ph.weatherapp.ALARM_FIRE"
    const val ACTION_SNOOZED_FIRE = "ph.weatherapp.ALARM_SNOOZED_FIRE"
    const val ACTION_PREFETCH = "ph.weatherapp.ALARM_PREFETCH"
    const val ACTION_SNOOZE = "ph.weatherapp.ALARM_SNOOZE"
    const val ACTION_DISMISS = "ph.weatherapp.ALARM_DISMISS"
    const val EXTRA_ID = "alarm_id"
    const val SNOOZE_MINUTES = 10L
    /** Refresh before ringing: once early, and again just before, so PAGASA's 5 AM release is picked up. */
    private val PREFETCH_MINUTES = listOf(30L to 2, 10L to 8)   // minutes before → request code

    fun scheduleAll(ctx: Context) = AlarmPrefs.all(ctx).forEach { schedule(ctx, it) }

    fun schedule(ctx: Context, a: AlarmSpec) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val fire = broadcast(ctx, ACTION_FIRE, a.id, 1)
        val pres = PREFETCH_MINUTES.map { (min, code) -> min to broadcast(ctx, ACTION_PREFETCH, a.id, code) }
        am.cancel(fire)
        pres.forEach { am.cancel(it.second) }
        if (!a.enabled) return
        val now = ZonedDateTime.now(MANILA)
        val at = nextTrigger(a, now)
        setAlarmClock(ctx, am, at, fire)
        for ((min, pre) in pres) {
            val preAt = at.minusMinutes(min)
            if (preAt.isAfter(now)) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, preAt.toInstant().toEpochMilli(), pre)
        }
    }

    fun snooze(ctx: Context, id: Int) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val at = ZonedDateTime.now(MANILA).plusMinutes(SNOOZE_MINUTES)
        setAlarmClock(ctx, am, at, broadcast(ctx, ACTION_SNOOZED_FIRE, id, 3))
    }

    /** Rings once in 10 seconds without touching the daily schedule (for checking sound and lock screen). */
    fun test(ctx: Context, id: Int) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        setAlarmClock(ctx, am, ZonedDateTime.now(MANILA).plusSeconds(10), broadcast(ctx, ACTION_SNOOZED_FIRE, id, 7))
    }

    /** Next time this alarm should ring: today if still ahead (by over a minute), else tomorrow. */
    fun nextTrigger(a: AlarmSpec, now: ZonedDateTime): ZonedDateTime {
        val today = now.withHour(a.hour).withMinute(a.minute).withSecond(0).withNano(0)
        return if (today.isAfter(now.plusMinutes(1))) today else today.plusDays(1)
    }

    /** e.g. "Tue 5:30 AM", or null when no alarm is on. */
    fun nextAlarmText(ctx: Context): String? {
        val now = ZonedDateTime.now(MANILA)
        return AlarmPrefs.all(ctx).filter { it.enabled }.minOfOrNull { nextTrigger(it, now) }
            ?.format(DateTimeFormatter.ofPattern("EEE h:mm a"))
    }

    fun canScheduleExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun setAlarmClock(ctx: Context, am: AlarmManager, at: ZonedDateTime, pi: PendingIntent) {
        val millis = at.toInstant().toEpochMilli()
        try {
            if (!canScheduleExact(ctx)) throw SecurityException("exact alarms not allowed")
            val show = PendingIntent.getActivity(
                ctx, 900, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(millis, show), pi)
        } catch (e: SecurityException) {
            // Without exact-alarm permission the alarm may ring a few minutes late.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        }
    }

    internal fun broadcast(ctx: Context, action: String, id: Int, slot: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, id * 10 + slot,
            Intent(ctx, AlarmReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

// ---------------------------------------------------------------------------
// Ringing: a full-screen notification whose alarm sound repeats until Snooze/Dismiss.
// ---------------------------------------------------------------------------

object AlarmNotifier {
    const val CHANNEL = "weather_alarms_v1"
    private const val NOTIF_BASE = 4200
    private const val RING_LIMIT_MS = 10 * 60 * 1000L   // stop ringing after 10 minutes

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val ch = NotificationChannel(CHANNEL, "Weather alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Rings at your alarm time with the day's forecast"
            setSound(sound, AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 600, 800, 600)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(ch)
    }

    fun ring(ctx: Context, id: Int) {
        ensureChannel(ctx)
        val a = AlarmPrefs.get(ctx, id)
        val lang = AlarmPrefs.lang(ctx)
        val data = WeatherRepository.loadCached(ctx, forAlarm = true)
        val f = data?.current
        val weather = f?.let {
            val top = Recommender.recommend(it).first().short(lang)
            "${it.rainChancePct}% ${if (lang == Lang.EN) "rain" else "ulan"} · ${it.tempMinC}–${it.tempMaxC}°C · $top"
        } ?: if (lang == Lang.EN) "Open to see today's weather" else "Buksan para makita ang panahon"
        // Premium: put today's class/work suspension first.
        val today = ZonedDateTime.now(MANILA).toLocalDate()
        val suspension = data?.suspensions?.firstOrNull { it.date == today }
            ?.takeIf { ph.weatherapp.app.Premium.isActive(ctx) }
            ?.let { it.title(lang).uppercase() + " · " }
        val summary = (suspension ?: "") + weather

        val open = PendingIntent.getActivity(
            ctx, id * 10 + 6,
            Intent(ctx, AlarmActivity::class.java)
                .putExtra(AlarmScheduler.EXTRA_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(ph.weatherapp.app.R.drawable.ic_stat_umbrella)
            .setContentTitle(a.label(lang) + " · " + ZonedDateTime.now(MANILA).format(DateTimeFormatter.ofPattern("h:mm a")))
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .setOngoing(true)
            .setAutoCancel(false)
            .setTimeoutAfter(RING_LIMIT_MS)
            .addAction(0, if (lang == Lang.EN) "Snooze ${AlarmScheduler.SNOOZE_MINUTES} min" else "Snooze ${AlarmScheduler.SNOOZE_MINUTES} min",
                AlarmScheduler.broadcast(ctx, AlarmScheduler.ACTION_SNOOZE, id, 4))
            .addAction(0, if (lang == Lang.EN) "Dismiss" else "Itigil",
                AlarmScheduler.broadcast(ctx, AlarmScheduler.ACTION_DISMISS, id, 5))
            .build()
        n.flags = n.flags or android.app.Notification.FLAG_INSISTENT   // repeat the sound until stopped
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_BASE + id, n)
        } catch (e: SecurityException) {
            // Notifications are turned off for the app; nothing can ring.
        }
    }

    fun stop(ctx: Context, id: Int) {
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_BASE + id)
    }

    fun canUseFullScreen(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
}

// ---------------------------------------------------------------------------
// Receivers and the pre-alarm forecast refresh.
// ---------------------------------------------------------------------------

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra(AlarmScheduler.EXTRA_ID, AlarmSpec.MAIN)
        when (intent.action) {
            AlarmScheduler.ACTION_FIRE -> {
                AlarmNotifier.ring(ctx, id)
                AlarmScheduler.schedule(ctx, AlarmPrefs.get(ctx, id))   // same time tomorrow
            }
            AlarmScheduler.ACTION_SNOOZED_FIRE -> AlarmNotifier.ring(ctx, id)
            AlarmScheduler.ACTION_PREFETCH -> PrefetchWorker.enqueue(ctx)
            AlarmScheduler.ACTION_SNOOZE -> {
                AlarmNotifier.stop(ctx, id)
                AlarmScheduler.snooze(ctx, id)
            }
            AlarmScheduler.ACTION_DISMISS -> AlarmNotifier.stop(ctx, id)
        }
    }
}

/** Re-arms the alarms after a restart, an app update, or a clock/time-zone change. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        AlarmScheduler.scheduleAll(ctx)
    }
}

class PrefetchWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result =
        try {
            WeatherRepository.load(applicationContext, forAlarm = true)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }

    companion object {
        fun enqueue(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<PrefetchWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("prefetch-forecast", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
