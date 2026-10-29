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

/**
 * 主界面：内嵌一个 WebView，指向本机 Node 服务器（127.0.0.1:3000）。
 * 优化：引入原生 Loading 页面 + 后台线程解压，解决首次冷启动黑屏问题。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    // 🌟 原生加载过渡层
    private lateinit var rootLayout: FrameLayout
    private lateinit var nativeLoadingView: TextView

    private var serverAddress: String? = null
    private var serverName: String? = null
    private val hookedUrls = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private var hookCheckDone = false

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

        // 0) 读取参数
        serverAddress = intent.getStringExtra(EXTRA_SERVER_ADDRESS)?.takeIf { it.isNotBlank() }
        serverName = intent.getStringExtra(EXTRA_SERVER_NAME)
        Log.i(TAG, "启动模式: " + if (serverAddress == null) "单机" else "联机 -> $serverAddress")

        // 1) 优先初始化 UI（确保秒出画面，告别黑屏）
        webView = WebView(this)
        webView.setBackgroundColor(Color.BLACK)
        
        rootLayout = FrameLayout(this)
        nativeLoadingView = TextView(this).apply {
            text = "正在准备游戏资源，首次启动可能较慢..."
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = android.view.Gravity.CENTER
        }
        
        rootLayout.addView(nativeLoadingView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        rootLayout.addView(webView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        
        webView.visibility = View.INVISIBLE
        nativeLoadingView.visibility = View.VISIBLE
        
        setContentView(rootLayout)

        // 2) 初始化 WebView 相关配置
        setupWebView()
        setupImmersiveMode()
        installBridgeIfNeeded()
        registerReceiverCompat()

        // 3) 🌟 将重活（解压、初始化）放到后台线程，防止阻塞主线程导致黑屏
        io.execute {
            try {
                if (!GamePack.hasActivePack(this)) {
                    AssetHelper.ensureAssetsExtracted(this)
                }
                FileLogger.init(this)
            } catch (e: Exception) {
                Log.e(TAG, "后台初始化失败", e)
            }

            // 4) 回到主线程启动服务
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                
                // 更新提示文字
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
                
                // 🌟 页面加载完了，WebView 登场，原生文字退场
                if (!isFinishing && !isDestroyed) {
                    webView.visibility = View.VISIBLE
                    nativeLoadingView.visibility = View.GONE
                }

                if (serverAddress != null) {
                    mainHandler.postDelayed({ runHookSelfCheck() }, 800)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                val m = msg ?: return true
                val line = "[${m.messageLevel()}] ${m.message()} (${m.sourceId()}:${m.lineNumber()})"
                Log.i(TAG, "CONSOLE $line")
                if (serverAddress != null &&
                    (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR ||
                     m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.WARNING)
                ) {
                    val text = m.message()
                    if (text.contains("net", ignoreCase = true) ||
                        text.contains("ws", ignoreCase = true) ||
                        text.contains("socket", ignoreCase = true) ||
                        text.contains("connect", ignoreCase = true)
                    ) {
                        mainHandler.post { showToastLike("JS: " + text.take(120)) }
                    }
                }
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
        if (!hookedUrls.add(key)) {
            Log.i(TAG, "重复注入 hook: $key")
        }
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url.toString()).openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "取 index.html 失败: HTTP $code")
                return null
            }
            val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

            val script = "<script>" + WsHook.build(addr) + "</script>"
            val patched = insertIntoHead(html, script)
            if (patched == null) {
                Log.w(TAG, "index.html 里找不到 <head>，改用 onPageStarted 兜底")
                return null
            }
            Log.i(TAG, "已注入 WebSocket hook -> $addr")
            WebResourceResponse(
                "text/html",
                "UTF-8",
                200,
                "OK",
                mapOf("Cache-Control" to "no-store", "Pragma" to "no-cache"),
                patched.toByteArray(Charsets.UTF_8).inputStream()
            )
        } catch (e: Exception) {
            Log.w(TAG, "注入失败: ${e.message}")
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
                    Log.w(TAG, "WebSocket 连接失败: $reason")
                    mainHandler.post {
                        if (isFinishing || isDestroyed) return@post
                        val target = serverName ?: serverAddress ?: ""
                        showToastLike("连不上 $target\n请确认对方已开服，或地址填错了")
                    }
                }

                @android.webkit.JavascriptInterface
                fun onSocketEvent(event: String?) {
                    Log.i(TAG, "WebSocket 事件: $event")
                    mainHandler.post {
                        if (isFinishing || isDestroyed) return@post
                        showToastLike("WS: $event")
                    }
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
                    v == "true" -> "联机已接管 → ${serverName ?: serverAddress}"
                    v == "false" -> "联机未生效（仍是单机）"
                    else -> "自检异常：$v"
                }
                webView.evaluateJavascript(
                    "(function(){try{var d=document.getElementById('__sh_diag');" +
                        "if(!d){d=document.createElement('div');d.id='__sh_diag';" +
                        "d.style.cssText='position:fixed;z-index:2147483647;left:8px;top:8px;" +
                        "padding:6px 10px;border-radius:6px;font:12px/1.4 monospace;" +
                        "background:rgba(0,0,0,.85);color:#0f0;pointer-events:none;" +
                        "box-shadow:0 2px 8px rgba(0,0,0,.5)';" +
                        "(document.body||document.documentElement).appendChild(d);}" +
                        "d.textContent=${jsLiteralForJs(msg)};}catch(e){}})()",
                    null
                )
                if (!hookCheckDone) {
                    hookCheckDone = true
                    showToastLike(msg)
                }
            }
        }
    }

    private fun jsLiteralForJs(s: String): String {
        val sb = StringBuilder("'")
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '\'' -> sb.append("\\'")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '<' -> sb.append("\\x3c")
                else -> sb.append(c)
            }
        }
        return sb.append("'").toString()
    }

    private fun setupImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun registerReceiverCompat() {
        val filter = IntentFilter(ACTION_SERVER_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serverReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(serverReceiver, filter)
        }
    }

    private fun startNodeService() {
        val intent = Intent(this, NodeService::class.java).apply {
            action = NodeService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Log.i(TAG, "已请求启动 NodeService")
    }

    private fun pollHealthAndLoad() {
        showLoadingPage()
        
        // 🌟 补丁：安全检查，防止线程池关闭后崩溃
        if (isFinishing || isDestroyed || io.isShutdown) {
            Log.w(TAG, "Activity 已销毁或线程池已关闭，取消本次健康检查")
            return
        }

        io.execute {
            var ok = false
            val maxTries = 180
            for (i in 1..maxTries) {
                if (isServerHealthy()) { ok = true; break }
                if (i % 4 == 0) {
                    val sec = i / 2
                    mainHandler.post { updateLoadingPage(sec) }
                }
                Thread.sleep(500)
            }
            Log.i(TAG, "健康检查结果: $ok")
            if (ok) mainHandler.post { loadGame() }
            else mainHandler.post { showErrorAndRetry() }
        }
    }

    // WebView 内部的次级加载页（在原生加载页之后显示）
    private fun showLoadingPage() {
        if (isFinishing || isDestroyed) return
        webView.loadDataWithBaseURL(
            null,
            """
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
              body{margin:0;height:100vh;display:flex;align-items:center;justify-content:center;
                   flex-direction:column;background:#0d1117;color:#e6edf3;
                   font-family:sans-serif;text-align:center}
              .spin{width:44px;height:44px;border:4px solid #30363d;border-top-color:#58a6ff;
                    border-radius:50%;animation:r 1s linear infinite;margin-bottom:22px}
              @keyframes r{to{transform:rotate(360deg)}}
              .t{font-size:17px;margin-bottom:8px}
              .s{font-size:13px;color:#7d8590}
            </style></head>
            <body>
              <div class="spin"></div>
              <div class="t">正在启动游戏…</div>
              <div class="s" id="tip">首次启动需要约 30~60 秒，请稍候</div>
            </body></html>
            """.trimIndent(),
            "text/html", "UTF-8", null
        )
    }

    private fun updateLoadingPage(sec: Int) {
        if (isFinishing || isDestroyed) return
        webView.evaluateJavascript(
            "document.getElementById('tip') && " +
            "(document.getElementById('tip').innerText='已等待 ${'$'}{sec} 秒，仍在启动…')",
            null
        )
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
        } catch (e: Exception) {
            false
        }
    }

    private fun loadGame() {
        if (webView.url?.startsWith(LOCAL_URL) == true) return
        val suffix = if (serverAddress != null) "?multiplayer=1" else ""
        Log.i(TAG, "加载游戏页面: $LOCAL_URL/index.html$suffix")
        webView.loadUrl("$LOCAL_URL/index.html$suffix")
    }

    private fun showErrorAndRetry() {
        val hasPack = GamePack.hasActivePack(this)
        val hint = if (hasPack)
            "<p>游戏服务还没起来（首次启动加载较慢）。</p>" +
            "<p style=\"color:#888\">游戏包已成功导入，导入流程本身正常；" +
            "稍等片刻会自动重试。</p>"
        else
            "<p>尚未导入游戏包。</p>" +
            "<p style=\"color:#888\">请返回上一页点击「导入游戏包 (.zip)」。</p>"

        webView.loadDataWithBaseURL(
            null,
            """
            <html><body style="background:#111;color:#eee;font-family:sans-serif;
                 display:flex;align-items:center;justify-content:center;flex-direction:column;height:100%;padding:0 24px;text-align:center">
            <h2>本地服务未就绪</h2>
            $hint
            <p style="color:#888">即将自动重试…</p>
            </body></html>
            """.trimIndent(),
            "text/html", "UTF-8", null
        )
        mainHandler.postDelayed({ pollHealthAndLoad() }, 3000)
    }

    override fun onResume() {
        super.onResume()
        // 🌟 补丁：防止线程池关闭后再次闪退
        if (isFinishing || isDestroyed || io.isShutdown) return
        
        io.execute {
            if (isServerHealthy()) mainHandler.post { loadGame() }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finish()
    }

    override fun onDestroy() {
        try { unregisterReceiver(serverReceiver) } catch (_: Exception) {}
        
        // 🌟 补丁：清空所有排队任务，防止 Activity 销毁后延迟任务诈尸
        mainHandler.removeCallbacksAndMessages(null)
        
        io.shutdown()
        super.onDestroy()
    }
}