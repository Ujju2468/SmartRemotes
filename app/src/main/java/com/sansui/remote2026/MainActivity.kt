package com.sansui.remote2026

import android.annotation.SuppressLint
import android.app.Activity
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.sansui.remote2026.core.TvController

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var tv: TvController
    private lateinit var bt: BtHid
    @Volatile private var volumeKeys = true
    @Volatile private var haptics = true

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        tv = TvController(
            dir = filesDir,
            emit = { json -> runOnUiThread { if (::web.isInitialized) web.evaluateJavascript("window.__native($json)", null) } },
            discovery = NsdDiscovery(this),
            clientName = Build.MODEL ?: "Phone"
        )
        bt = BtHid(this, tv) { json -> runOnUiThread { if (::web.isInitialized) web.evaluateJavascript("window.__native($json)", null) } }
        tv.bt = bt

        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            overScrollMode = WebView.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            setOnLongClickListener { true }
            isHapticFeedbackEnabled = false
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true          // saves shortcuts + preferences
            settings.textZoom = 100                    // keep the layout stable with big system fonts
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            webViewClient = WebViewClient()
            addJavascriptInterface(Bridge(), "Android")
            loadUrl("file:///android_asset/sansui-tv-remote.html")
        }
        setContentView(web)
    }

    override fun onStart() { super.onStart(); tv.start() }

    private fun toast(msg: String) =
        runOnUiThread { web.evaluateJavascript("window.__native({t:'toast',msg:${TvController.q(msg)}})", null) }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 7) { if (res.isNotEmpty() && res.all { it == PackageManager.PERMISSION_GRANTED }) bt.init() else toast("Bluetooth permission was denied"); bt.push() }
    }
    override fun onStop() { tv.scanStop(); tv.stop(); super.onStop() }
    override fun onDestroy() { tv.shutdown(); super.onDestroy() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("window.__back && window.__back()") { r -> if (r != "true") finish() }
    }

    /** Phone volume buttons control the TV volume while connected. */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val up = e.keyCode == KeyEvent.KEYCODE_VOLUME_UP
        val down = e.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if ((up || down) && volumeKeys && tv.isConnected) {
            if (e.action == KeyEvent.ACTION_DOWN) tv.key(if (up) 24 else 25)
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    /** Everything the page may call. Runs on a WebView thread: never block here. */
    inner class Bridge {
        @JavascriptInterface fun hello() = tv.hello()
        @JavascriptInterface fun key(code: Int) = tv.key(code)
        @JavascriptInterface fun launch(link: String) = tv.launch(link)
        @JavascriptInterface fun text(s: String, keysOnly: Boolean) = tv.typeText(s, keysOnly)
        @JavascriptInterface fun scan(on: Boolean) = if (on) tv.scanStart() else tv.scanStop()
        @JavascriptInterface fun pair(host: String, name: String) = tv.pairStart(host, name)
        @JavascriptInterface fun code(c: String) = tv.pairCode(c)
        @JavascriptInterface fun cancelPair() = tv.pairCancel()
        @JavascriptInterface fun connect() = tv.start()
        @JavascriptInterface fun forget() = tv.forget()
        @JavascriptInterface fun prefs(volKeys: Boolean, haptic: Boolean) { volumeKeys = volKeys; haptics = haptic }
        @JavascriptInterface fun btInit() = runOnUiThread {
            if (Build.VERSION.SDK_INT >= 31 && !bt.hasPermission())
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE), 7)
            else bt.init()
        }
        @JavascriptInterface fun btInfo() = bt.push()
        @JavascriptInterface fun btVisible() = runOnUiThread {
            try { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)) }
            catch (_: Exception) { toast("Open Bluetooth settings and make the phone visible") }
        }
        @JavascriptInterface fun btConnect(addr: String) = bt.connect(addr)
        @JavascriptInterface fun btForget() = bt.forget()
        @JavascriptInterface fun cast() = runOnUiThread {
            val actions = listOf("android.settings.CAST_SETTINGS", "android.settings.WIFI_DISPLAY_SETTINGS")
            for (a in actions) {
                try { startActivity(Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return@runOnUiThread }
                catch (_: ActivityNotFoundException) {} catch (_: SecurityException) {}
            }
            toast("Swipe down your phone's quick settings and tap Cast / Smart View")
        }
        @JavascriptInterface fun tick() {
            if (haptics) runOnUiThread { web.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
        }
    }
}
