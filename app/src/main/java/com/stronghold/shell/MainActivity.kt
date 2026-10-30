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
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
 *
 * 启动流程：
 *   1. onCreate → AssetHelper.ensureAssetsExtracted()（把 assets/app_bundle.zip 解到私有目录）
 *   2. 启动 NodeService（前台服务，进程内跑 Node）
 *   3. 轮询 /healthz，健康后 WebView 加载 index.html
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    /** 联机模式：远端服务器地址；null 表示单机模式 */
    private var serverAddress: String? = null
    /** 联机模式：服务器显示名，仅用于提示 */
    private var serverName: String? = null
    /** 已注入过 hook 的页面 URL，避免重复插入 */
    private val hookedUrls = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** 联机自检是否已执行过（onPageFinished 会多次触发，只弹一次提示） */
    private var hookCheckDone = false

    /** 游戏页面是否已经真正加载过（防止重复 loadUrl / 加载页覆盖游戏） */
    @Volatile
    private var gameLoaded = false

    companion object {
        private const val TAG = "StrongholdShell"
        const val LOCAL_URL = "http://127.0.0.1:3000"
        const val HEALTH_URL = "$LOCAL_URL/healthz"
        const val ACTION_SERVER_STATE = "com.stronghold.shell.SERVER_STATE"
        const val EXTRA_SERVER_READY = "server_ready"

        /**
         * 联机模式：远端服务器地址（"host:port"）。
         * 有值时本页会 hook WebSocket，把 /ws 连接改写到远端；
         * 页面与素材仍然由手机内的 Node 在 127.0.0.1:3000 提供。
         */
        const val EXTRA_SERVER_ADDRESS = "server_address"
        /** 联机模式下用于界面提示的服务器名 */
        const val EXTRA_SERVER_NAME = "server_name"

        /** 需要注入 hook 的入口页面（唯一一个会插入脚本的路径） */
        private const val INDEX_PATH = "/index.html"
    }

    /** 服务状态广播接收器：只记录状态，加载由健康检查统一决定 */
    private val serverReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val ready = intent?.getBooleanExtra(EXTRA_SERVER_READY, false) ?: false
            Log.i(TAG, "收到服务状态广播: ready=$ready")
            // 注意：这个 ready=true 只代表「Node 线程已启动」，并不代表 HTTP 已经能响应。
            // 之前在这里直接 loadGame()，会抢在服务真正就绪前加载，造成首屏黑屏/卡住；
            // 返回再进时服务早就好了，所以才会「秒进」。
            // 现在统一交给 pollHealthAndLoad() 用真实 HTTP 探测决定，这里不再触发加载。
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 0) 读取联机参数。有地址 = 联机模式，无地址 = 单机（行为完全不变）
        serverAddress = intent.getStringExtra(EXTRA_SERVER_ADDRESS)?.takeIf { it.isNotBlank() }
        serverName = intent.getStringExtra(EXTRA_SERVER_NAME)
        Log.i(TAG, "启动模式: " + if (serverAddress == null) "单机" else "联机 -> $serverAddress")

        // 1) 仅在「没有导入游戏包」时，才回退解压 assets 内置资源
        //    （启动器模式：用户导入的包在 GamePack.activeDir，不需要这步）
        if (!GamePack.hasActivePack(this)) {
            AssetHelper.ensureAssetsExtracted(this)
        }
        FileLogger.init(this)

        // 2) 创建 WebView
        webView = WebView(this)
        webView.setBackgroundColor(Color.BLACK)
        setContentView(
            webView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        setupWebView()

        // 2.5) 沉浸式全屏：隐藏状态栏 + 导航栏，滑动才临时唤出（需在 setContentView 之后）
        setupImmersiveMode()
        // 2.6) 联机模式：挂 JS 桥（单机不挂，行为与旧版完全一致）
        installBridgeIfNeeded()

        // 3) 注册服务状态广播
        registerReceiverCompat()

        // 4) 启动 Node 服务
        startNodeService()

        // 5) 轮询健康检查（服务可能比 UI 慢）
        pollHealthAndLoad()

        // 注意：联机自检不在这里计时。
        // 首次启动 Node 可能要几十秒，那时 WebView 还停在加载页，
        // 此时自检必然读不到 hook 标志，会误报「联机未生效」并消耗掉唯一的一次提示机会。
        // 自检统一改到 loadGame() 真正发起页面加载之后才开始计时。
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
        // 联机模式关键：WebView 混合内容策略。
        // 页面来自 http://127.0.0.1（被视为安全上下文），联机时 WebSocket 要连
        // ws://公网IP（明文），默认会被 WebView 当作混合内容静默拦截 —— 表现就是
        // 服务器一条连接都收不到。这里在联机模式下显式放开。
        if (serverAddress != null) {
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        // 联机模式下必须绕开缓存：否则 index.html 可能直接来自缓存，
        // shouldInterceptRequest 不触发 → hook 注不进去（这是联机失效的头号原因）
        settings.cacheMode = if (serverAddress != null) WebSettings.LOAD_NO_CACHE
                             else WebSettings.LOAD_DEFAULT
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true

        // 本地资源加载器：game.local 域名映射到私有 assets 目录
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/res/", WebViewAssetLoader.ResourcesPathHandler(this))
            .build()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                // 1) 联机模式下拦截入口页面，把 WebSocket hook 插到 <head> 最前面
                if (serverAddress != null && isIndexRequest(request.url)) {
                    injectIntoIndex(request.url)?.let { return it }
                }
                // 2) 其余交给 assetLoader（game.local 等自定义域名）
                return assetLoader.shouldInterceptRequest(request.url)
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 联机自检：页面加载完立刻问一句 hook 在不在
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
                // 联机模式下把 warn/error 直接弹出来，省得抓 logcat
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

        // 注入 JS 桥（名字必须与前端约定一致）
        webView.addJavascriptInterface(AndroidBridge(this, webView), "AndroidNative")

        // 长按禁用（游戏内不需要）
        webView.isLongClickable = false
        webView.setOnLongClickListener { true }
    }

    /**
     * 判断这是不是需要注入的入口页面请求。
     * 只认本机 Node 上的 /index.html（含根路径），其它一律不碰。
     */
    private fun isIndexRequest(url: android.net.Uri): Boolean {
        val host = url.host ?: return false
        if (host != "127.0.0.1" && host != "localhost") return false
        val path = url.path ?: return false
        // 忽略查询串：loadGame 会带 ?multiplayer=1 绕缓存
        return path == INDEX_PATH || path == "/" || path.isEmpty()
    }

    /**
     * 从本机 Node 取回 index.html，把 WebSocket hook 插到 <head> 之后返回。
     *
     * 为什么不用 onPageStarted：那是异步投递的，页面里的内联脚本可能先跑，
     * 导致上游在 hook 生效前就把 WebSocket 取了引用。在 HTML 里插入 <script>
     * 能保证 hook 严格先于页面上任何脚本执行。
     *
     * 任何异常都返回 null → 退回原生加载，最坏情况是联机不生效但游戏能开。
     */
    private fun injectIntoIndex(url: android.net.Uri): WebResourceResponse? {
        val addr = serverAddress ?: return null
        val key = url.toString()
        if (!hookedUrls.add(key)) {
            // 已经注入过（页面刷新/重进），仍要注入，只是记一笔日志
            Log.i(TAG, "重复注入 hook: $key")
        }
        return try {
            val conn = URL(url.toString()).openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                Log.w(TAG, "取 index.html 失败: HTTP $code")
                return null
            }
            val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            conn.disconnect()

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
                mapOf(
                    "Cache-Control" to "no-store",
                    "Pragma" to "no-cache"
                ),
                patched.toByteArray(Charsets.UTF_8).inputStream()
            )
        } catch (e: Exception) {
            Log.w(TAG, "注入失败: ${e.message}")
            null
        }
    }

    /**
     * 把脚本插到 <head> 标签之后（大小写不敏感）。
     * 找不到 <head> 时退而插入到 <html> 之后；都没有就返回 null。
     */
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
        // 连 <html> 都没有（极少数情况）：直接放最前面
        return script + html
    }

    /**
     * 联机模式下挂一个 JS 桥，用于连接失败时给用户明确提示。
     * 只暴露一个无参回调，且只在联机模式挂载；单机完全不挂。
     */
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
    /** 用一个小浮层提示，不打断游戏（游戏是横屏全屏，Toast 也能用） */
    private fun showToastLike(message: String) {
        try {
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
        }
    }

    /**
     * 联机自检：页面加载完成后问一句 window.__StrongholdHookInstalled。
     *
     * 三种结果都明确提示，避免「以为连上了其实没有」：
     *   - installed=true  → 绿色Toast，提示已接管
     *   - installed=false → 红色Toast，说清 hook 没进去
     *   - 异常            → 红色Toast，说清 JS 桥没通
     */
    private fun runHookSelfCheck(isFinal: Boolean = true) {
        if (isFinishing || isDestroyed) return
        val js = "(function(){try{return String(window." + WsHook.HOOK_FLAG + "===true);}catch(e){return 'ERR:'+e.message;}})()"
        webView.evaluateJavascript(js) { raw ->
            val v = raw?.trim()?.trim('"') ?: "null"
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                // 第 3 秒那次可能页面还没解析完，hook 标志天然读不到。
                // 非最终轮如果读不到，先什么都不做，等第 10 秒那次再下结论，
                // 免得用户刚进游戏就看到一片红字。
                if (!isFinal && v != "true") return@post
                // 只保留「未生效」这种异常状态的常驻显示；
                // 接管成功属于正常情况，横幅展示几秒即可自动消失，不遮挡游戏画面。
                val msg = when {
                    v == "true" -> "联机已接管 → ${serverName ?: serverAddress}"
                    v == "false" -> "联机未生效（仍是单机）"
                    else -> "自检异常：$v"
                }
                val keepOnScreen = v != "true"   // 异常才常驻，成功则自动淡出
                // 正常=绿色；未生效/自检异常=红色，否则报错却显示绿色会误导用户
                val color = if (keepOnScreen) "#ff5252" else "#0f0"
                webView.evaluateJavascript(
                    "(function(){try{var d=document.getElementById('__sh_diag');" +
                        "if(!d){d=document.createElement('div');d.id='__sh_diag';" +
                        "d.style.cssText='position:fixed;z-index:2147483647;left:8px;top:8px;" +
                        "padding:6px 10px;border-radius:6px;font:12px/1.4 monospace;" +
                        "background:rgba(0,0,0,.85);color:#0f0;pointer-events:none;" +
                        "box-shadow:0 2px 8px rgba(0,0,0,.5);transition:opacity .5s ease';" +
                        "(document.body||document.documentElement).appendChild(d);}" +
                        // 自检会跑两次（3s / 10s），先清掉上一轮的定时器，
                        // 否则旧定时器会把新横幅提前淡出或误删。
                        "if(d.__shTimer){clearTimeout(d.__shTimer);d.__shTimer=null;}" +
                        "if(d.__shRmTimer){clearTimeout(d.__shRmTimer);d.__shRmTimer=null;}" +
                        "d.style.color='$color';d.style.opacity='1';" +
                        "d.textContent=${jsLiteralForJs(msg)};" +
                        "if(!$keepOnScreen){d.__shTimer=setTimeout(function(){try{d.style.opacity='0';" +
                        "d.__shRmTimer=setTimeout(function(){try{if(d.parentNode)d.parentNode.removeChild(d);}catch(e2){}},600);" +
                        "}catch(e1){}},3000);}" +
                        "}catch(e){}})()",
                    null
                )
                if (!hookCheckDone) {
                    hookCheckDone = true
                    showToastLike(msg)
                }
            }
        }
    }

    /** 把 Kotlin 字符串转成可安全嵌入 JS 的字面量（含引号） */
    private fun jsLiteralForJs(s: String): String {
        val sb = StringBuilder("'")
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '\'' -> sb.append("\\'")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '<' -> sb.append("\\x3c")
                // JS 里 \u2028 / \u2029 是行终止符，不转义会直接报 SyntaxError
                '\u2028' -> sb.append("\\u2028")
                '\u2029' -> sb.append("\\u2029")
                else -> sb.append(c)
            }
        }
        return sb.append("'").toString()
    }


    /**
     * 沉浸式全屏：内容延伸到状态栏/导航栏区域，并隐藏两者。
     * 用户从边缘滑动时临时唤出，几秒后自动收起（BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE）。
     */
    private fun setupImmersiveMode() {
        // 让内容延伸到系统栏区域（配合 hide 才是真全屏）
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val controller = WindowInsetsControllerCompat(window, window.decorView)
        // 系统栏隐藏后，滑动才临时显示
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        // 隐藏状态栏 + 导航栏
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    /** 窗口重新获得焦点时再隐藏一次（有些系统/场景会把系统栏弹回来） */
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

    /**
     * 轮询健康检查，健康则加载游戏。
     *
     * 上游 Node 服务器首次启动需要较长时间（实测几十秒 ~ 1 分钟，
     * 因为要加载 21165 个文件的站点资源）。故：
     *   - 轮询上限放宽到 ~90 秒（180 × 500ms）
     *   - 轮询期间显示「正在启动游戏…」加载页（而非黑屏）
     *   - 每 2 秒更新一次已等待时间，让用户知道在推进
     */
    private fun pollHealthAndLoad() {
        showLoadingPage()
        // 每 2 秒刷新一次提示（由主线程定时器驱动，不占 IO 线程）
        val ticker = object : Runnable {
            var sec = 0
            override fun run() {
                if (isFinishing || isDestroyed) return
                if (gameLoaded) return
                sec += 2
                updateLoadingPage(sec)
                mainHandler.postDelayed(this, 2000)
            }
        }
        mainHandler.postDelayed(ticker, 2000)

        io.execute {
            var ok = false
            // 用时间戳做截止，避免单次探测耗时 5 秒导致总等待被放大到十几分钟
            val deadline = SystemClock.elapsedRealtime() + 90_000L
            while (SystemClock.elapsedRealtime() < deadline) {
                // Activity 已销毁 / 游戏已加载就立刻退出，避免后台空转并长期持有 Activity
                if (isFinishing || isDestroyed || gameLoaded) {
                    mainHandler.removeCallbacks(ticker)
                    return@execute
                }
                if (isServerHealthy()) { ok = true; break }
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    mainHandler.removeCallbacks(ticker)
                    return@execute
                }
            }
            mainHandler.removeCallbacks(ticker)
            if (isFinishing || isDestroyed) return@execute
            Log.i(TAG, "健康检查结果: $ok")
            if (ok) mainHandler.post { loadGame() }
            else mainHandler.post { showErrorAndRetry() }
        }
    }

    /** 启动中：显示加载页（替代黑屏） */
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

    /** 更新加载页上的等待秒数 */
    private fun updateLoadingPage(sec: Int) {
        if (isFinishing || isDestroyed) return
        // 只在还停留在加载页时更新
        webView.evaluateJavascript(
            "document.getElementById('tip') && " +
            "(document.getElementById('tip').innerText='已等待 ${'$'}{sec} 秒，仍在启动…')",
            null
        )
    }

    private fun isServerHealthy(): Boolean {
        return try {
            val conn = URL(HEALTH_URL).openConnection() as HttpURLConnection
            // Node 首次启动要加载 2 万多个站点文件，CPU 打满时连本机也会慢；
            // 1.5 秒太急会误判「未就绪」，从而白等满 90 秒。放宽到 5 秒。
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Connection", "close")
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) {
            false
        }
    }

    private fun loadGame() {
        // 已经加载过游戏主页面就不再重复 loadUrl。
        // 旧逻辑用 webView.url 判断，但加载页（loadDataWithBaseURL，url=null）和
        // 中间态都会让它误判，导致重复加载、把已经跑起来的游戏顶掉。
        if (isFinishing || isDestroyed) return
        if (gameLoaded) return
        if (webView.url?.startsWith(LOCAL_URL) == true && !webView.url.orEmpty().contains("about:blank")) {
            gameLoaded = true
            return
        }
        gameLoaded = true
        // 联机模式下加一个查询串，确保 URL 唯一、绕开 WebView 缓存，
        // 保证 shouldInterceptRequest 一定被调用（否则 hook 注不进去）
        val suffix = if (serverAddress != null) "?multiplayer=1" else ""
        Log.i(TAG, "加载游戏页面: $LOCAL_URL/index.html$suffix")
        webView.loadUrl("$LOCAL_URL/index.html$suffix")

        // 联机自检：必须在真正开始加载游戏页面之后才计时。
        // 放在 onCreate 里会在还停在加载页时就去问 hook 标志，必然误报「未生效」。
        if (serverAddress != null) {
            mainHandler.postDelayed({ runHookSelfCheck(isFinal = false) }, 3000)
            mainHandler.postDelayed({ runHookSelfCheck(isFinal = true) }, 10000)
        }
    }

    /** 服务没起来时的兜底提示（黑屏看门狗思路：不崩，只提示 + 重试） */
    private fun showErrorAndRetry() {
        // 生命周期守卫：投递到主线程时 Activity 可能正在销毁
        if (isFinishing || isDestroyed) return
        // 区分两种情形，给用户明确指引：
        //   ① 有导入包但 Node 没起来 → 多半是首次启动还没加载完 / 被系统回收
        //   ② 压根没导入包 → 提示先去导入
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
        // 自愈：回到前台时若服务健康但页面没加载好，则重新导航
        io.execute {
            if (isServerHealthy()) mainHandler.post { loadGame() }
        }
    }

        /**
     * 返回键：退出游戏页，回到启动器（而不是直接退出整个 App）。
     * Node 服务是前台服务，会继续在后台跑，再次点「启动游戏」可秒回。
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finish()
    }

    override fun onDestroy() {
        // 清掉所有挂起的 Runnable（加载页 ticker、联机自检 3s/10s 等），
        // 否则 Handler 会持有已销毁的 Activity 直到延时结束，造成内存泄漏。
        mainHandler.removeCallbacksAndMessages(null)
        io.shutdownNow()
        try { unregisterReceiver(serverReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}