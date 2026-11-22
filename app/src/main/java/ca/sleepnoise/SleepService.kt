package ca.sleepnoise

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Foreground service that owns the sound and the microphone, so playback, voice control and the
 * schedule keep working with the screen off or the UI gone. The UI is a thin client: it sends
 * actions and receives JSON state through [Listener].
 */
class SleepService : Service(), VoiceEngine.Callbacks {
    companion object {
        const val ACTION_TOGGLE = "ca.sleepnoise.TOGGLE"
        const val ACTION_PLAY = "ca.sleepnoise.PLAY"
        const val ACTION_PAUSE = "ca.sleepnoise.PAUSE"
        const val ACTION_ALARM_START = "ca.sleepnoise.ALARM_START"
        const val ACTION_ALARM_STOP = "ca.sleepnoise.ALARM_STOP"
        private const val CHANNEL = "playback"
        private const val NOTIF_ID = 1

        fun start(ctx: Context, action: String? = null) {
            val i = Intent(ctx, SleepService::class.java); if (action != null) i.action = action
            ctx.startForegroundService(i)
        }
    }

    fun interface Listener { fun onMessage(json: String) }
    inner class LocalBinder : Binder() { val service: SleepService get() = this@SleepService }

    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<Listener>()
    private val synthPool = Executors.newSingleThreadExecutor()
    lateinit var settings: Settings; private set
    private lateinit var audio: AudioEngine
    private lateinit var voice: VoiceEngine
    private lateinit var wake: WakeLogic
    private var wakeLock: PowerManager.WakeLock? = null

    var playing = false; private set
    private var listening = false
    private var softDuck = false
    private var voiceState = "busy"; private var voiceText = "Voice starting…"
    private var lastHeard = ""
    private var micLevel = 0f
    private val baker = LoopBaker<NoiseShape>()
    @Volatile private var synthMs = 0L
    private var jingleInfo = "loading"
    private val startedAt = System.currentTimeMillis()

    // ------------------------------------------------------------------ lifecycle
    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        goForeground()
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SleepNoise::service").apply { setReferenceCounted(false); acquire() }

        audio = AudioEngine()
        audio.start()
        applyLive()
        loadJingle()
        rebakeNoise(immediate = true)

