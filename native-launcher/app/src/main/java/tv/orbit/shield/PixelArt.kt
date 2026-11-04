package tv.orbit.shield

import android.app.Activity
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.service.dreams.DreamService
import android.view.WindowInsets
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek

/** Weekly native schedule: no network, AI service, exact alarm or screen wake. */
internal object ArtSchedule {
    const val JOB_ID = 7008
    private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
    fun week(date: LocalDate = LocalDate.now()): Long = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay()
    fun ensure(context: Context): Long {
        val prefs = context.getSharedPreferences("pixelArt", Context.MODE_PRIVATE)
        val current = week()
        if (prefs.getLong("week", Long.MIN_VALUE) != current) {
            prefs.edit().putLong("week", current).putLong("seed", current * 104729L + 7008L)
                .putLong("generatedAt", System.currentTimeMillis()).apply()
        }
        return prefs.getLong("seed", current * 104729L + 7008L)
    }
    fun install(context: Context) {
        ensure(context)
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(JOB_ID) == null) {
            scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, WeeklyArtJob::class.java))
                .setPeriodic(WEEK_MS, 24L * 60 * 60 * 1000).setPersisted(true).setRequiresDeviceIdle(true).build())
        }
    }
}

class WeeklyArtJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean { ArtSchedule.ensure(this); return false }
    override fun onStopJob(params: JobParameters): Boolean = true
}

class PixelDreamService : DreamService() {
    private var art: PixelArtView? = null
    private var grafana: GrafanaScreen? = null
    private var mode: ScreensaverMode? = null
    private var running = false
    private var preferences: SharedPreferences? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key.startsWith(GrafanaPreferences.KEY_PREFIX)) applyOptions()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = false
        ArtSchedule.install(this)
        preferences = getSharedPreferences("orbit", Context.MODE_PRIVATE).also {
            it.registerOnSharedPreferenceChangeListener(listener)
        }
        applyOptions()
    }

    private fun applyOptions() {
        val options = GrafanaPreferences.read(this)
        if (mode != options.mode) {
            art?.stop()
            art = null
            grafana?.destroy()
            grafana = null
            mode = options.mode
            when (options.mode) {
                ScreensaverMode.ART -> {
                    art = PixelArtView(Language.context(this))
                    setContentView(art)
                }
                ScreensaverMode.GRAFANA -> {
                    grafana = GrafanaScreen(Language.context(this), interactive = false)
                    setContentView(grafana)
                }
            }
        }
        grafana?.configure(options)
        if (running) { art?.start(); grafana?.start() }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        running = true
        art?.start()
        grafana?.start()
    }
    override fun onDreamingStopped() {
        running = false
        art?.stop()
        grafana?.stop()
        super.onDreamingStopped()
    }
    override fun onDetachedFromWindow() {
        running = false
        preferences?.unregisterOnSharedPreferenceChangeListener(listener)
        preferences = null
        art?.stop()
        art = null
        grafana?.destroy()
        grafana = null
        mode = null
        super.onDetachedFromWindow()
    }
}

/** Preview respects normal sleep timeout and stops rendering as soon as it is hidden. */
class PixelArtPreviewActivity : Activity() {
    private lateinit var art: PixelArtView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        art = PixelArtView(Language.context(this))
        setContentView(art)
        window.decorView.post { window.insetsController?.hide(WindowInsets.Type.systemBars()) }
    }
    override fun onResume() { super.onResume(); art.start() }
    override fun onPause() { art.stop(); super.onPause() }
}

