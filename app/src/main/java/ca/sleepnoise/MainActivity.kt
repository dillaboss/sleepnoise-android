package ca.sleepnoise

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

/**
 * The screen: a WebView showing assets/ui/index.html, which is only a control panel. All sound and
 * listening happen in [SleepService]; if this page crashes or closes, playback carries on.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private var service: SleepService? = null
    private val listener = SleepService.Listener { json -> runOnUiThread { web.evaluateJavascript("window.onNative&&window.onNative($json)", null) } }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as SleepService.LocalBinder).service.also { it.addListener(listener); applyScreenSetting(it) }
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true) }
        else { @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED) }
        volumeControlStream = AudioManager.STREAM_MUSIC
        WebView.setWebContentsDebuggingEnabled(true)

        SleepService.start(this)
        bindService(Intent(this, SleepService::class.java), conn, Context.BIND_AUTO_CREATE)

        buildWebView()
        hideSystemUi()
        askPermissions()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView() {
        val assets = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = assets.shouldInterceptRequest(request.url)
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean { recreate(); return true }   // the sound is unaffected
                override fun onPageFinished(view: WebView?, url: String?) { service?.pushState() }
            }
            addJavascriptInterface(Bridge(), "SleepNoiseNative")
            loadUrl("https://appassets.androidplatform.net/assets/ui/index.html")
        }
        setContentView(web)
    }

    private fun applyScreenSetting(s: SleepService) {
        if (s.settings.bool("keepScreenOn")) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun askPermissions() {
        val want = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) want += Manifest.permission.POST_NOTIFICATIONS
        val missing = want.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1) else askBatteryOnce()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        service?.micPermissionChanged()
        askBatteryOnce()
    }

    /** Samsung's battery manager can kill background apps; ask once to be left alone. */
    private fun askBatteryOnce() {
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (prefs.getBoolean("askedBattery", false)) return
        prefs.edit().putBoolean("askedBattery", true).apply()
        requestBatteryExemption()
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        runCatching { startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideSystemUi() }
    override fun onResume() { super.onResume(); hideSystemUi(); service?.pushState() }

    @Deprecated("Back stays in the app; use Settings > Android settings to leave")
    override fun onBackPressed() { }

    override fun onDestroy() {
        service?.removeListener(listener)
        runCatching { unbindService(conn) }
        web.destroy()
        super.onDestroy()
    }

    /** window.SleepNoiseNative.send(json) from the page. */
    private inner class Bridge {
        @JavascriptInterface
        fun send(json: String) {
            val m = runCatching { JSONObject(json) }.getOrNull() ?: return
            runOnUiThread { handle(m) }
        }
    }

    private fun handle(m: JSONObject) {
        val s = service ?: return
        when (m.optString("a")) {
            "hello" -> s.pushState()
            "toggle" -> s.toggle()
            "play" -> s.play()
            "pause" -> s.pause()
            "volume" -> s.nudgeVolume(m.optDouble("delta", 0.0))
            "set" -> {
                s.set(m.getString("k"), m.opt("v"))
                if (m.getString("k") == "keepScreenOn") applyScreenSetting(s)
            }
            "previewJingle" -> s.previewJingle()
            "testIntro" -> s.testIntro()
            "reset" -> { s.resetSettings(); applyScreenSetting(s) }
            "openSettings" -> startActivity(Intent(AndroidSettings.ACTION_SETTINGS))
            "openHome" -> runCatching { startActivity(Intent(AndroidSettings.ACTION_HOME_SETTINGS)) }
                .onFailure { startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
            "battery" -> {
                val pm = getSystemService(PowerManager::class.java)
                if (pm.isIgnoringBatteryOptimizations(packageName)) runCatching { startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                else requestBatteryExemption()
            }
        }
    }
}