        wake = WakeLogic(settings.str("wake"), settings.num("minConf"))
        voice = VoiceEngine(this, this).apply {
            enabled = settings.bool("voiceOn"); micGain = settings.f("micGain"); setMicFx(settings.bool("micFx"))
            setGrammar(wake.grammar())
            start()
        }
        Scheduler.reschedule(this, settings)
        if (settings.bool("resume") && settings.bool("_wasPlaying")) play(intro = false, announce = false)
        main.post(levelTick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (intent?.action) {
            ACTION_TOGGLE -> toggle()
            ACTION_PLAY -> play()
            ACTION_PAUSE -> pause()
            ACTION_ALARM_START -> { play(announce = true); Scheduler.reschedule(this, settings) }
            ACTION_ALARM_STOP -> { pause(announce = true); Scheduler.reschedule(this, settings) }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        voice.stop(); audio.stop(); synthPool.shutdownNow()
        runCatching { wakeLock?.release() }
        super.onDestroy()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val n = notification()
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        } catch (e: Exception) {   // newer Android refuses the microphone type when started in the background
            ServiceCompat.startForeground(this, NOTIF_ID, n, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val toggle = PendingIntent.getService(this, 1, Intent(this, SleepService::class.java).setAction(ACTION_TOGGLE), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(if (playing) "Brown noise playing" else "Sleep Noise paused")
            .setContentText(if (settings.bool("voiceOn")) "Say “${settings.str("wake")}”" else "Voice control off")
            .setContentIntent(open)
            .addAction(0, if (playing) "Pause" else "Play", toggle)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun refreshNotification() = getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification())

    // ------------------------------------------------------------------ sound
    private fun loadJingle() {
        synthPool.execute {
            try {
                val clip = Wav.resample(Wav.decode(resources.openRawResource(R.raw.intro).use { it.readBytes() }), audio.sampleRate)
                audio.renderer.setJingle(clip.left, clip.right)
                jingleInfo = "%.1f s".format(clip.seconds)
            } catch (t: Throwable) { jingleInfo = "failed: ${t.message}"; Log.e("SleepNoise", "jingle", t) }
            main.post { pushState() }
        }
    }

    /** Tone, rumble and colour are baked into the loop; regenerate it (debounced) and crossfade. */
    private fun rebakeNoise(immediate: Boolean = false) {
        main.removeCallbacks(rebakeRunnable)
        if (immediate) rebakeRunnable.run() else main.postDelayed(rebakeRunnable, 350)
    }
    private val rebakeRunnable = Runnable {
        val shape = settings.shape
        val job = baker.request(shape) ?: return@Runnable     // also cancels any job for an abandoned setting
        synthPool.execute {
            if (!baker.isCurrent(job)) return@execute          // superseded before it started
            val loop = NoiseSynth.generate(audio.sampleRate, shape)
            if (!baker.complete(job, shape)) return@execute    // superseded while generating
            audio.renderer.setNoise(loop.left, loop.right)
            synthMs = loop.millis
            main.post { pushState() }
        }
    }

    private fun applyLive() {
        val r = audio.renderer
        r.volume = settings.f("volume"); r.width = settings.f("width")
        r.swellDepth = settings.f("waves"); r.swellPeriod = settings.f("wavePeriod")
    }

    fun play(intro: Boolean = settings.bool("introOn"), announce: Boolean = true) {
        if (playing) return
        playing = true; settings.put("_wasPlaying", true)
        audio.renderer.play(intro, settings.f("introVol"), settings.f("noiseDelay"), settings.f("fadeIn"))
        if (announce) toast("▶  Playing")
        refreshNotification(); pushState()
    }

    fun pause(announce: Boolean = true) {
        if (!playing) return
        playing = false; settings.put("_wasPlaying", false)
        audio.renderer.pause(settings.f("fadeOut"))
        if (announce) toast("❚❚  Paused")
        refreshNotification(); pushState()
    }

    fun toggle() = if (playing) pause() else play()

    fun nudgeVolume(d: Double) {
        val v = ((settings.num("volume") + d) * 100).roundToInt() / 100.0
        settings.put("volume", v.coerceIn(0.0, 1.0)); applyLive(); pushState()
    }

    // ------------------------------------------------------------------ settings from the UI
    fun set(k: String, v: Any?) {
        if (k == "wake") { setWake(v.toString()); return }
        settings.put(k, v)
        when (k) {
            "tone", "rumble", "color" -> rebakeNoise()
            "volume", "width", "waves", "wavePeriod" -> applyLive()
            "voiceOn" -> { voice.enabled = settings.bool("voiceOn"); refreshNotification() }
            "micGain" -> voice.micGain = settings.f("micGain")
            "micFx" -> voice.setMicFx(settings.bool("micFx"))
            "minConf" -> wake.minConf = settings.num("minConf")
            "autoStart", "autoStop" -> Scheduler.reschedule(this, settings)
        }
        pushState()
    }

    private fun setWake(phrase: String) {
        val words = WakeLogic.split(phrase)
        val m = voice.model
        val missing = if (m == null) emptyList() else words.filter { VoskExtra.hasWord(m, it) == false }
        send(JSONObject().put("type", "wakeCheck").put("ok", words.isNotEmpty() && missing.isEmpty()).put("missing", JSONArray(missing)))
        if (words.isEmpty() || missing.isNotEmpty()) return
        settings.put("wake", words.joinToString(" "))
        wake.setWake(words.joinToString(" ")); voice.setGrammar(wake.grammar())
        refreshNotification(); pushState()
    }

    fun resetSettings() {
        settings.reset()
        wake.setWake(settings.str("wake")); wake.minConf = settings.num("minConf"); voice.setGrammar(wake.grammar())
        voice.enabled = settings.bool("voiceOn"); voice.micGain = settings.f("micGain"); voice.setMicFx(settings.bool("micFx"))
        applyLive(); rebakeNoise(); Scheduler.reschedule(this, settings)
        toast("Settings reset"); refreshNotification(); pushState()
    }

    fun previewJingle() = audio.renderer.previewJingle(settings.f("introVol"))
    fun testIntro() { if (playing) pause(announce = false); main.postDelayed({ play() }, 300) }
    fun micPermissionChanged() = voice.restart()

    // ------------------------------------------------------------------ voice callbacks (voice thread)
    override fun onStatus(state: String, text: String) {
        main.post { voiceState = state; voiceText = text; pushState() }
    }

    override fun onLevel(level: Float) { micLevel = level }

    override fun onPartial(text: String) {
        main.post {
            if (!listening && !softDuck && wake.partialHasWake(text)) {   // dip early so the command is easier to hear
                softDuck = true; audio.renderer.duckLevel = settings.f("duck")
                main.postDelayed(undoSoftDuck, 4000)
            }
        }
    }
    private val undoSoftDuck = Runnable { if (!listening) { softDuck = false; audio.renderer.duckLevel = 1f } }

    override fun onResult(json: JSONObject) {
        main.post {
            val text = json.optString("text").trim()
            val arr = json.optJSONArray("result")
            val words = (0 until (arr?.length() ?: 0)).map { val o = arr!!.getJSONObject(it); WakeLogic.Word(o.optString("word"), o.optDouble("conf", 0.0)) }
            if (text.isNotEmpty() && text != "[unk]") {
                lastHeard = text + "  (" + words.joinToString { "${it.w} ${(it.conf * 100).roundToInt()}%" } + ")"
                send(JSONObject().put("type", "heard").put("text", lastHeard))
            }
            when (val d = wake.decide(words, listening)) {
                is WakeLogic.Decision.Run -> { run(d.cmd); closeWindow() }
                WakeLogic.Decision.OpenWindow -> openWindow()
                WakeLogic.Decision.Nothing -> if (softDuck && !listening) { softDuck = false; main.removeCallbacks(undoSoftDuck); audio.renderer.duckLevel = 1f }
            }
        }
    }

    private fun openWindow() {
        listening = true; softDuck = false; main.removeCallbacks(undoSoftDuck)
        audio.renderer.duckLevel = settings.f("duck")
        if (settings.bool("chime")) audio.renderer.chime(true, settings.f("chimeVol"))
        main.removeCallbacks(closeRunnable); main.postDelayed(closeRunnable, 7000)
        pushState()
    }
    private val closeRunnable = Runnable { closeWindow() }
    private fun closeWindow() {
        listening = false; main.removeCallbacks(closeRunnable)
        audio.renderer.duckLevel = 1f
        pushState()
    }

    private fun run(c: WakeLogic.Command) {
        when (c.id) {
            "start" -> play(announce = false)
            "stop" -> pause(announce = false)
            "louder" -> nudgeVolume(0.08)
            "quieter" -> nudgeVolume(-0.08)
            "deeper" -> set("tone", (settings.num("tone") * 0.8).roundToInt().coerceIn(80, 4000))
            "brighter" -> set("tone", (settings.num("tone") * 1.25).roundToInt().coerceIn(80, 4000))
        }
        toast(c.say)
    }

    // ------------------------------------------------------------------ UI messaging
    fun addListener(l: Listener) { listeners += l; pushState() }
    fun removeListener(l: Listener) { listeners -= l }

    private fun send(o: JSONObject) { val s = o.toString(); listeners.toList().forEach { it.onMessage(s) } }
    private fun toast(text: String) = send(JSONObject().put("type", "toast").put("text", text))

    fun pushState() {
        if (listeners.isEmpty()) return
        val pm = getSystemService(PowerManager::class.java)
        val diag = JSONObject()
            .put("sound", "AudioTrack ${audio.encoding}, ${audio.sampleRate} Hz, buffer ${audio.bufferMs} ms, underruns ${audio.underruns}, output restarts ${audio.restarts}")
            .put("noise", if (audio.renderer.noiseReady) "ready (generated in $synthMs ms)" else "preparing")
            .put("jingle", jingleInfo)
            .put("voice", (if (voiceState == "ready") "listening" else voiceText) + (if (voice.modelLoadMs > 0) ", model loaded in ${voice.modelLoadMs} ms" else "") + (if (voice.micInfo.isNotEmpty()) ", ${voice.micInfo}" else ""))
            .put("service", "running ${(System.currentTimeMillis() - startedAt) / 60000} min, battery optimisation " + if (pm.isIgnoringBatteryOptimizations(packageName)) "off (good)" else "ON (tap the button below)")
            .put("version", runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrDefault("?"))
        send(JSONObject().put("type", "state")
            .put("playing", playing).put("listening", listening)
            .put("voice", JSONObject().put("state", if (listening) "listen" else voiceState).put("text", voiceText))
            .put("lastHeard", lastHeard)
            .put("settings", settings.toJson())
            .put("diag", diag))
    }

    private val levelTick = object : Runnable {
        override fun run() {
            if (listeners.isNotEmpty()) send(JSONObject().put("type", "level").put("mic", micLevel.toDouble()))
            main.postDelayed(this, 250)
        }
    }
}
