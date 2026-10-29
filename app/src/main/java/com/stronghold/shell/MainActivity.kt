package com.stronghold.shell

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var rootLayout: FrameLayout
    private lateinit var nativeLoadingView: TextView

    private var serverAddress: String? = null
    private var serverName: String? = null
    private val hookedUrls = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private var hookCheckDone = false

    // 🌟 双重加载与时间控制
    private var isFirstLoad = true
    private var loadingStartTime = 0L

    companion object {
        private const val TAG = "StrongholdShell"
        const val LOCAL_URL = "http://127.0.0.1:3000"
        const val HEALTH_URL = "$LOCAL_URL/healthz"
        const val ACTION_SERVER_STATE = "com.stronghold.shell.SERVER_STATE"
        const val EXTRA_SERVER_READY = "server_ready"
        const val EXTRA_SERVER_ADDRESS = "server_address"
        const val EXTRA_SERVER_NAME = "server_name"
        private const val INDEX_PATH = "/index.html"
    }

    private val serverReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val ready = intent?.getBooleanExtra(EXTRA_SERVER_READY, false) ?: false
            Log.i(TAG, "收到服务状态广播: ready=$ready")
            if (ready) loadGame()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        serverAddress = intent.getStringExtra(EXTRA_SERVER_ADDRESS)?.takeIf { it.isNotBlank() }
        serverName = intent.getStringExtra(EXTRA_SERVER_NAME)

        // 1) 初始化 UI 层级：WebView 在底层，原生白字在最上层遮挡
        webView = WebView(this)
        webView.setBackgroundColor(Color.BLACK)
        webView.visibility = View.VISIBLE // 底层可见，但被上层盖住
        
        rootLayout = FrameLayout(this)
        nativeLoadingView = TextView(this).apply {
            text = "正在准备游戏资源，请稍候..."
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0d1117"))
            visibility = View.VISIBLE
        }
        
        // 记录白字出现的时间（用于防闪烁）
        loadingStartTime = System.currentTimeMillis()
        
        rootLayout.addView(webView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        rootLayout.addView(nativeLoadingView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        
        setContentView(rootLayout)

        setupWebView()
        setupImmersiveMode()
        installBridgeIfNeeded()
        registerReceiverCompat()

        // 2) 后台执行重活，防止主线程卡顿黑屏
        io.execute {
            try {
                if (!GamePack.hasActivePack(this)) {
                    AssetHelper.ensureAssetsExtracted(this)
                }
                FileLogger.init(this)
            } catch (e: Exception) {
                Log.e(TAG, "后台初始化失败", e)
            }

            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                nativeLoadingView.text = "正在启动游戏引擎..."
                startNodeService()
                pollHealthAndLoad()

                if (serverAddress != null) {
                    mainHandler.postDelayed({ runHookSelfCheck() }, 6000)
                    mainHandler.postDelayed({ runHookSelfCheck() }, 15000)
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.mediaPlaybackRequiresUserGesture = false
        
        if (serverAddress != null) {
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        settings.cacheMode = if (serverAddress != null) WebSettings.LOAD_NO_CACHE
                             else WebSettings.LOAD_DEFAULT
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/res/", WebViewAssetLoader.ResourcesPathHandler(this))
            .build()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                if (serverAddress != null && isIndexRequest(request.url)) {
                    injectIntoIndex(request.url)?.let { return it }
                }
                return assetLoader.shouldInterceptRequest(request.url)
            }
            
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                
                if (url?.startsWith(LOCAL_URL) == true) {
                    if (isFirstLoad) {
                        // 🌟 第一次加载完成（预热缓存），延迟 300ms 触发第二次加载
                        isFirstLoad = false
                        if (!isFinishing && !isDestroyed) {
                            nativeLoadingView.text = "正在渲染游戏画面..."
                        }
                        mainHandler.postDelayed({
                            if (!isFinishing && !isDestroyed) {
                                val suffix = if (serverAddress != null) "?multiplayer=1" else ""
                                webView.loadUrl("$LOCAL_URL/index.html$suffix")
                            }
                        }, 300)
                    } else {
                        // 🌟 第二次加载完成，计算时间以决定何时撤下白字
                        if (!isFinishing && !isDestroyed) {
                            val elapsedTime = System.currentTimeMillis() - loadingStartTime
                            // 最小显示时间 500ms，防止白字一闪而过
                            val minDisplayTime = 500L 
                            val delayTime = if (elapsedTime < minDisplayTime) minDisplayTime - elapsedTime else 0L
                            
                            mainHandler.postDelayed({
                                if (!isFinishing && !isDestroyed) {
                                    nativeLoadingView.visibility = View.GONE
                                }
                            }, delayTime)
                        }
                    }
                }

                if (serverAddress != null) {
                    mainHandler.postDelayed({ runHookSelfCheck() }, 800)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                val m = msg ?: return true
                Log.i(TAG, "CONSOLE [${m.messageLevel()}] ${m.message()}")
                return true
            }
        }

        webView.addJavascriptInterface(AndroidBridge(this, webView), "AndroidNative")
        webView.isLongClickable = false
        webView.setOnLongClickListener { true }
    }

    private fun isIndexRequest(url: android.net.Uri): Boolean {
        val host = url.host ?: return false
        if (host != "127.0.0.1" && host != "localhost") return false
        val path = url.path ?: return false
        return path == INDEX_PATH || path == "/" || path.isEmpty()
    }

    private fun injectIntoIndex(url: android.net.Uri): WebResourceResponse? {
        val addr = serverAddress ?: return null
        val key = url.toString()
        hookedUrls.add(key)
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url.toString()).openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code !in 200..299) return null
            val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

            val script = "<script>" + WsHook.build(addr) + "</script>"
            val patched = insertIntoHead(html, script)
            if (patched == null) return null

            WebResourceResponse(
                "text/html", "UTF-8", 200, "OK",
                mapOf("Cache-Control" to "no-store", "Pragma" to "no-cache"),
                patched.toByteArray(Charsets.UTF_8).inputStream()
            )
        } catch (e: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun insertIntoHead(html: String, script: String): String? {
        val headRegex = Regex("<head\\b[^>]*>", RegexOption.IGNORE_CASE)
        val m = headRegex.find(html)
        if (m != null) {
            val at = m.range.last + 1
            return html.substring(0, at) + script + html.substring(at)
        }
        val htmlRegex = Regex("<html\\b[^>]*>", RegexOption.IGNORE_CASE)
        val m2 = htmlRegex.find(html)
        if (m2 != null) {
            val at = m2.range.last + 1
            return html.substring(0, at) + script + html.substring(at)
        }
        return script + html
    }

    @SuppressLint("JavascriptInterface")
    private fun installBridgeIfNeeded() {
        if (serverAddress == null) return
        webView.addJavascriptInterface(
            object {
                @android.webkit.JavascriptInterface
                fun onConnectionFailed(reason: String?) {
                    mainHandler.post { showToastLike("连不上服务器") }
                }
                @android.webkit.JavascriptInterface
                fun onSocketEvent(event: String?) {
                    mainHandler.post { showToastLike("WS: $event") }
                }
            },
            WsHook.BRIDGE_NAME
        )
    }

    private fun showToastLike(message: String) {
        try {
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
        } catch (_: Exception) {}
    }

    private fun runHookSelfCheck() {
        if (isFinishing || isDestroyed) return
        val js = "(function(){try{return String(window." + WsHook.HOOK_FLAG + "===true);}catch(e){return 'ERR:'+e.message;}})()"
        webView.evaluateJavascript(js) { raw ->
            val v = raw?.trim()?.trim('"') ?: "null"
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                val msg = when {
                    v == "true" -> "联机已接管"
                    v == "false" -> "联机未生效（仍是单机）"
                    else -> "自检异常：$v"
                }
                if (!hookCheckDone) {
                    hookCheckDone = true
                    showToastLike(msg)
                }
            }
        }
    }

    private fun setupImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun registerReceiverCompat() {
        val filter = IntentFilter(ACTION_SERVER_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serverReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(serverReceiver, filter)
        }
    }

    private fun startNodeService() {
        val intent = Intent(this, NodeService::class.java).apply { action = NodeService.ACTION_START }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun pollHealthAndLoad() {
        if (isFinishing || isDestroyed || io.isShutdown) return

        io.execute {
            var ok = false
            val maxTries = 180
            for (i in 1..maxTries) {
                if (isServerHealthy()) { ok = true; break }
                if (i % 4 == 0) {
                    val sec = i / 2
                    mainHandler.post { 
                        if (!isFinishing && !isDestroyed && nativeLoadingView.visibility == View.VISIBLE) {
                            nativeLoadingView.text = "正在启动游戏引擎... 已等待 ${sec} 秒" 
                        }
                    }
                }
                Thread.sleep(500)
            }
            if (ok) mainHandler.post { loadGame() }
            else mainHandler.post { showErrorAndRetry() }
        }
    }

    private fun isServerHealthy(): Boolean {
        return try {
            val conn = URL(HEALTH_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            conn.requestMethod = "GET"
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) { false }
    }

    private fun loadGame() {
        if (webView.url?.startsWith(LOCAL_URL) == true) return
        val suffix = if (serverAddress != null) "?multiplayer=1" else ""
        webView.loadUrl("$LOCAL_URL/index.html$suffix")
    }

    private fun showErrorAndRetry() {
        if (isFinishing || isDestroyed) return
        val hasPack = GamePack.hasActivePack(this)
        nativeLoadingView.text = if (hasPack) {
            "本地服务未就绪。\n稍等片刻会自动重试..."
        } else {
            "尚未导入游戏包。\n请返回上一页导入。"
        }
        nativeLoadingView.setTextColor(Color.RED)
        
        mainHandler.postDelayed({ 
            if (!isFinishing && !isDestroyed) {
                nativeLoadingView.setTextColor(Color.WHITE)
                pollHealthAndLoad() 
            }
        }, 3000)
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || isDestroyed || io.isShutdown) return
        io.execute {
            if (isServerHealthy()) mainHandler.post { loadGame() }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { finish() }

    override fun onDestroy() {
        try { unregisterReceiver(serverReceiver) } catch (_: Exception) {}
        mainHandler.removeCallbacksAndMessages(null)
        io.shutdown()
        super.onDestroy()
    }
}