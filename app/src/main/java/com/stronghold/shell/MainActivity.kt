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
import android.webkit.WebResourceError
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
    /** 联机模式：服务器是否走加密连接（wss） */
    private var serverTls: Boolean = false
    /** 已注入过 hook 的页面 URL，避免重复插入 */
    private val hookedUrls = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** 联机自检是否已执行过（onPageFinished 会多次触发，只弹一次提示） */
    private var hookCheckDone = false

    /**
     * 当前处于「已连接」状态的 WebSocket 集合。
     *
     * 为什么要按 id 记：游戏在同一页面里可能同时开着多条 WS（大厅一条、房间一条），
     * 旧的那条正常关闭时并不代表联机断了。只按「有没有收到过 close」判断会误报，
     * 用户就会看到「未连接 / 已连接」来回弹 —— 实际上连接一直是好的。
     */
    private val openSockets = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** 「已联机」成功提示只弹一次，之后的重连一律静默 */
    private var onlineAnnounced = false

    /** 「联机已断开」提示是否已弹过，避免掉线期间反复刷屏 */
    private var offlineAnnounced = false

    /** 断线确认延时任务：短时间内的抖动（重连、切换场景）不提示 */
    private val offlineConfirm = Runnable {
        if (isFinishing || isDestroyed) return@Runnable
        if (openSockets.isNotEmpty() || offlineAnnounced) return@Runnable
        offlineAnnounced = true
        val target = serverName ?: serverAddress ?: ""
        showToastLike("联机已断开 → $target\n正在自动重连…")
    }

    /**
     * 主框架加载失败后的自动重试次数。
     *
     * 为什么需要：Node 线程刚起来时端口还没监听，而 NodeRunner 会先发「已就绪」广播，
     * 于是 WebView 可能抢在端口就绪之前请求 index.html，拿到 ERR_CONNECTION_REFUSED。
     * 此时页面里只剩 WebView 自带的黑底错误页（表现为"黑屏"），必须回到加载页重试。
     */
    private var loadRetries = 0
    private val maxLoadRetries = 5

    /** 是否已有健康检查线程在跑（防止重试时叠加多个轮询） */
    @Volatile
    private var polling = false

    /** 最近一次导航是否以错误页告终（用于 onResume 自愈时判断要不要重新导航） */
    @Volatile
    private var lastLoadFailed = false

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
        /** 该服务器是否走加密连接（wss / https），由 ServerStore 规范化时判定 */
        const val EXTRA_SERVER_TLS = "server_tls"

        /** 需要注入 hook 的入口页面（唯一一个会插入脚本的路径） */
        private const val INDEX_PATH = "/index.html"
    }

    /** 服务状态广播接收器：服务就绪后加载页面 */
    private val serverReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val ready = intent?.getBooleanExtra(EXTRA_SERVER_READY, false) ?: false
            Log.i(TAG, "收到服务状态广播: ready=$ready")
            if (ready) loadGame()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 0) 读取联机参数。有地址 = 联机模式，无地址 = 单机（行为完全不变）
        serverAddress = intent.getStringExtra(EXTRA_SERVER_ADDRESS)?.takeIf { it.isNotBlank() }
        serverName = intent.getStringExtra(EXTRA_SERVER_NAME)
        serverTls = intent.getBooleanExtra(EXTRA_SERVER_TLS, false)
        Log.i(TAG, "启动模式: " + if (serverAddress == null) "单机"
            else "联机 -> $serverAddress" + if (serverTls) "（wss）" else "（ws）")

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

        // 6) 联机自检兜底：onPageFinished 不一定会触发（重定向/缓存命中时会被跳过），
        //    所以改用固定延时轮询，确保诊断一定跑起来
        if (serverAddress != null) {
            mainHandler.postDelayed({ runHookSelfCheck() }, 6000)
            mainHandler.postDelayed({ runHookSelfCheck() }, 15000)
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
                // 页面正常加载完成 → 重置重试计数
                if (url?.startsWith(LOCAL_URL) == true) loadRetries = 0
                // 联机自检：页面加载完立刻问一句 hook 在不在
                if (serverAddress != null) {
                    mainHandler.postDelayed({ runHookSelfCheck() }, 800)
                }
            }

            /**
             * 主框架加载失败（典型：Node 端口还没起来 → ERR_CONNECTION_REFUSED）。
             *
             * 不把 WebView 自带的黑底错误页留给用户，而是退回「正在启动游戏…」
             * 加载页，并在短暂延时后重新轮询健康检查 → 重新加载。
             */
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                val isMain = request?.isForMainFrame ?: false
                val url = request?.url?.toString() ?: return
                Log.w(TAG, "页面加载失败: $url -> ${error?.errorCode} ${error?.description}")
                if (!isMain || !url.startsWith(LOCAL_URL)) return
                lastLoadFailed = true
                if (loadRetries >= maxLoadRetries) {
                    Log.e(TAG, "重试 $loadRetries 次仍失败，保留错误页")
                    return
                }
                loadRetries++
                mainHandler.postDelayed({
                    if (isFinishing || isDestroyed) return@postDelayed
                    showLoadingPage()
                    pollHealthAndLoad()
                }, 600)
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

            val script = "<script>" + WsHook.build(addr, serverTls) + "</script>"
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
     * 联机模式下挂一个 JS 桥，用于连接状态提示。
     * 只在联机模式挂载；单机完全不挂。
     *
     * 【提示策略】宁缺勿滥 —— 联机本身没断时绝不该弹窗。
     *   - 第一次连上：弹一次「已联机 → 地址」
     *   - 之后的重连/切换场景：完全静默（游戏会频繁开关 WS，逐条提示就是刷屏）
     *   - 确实掉了：先等 [OFFLINE_CONFIRM_DELAY] 再确认，期间连上就当没发生
     *   - 掉线后重新连上：弹一次「已重新连上」，仅当之前确实报过掉线
     */
    @SuppressLint("JavascriptInterface")
    private fun installBridgeIfNeeded() {
        if (serverAddress == null) return
        webView.addJavascriptInterface(
            object {
                @android.webkit.JavascriptInterface
                fun onConnectionFailed(reason: String?) {
                    Log.w(TAG, "WebSocket 连接失败: $reason")
                    // 不立刻弹：可能只是重连过程中的一次失败，交给延时确认
                    mainHandler.post { scheduleOfflineConfirm() }
                }

                @android.webkit.JavascriptInterface
                fun onSocketEvent(event: String?) {
                    Log.i(TAG, "WebSocket 事件: $event")
                    val e = event ?: return
                    // 格式：LINK_OK|<id>   /   LINK_CLOSED|<id>|<code>
                    val parts = e.split('|')
                    val id = parts.getOrNull(1) ?: return
                    mainHandler.post {
                        if (isFinishing || isDestroyed) return@post
                        when (parts[0]) {
                            "LINK_OK" -> onSocketUp(id)
                            "LINK_CLOSED" -> onSocketDown(id)
                        }
                    }
                }
            },
            WsHook.BRIDGE_NAME
        )
    }

    /** 掉线确认延时：这段时间内重连成功就不提示（游戏切场景/心跳重连时会短暂断一下） */
    private val OFFLINE_CONFIRM_DELAY = 10000L

    private fun onSocketUp(id: String) {
        openSockets.add(id)
        mainHandler.removeCallbacks(offlineConfirm)
        val target = serverName ?: serverAddress ?: ""
        when {
            // 之前报过掉线，这次是真恢复了
            offlineAnnounced -> {
                offlineAnnounced = false
                showToastLike("已重新连上 → $target")
            }
            // 第一次连上，报一次
            !onlineAnnounced -> {
                onlineAnnounced = true
                showToastLike("已联机 → $target")
            }
            // 其余都是重连/多连接，静默
        }
    }

    private fun onSocketDown(id: String) {
        openSockets.remove(id)
        if (openSockets.isNotEmpty()) return   // 还有别的连接活着，不算断
        scheduleOfflineConfirm()
    }

    private fun scheduleOfflineConfirm() {
        if (openSockets.isNotEmpty()) return
        mainHandler.removeCallbacks(offlineConfirm)
        mainHandler.postDelayed(offlineConfirm, OFFLINE_CONFIRM_DELAY)
    }
    /**
     * 用 Toast 提示，不打断游戏（游戏是横屏全屏，Toast 也能用）。
     *
     * 一律用 LENGTH_SHORT（约 2 秒）：之前用 LENGTH_LONG（约 3.5 秒），
     * 全屏游戏里一条横贯屏幕的提示压那么久很碍事，而且这些文案都短，够看。
     */
    private fun showToastLike(message: String) {
        try {
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
    }

    /**
     * 联机自检：页面加载完成后问一句 window.__StrongholdHookInstalled。
     *
     * 只报「没接上」的情况，成功一律静默：
     *   - installed=true  → 不提示，等 WebSocket 真连上时由 onSocketEvent 弹「已联机」
     *   - installed=false → 提示 hook 没进去
     *   - 异常            → 提示 JS 桥没通
     * 这样成功路径上只有一条 Toast，不会和「联机已接管」重复。
     */
    private fun runHookSelfCheck() {
        if (isFinishing || isDestroyed) return
        // 只在真正的游戏页上自检。
        // 「正在启动游戏…」加载页是 loadDataWithBaseURL 渲染的，它同样会触发
        // onPageFinished，但那个上下文里当然没有 hook —— 不排除掉就会误报
        // 「联机未生效（仍是单机）」，让用户以为联机坏了。
        if (webView.url?.startsWith(LOCAL_URL) != true) return
        val js = "(function(){try{return String(window." + WsHook.HOOK_FLAG + "===true);}catch(e){return 'ERR:'+e.message;}})()"
        webView.evaluateJavascript(js) { raw ->
            val v = raw?.trim()?.trim('"') ?: "null"
            if (v != "true") {
                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    val msg = when {
                        v == "false" -> "联机未生效（仍是单机）"
                        else -> "自检异常：$v"
                    }
                    // 只做一次性 Toast 提示，不再往页面上画常驻横幅
                    // （那个 __sh_diag 会一直浮在左上角遮挡游戏 HUD）
                    if (!hookCheckDone) {
                        hookCheckDone = true
                        showToastLike(msg)
                    }
                }
            }
        }
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

        // 🌟 [修复补丁1]：防止 Activity 销毁或线程池关闭后，依然有闹钟叫醒它导致闪退
        if (isFinishing || isDestroyed || io.isShutdown) {
            Log.w(TAG, "Activity 已销毁或线程池已关闭，取消本次健康检查")
            return
        }
        // 避免重试时叠加多个轮询线程
        if (polling) {
            Log.i(TAG, "已有健康检查在跑，跳过本次")
            return
        }
        polling = true

        io.execute {
            var ok = false
            val maxTries = 180          // 180 × 500ms = 90 秒
            for (i in 1..maxTries) {
                if (isServerHealthy()) { ok = true; break }
                if (i % 4 == 0) {       // 每 2 秒刷新一次提示
                    val sec = i / 2
                    mainHandler.post { updateLoadingPage(sec) }
                }
                Thread.sleep(500)
            }
            polling = false
            Log.i(TAG, "健康检查结果: $ok")
            if (ok) mainHandler.post { loadGame(force = true) }
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

    /**
     * 加载游戏页面。
     *
     * @param force true 时忽略「已经在 LOCAL_URL 上」的判断，强制重新加载。
     *        健康检查成功后必须用 force=true：首次加载可能因为 Node 端口还没监听
     *        而失败，此时 webView.url 已经是 LOCAL_URL，不加 force 就永远不会重试，
     *        用户看到的就是 WebView 的黑底错误页（"黑屏"）。
     */
    private fun loadGame(force: Boolean = false) {
        if (!force && webView.url?.startsWith(LOCAL_URL) == true) return
        lastLoadFailed = false
        // 重新加载 = 旧页面的 WebSocket 全部作废，清掉记账避免残留 id 把「已断开」压住
        openSockets.clear()
        // 联机模式下加一个查询串，确保 URL 唯一、绕开 WebView 缓存，
        // 保证 shouldInterceptRequest 一定被调用（否则 hook 注不进去）
        val suffix = if (serverAddress != null) "?multiplayer=1" else ""
        Log.i(TAG, "加载游戏页面: $LOCAL_URL/index.html$suffix")
        webView.loadUrl("$LOCAL_URL/index.html$suffix")
    }

    /** 服务没起来时的兜底提示（黑屏看门狗思路：不崩，只提示 + 重试） */
    private fun showErrorAndRetry() {
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
        // 🌟 [修复补丁2]：防止线程池关闭后，重新回到前台时再次闪退
        if (isFinishing || isDestroyed || io.isShutdown) return
        
        // 自愈：回到前台时若服务健康但上一次导航是错误页，则强制重新导航。
        // 注意不能无条件 loadGame()：url 已经是 LOCAL_URL 时会被守卫拦下，
        // 且无条件强刷会在每次切回前台时重载游戏、丢掉进度。
        io.execute {
            if (isServerHealthy() && lastLoadFailed) mainHandler.post { loadGame(force = true) }
        }
    }

    /**
     * 返回键：退出游戏页，回到启动器（而不是直接退出整个 App）。
     * Node 服务是前台服务，会继续在后台跑，再次点「启动游戏」可秒回。
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        super.onBackPressed()
        finish()
    }

    override fun onDestroy() {
        try { unregisterReceiver(serverReceiver) } catch (_: Exception) {}
        
        // 🌟 [修复补丁3]：清空主线程中所有还没执行的延迟任务（比如那个3秒重试），防止诈尸闪退
        mainHandler.removeCallbacksAndMessages(null)
        
        io.shutdown()
        super.onDestroy()
    }
}