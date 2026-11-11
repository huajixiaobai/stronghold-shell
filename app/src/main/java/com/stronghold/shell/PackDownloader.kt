package com.stronghold.shell

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 游戏包下载器 —— 从 GitHub Release 下载 zip，支持断点续传、多镜像测速与多轮重试。
 *
 * 【为什么不用第三方库】零依赖，避免 APK 变大；HttpURLConnection 足够。
 *
 * 【断点续传原理】用 Range: bytes=N- 请求剩余部分，配合 RandomAccessFile 追加写。
 *   服务器不支持 Range（返回 200 而非 206）时，从头重下。
 *
 * 【镜像策略】GitHub 直连在国内经常 000/超时，
 *   且实测所有通道都可能静默截断（镜像只下 36%、直连只下 83%），
 *   所以必须「断点续传 + 大小强校验 + 多轮重试」。
 *
 *   镜像列表（参考同类项目实测，按国内可用性排序）：
 *     gh-proxy.com / ghfast.top / ghproxy.net / gh.monlor.com / slink.ltd / gh.llkk.cc
 *   先用 [probeMirrors] 并发测速，挑最快的几个开始下载。
 */
object PackDownloader {

    private const val TAG = "StrongholdShell"

    /** 下载进度回调（在调用线程执行，通常是后台线程） */
    interface Listener {
        /** @param downloaded 已下载字节 @param total 总字节（未知时 <=0） */
        fun onProgress(downloaded: Long, total: Long)
        /** 切换镜像时通知（第 idx 个，共 count 个） */
        fun onMirror(idx: Int, count: Int, name: String) {}
        /** 实时速度（字节/秒），用于界面显示和用户判断是否手动换源 */
        fun onSpeed(bytesPerSec: Long) {}
    }

    /**
     * 手动换源请求：外部（界面上的「换个源」按钮）调 [requestSwitchMirror]，
     * 下载线程在下一轮循环读到标记后立刻放弃当前通道，换下一个。
     *
     * 用 AtomicBoolean 而不是 @Volatile 布尔：getAndSet 是原子操作，
     * 避免「读了 true、还没置 false 时又被点一次」丢请求。
     */
    private val switchRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 用户指定的目标通道（null = 不加指定，按原顺序往后轮） */
    @Volatile
    private var switchTarget: Pair<String, String>? = null

    /** 当前正在用的连接，换源时直接断开它，避免卡在建连/读响应头阶段换不走 */
    @Volatile
    private var activeConn: java.net.HttpURLConnection? = null

    /** 最近一次抢跑/体检得到的通道状态，供下载中弹窗展示 */
    @Volatile
    var lastProbeResult: List<Probe> = emptyList()
        private set

    fun rememberProbe(r: List<Probe>) {
        lastProbeResult = r
    }

    /** 换到下一个（不指定） */
    fun requestSwitchMirror() {
        switchTarget = null
        switchRequested.set(true)
        try {
            activeConn?.disconnect()
        } catch (_: Exception) {
        }
    }

    /** 换到指定通道 */
    fun requestSwitchMirrorTo(prefix: String, name: String) {
        switchTarget = prefix to name
        switchRequested.set(true)
        try {
            activeConn?.disconnect()
        } catch (_: Exception) {
        }
    }

    /** 供 tryDownloadOnce 检查：返回 true 表示外部要求换源 */
    private fun consumeSwitchRequest(): Boolean = switchRequested.getAndSet(false)

    /** 取出并清空用户指定的目标通道 */
    private fun takeSwitchTarget(): Pair<String, String>? {
        val t = switchTarget
        switchTarget = null
        return t
    }

    /** 一轮下载内最多尝试的镜像数 */
    private const val MIRRORS_PER_ROUND = 4

    /** 完整下载最多重试轮数（每轮都会续传，不会从头重下） */
    private const val MAX_ROUNDS = 4

    /**
     * 下载通道列表。空前缀 = 直接用官方 URL。
     *
     * 【顺序说明】前 4 项与桌面启动器 updater.cjs 的 mirrors 数组**完全一致**：
     *   githubproxy.cc → gh-proxy.com → moeyy.cn/gh-proxy → 官方直连（兜底）
     * 桌面端实测这套顺序可用，所以这里直接沿用，并把官方直连从原来的第一位
     * 挪到第三位之后 —— 国内环境下直连经常超时，放最前面只会白白拖时间。
     *
     * 后面 3 项是壳原有的备选，保留作为额外兜底（顺序靠后，不影响首选）。
     *
     * 【什么时候会用到这个顺序】
     *   1. SteppedUpdater 逐级升级：不走测速，直接按本表顺序试 —— 第一项就是 githubproxy.cc
     *   2. 用户在「选择下载通道」里点「不测速，直接下」→ PackDownloader.allAsIs()
     *   3. 测速全部失败时的兜底轮转
     * 若走了测速（probeAll），结果会按**实测速度**重排，本顺序只作为同速时的参考。
     */
    val MIRRORS = listOf(
        // ---- 与桌面端一致的 4 项，顺序不要随意调 ----
        "https://githubproxy.cc/" to "githubproxy.cc",
        "https://gh-proxy.com/" to "gh-proxy.com",
        "https://moeyy.cn/gh-proxy/" to "moeyy.cn/gh-proxy",
        "" to "GitHub 原版直连",
        // ---- 壳原有备选，仅作兜底 ----
        "https://ghproxy.net/" to "ghproxy.net",
        "https://ghfast.top/" to "ghfast.top",
        "https://gh.monlor.com/" to "gh.monlor.com"
    )

    /** 单个通道的探测结果 */
    data class Probe(
        val prefix: String,
        val name: String,
        /** 是否可用（拿到了真 ZIP 数据） */
        val ok: Boolean,
        /** 测速耗时（毫秒），失败时为 -1 */
        val costMs: Long = -1,
        /** 失败原因，成功时为空 */
        val reason: String = ""
    )

    /**
     * 抢跑式探测：所有通道同时开始，**第一个成功的立刻返回**，
     * 不再等最慢的那个。
     *
     * 原来的写法是等全部通道跑完再排序，3 个死链各拖 8 秒，用户要等 8 秒以上。
     * 现在只要有一个通道成功（实测最快 1.2 秒），马上就能开始下载，
     * 剩下的通道继续在后台跑，结果缓存起来给后续换源用。
     *
     * @param onWinner 第一个成功的通道，在探测线程里回调
     * @param totalTimeoutMs 总超时，到点还没成功的强制放弃
     * @return 所有探测结果（含后台补完的），供界面显示
     */
    fun probeRacing(
        url: String,
        totalTimeoutMs: Int = 6000,
        onWinner: (Pair<String, String>) -> Unit
    ): List<Probe> {
        val results = Collections.synchronizedList(ArrayList<Probe>())
        val pool = Executors.newFixedThreadPool(MIRRORS.size)
        val winnerFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        val latch = java.util.concurrent.CountDownLatch(1)

        for ((prefix, name) in MIRRORS) {
            pool.execute {
                val p = probeOne(url, prefix, name, 3000)
                results.add(p)
                if (p.ok && winnerFlag.compareAndSet(false, true)) {
                    // 抢到了：立刻通知调用方开始下载，不等别人
                    try {
                        onWinner(prefix to name)
                    } catch (_: Exception) {
                    }
                    latch.countDown()
                }
            }
        }
        // 等第一个成功者，或者等到总超时
        try {
            latch.await(totalTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        }
        pool.shutdown()
        // 给后台线程一点时间补完结果（用于界面显示），但不阻塞太久
        try {
            pool.awaitTermination(1500, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        }
        val snapshot = ArrayList(results)
        for ((prefix, name) in MIRRORS) {
            if (snapshot.none { it.prefix == prefix }) {
                snapshot.add(Probe(prefix, name, false, reason = "探测中"))
            }
        }
        return snapshot.filter { it.ok }.sortedBy { it.costMs } + snapshot.filter { !it.ok }
    }

    /**
     * 逐个体检所有通道，返回全部结果（失败的也在内），供界面展示。
     * 成功项按耗时升序排在前面，失败项排在后面。
     */
    fun probeAll(url: String, threads: Int = 5, timeoutMs: Int = 4000): List<Probe> {
        val results = Collections.synchronizedList(ArrayList<Probe>())
        val pool = Executors.newFixedThreadPool(threads)
        for ((prefix, name) in MIRRORS) {
            pool.execute {
                results.add(probeOne(url, prefix, name, timeoutMs))
            }
        }
        pool.shutdown()
        try {
            // 每个通道最多等 timeoutMs，留足余量，避免结果缺项
            pool.awaitTermination(timeoutMs.toLong() * 4 + 4000, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        }
        val resultsFinal = ArrayList(results)
        // 没回来的通道也要列出，否则用户看不到它们为什么"消失"
        for ((prefix, name) in MIRRORS) {
            if (resultsFinal.none { it.prefix == prefix }) {
                resultsFinal.add(Probe(prefix, name, false, reason = "无响应/超时"))
            }
        }
        val okList = resultsFinal.filter { it.ok }.sortedBy { it.costMs }
        val badList = resultsFinal.filter { !it.ok }
        return okList + badList
    }

    /** 单个通道体检 */
    private fun probeOne(url: String, prefix: String, name: String, timeoutMs: Int): Probe {
        val full = if (prefix.isEmpty()) url else prefix + url
        val t0 = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        try {
            conn = URL(full).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "StrongholdShell-Launcher")
            // 禁止代理做透明压缩：否则拿不到真实 Content-Length，二进制流也可能被改坏
            conn.setRequestProperty("Accept-Encoding", "identity")
            conn.setRequestProperty("Range", "bytes=0-65535")
            val code = conn.responseCode
            if (code != 200 && code != 206) {
                return Probe(prefix, name, false, reason = "HTTP $code")
            }
            // 注意：不能靠 content-type 判断！实测 GitHub 原版直连返回真 ZIP，
            // 但 content-type 却被标成 text/html。唯一可信的判据是读真实字节看魔数。
            val buf = ByteArray(65536)
            var got = 0
            try {
                conn.inputStream.use { ins ->
                    while (got < buf.size) {
                        val n = ins.read(buf, got, buf.size - got)
                        if (n <= 0) break
                        got += n
                    }
                }
            } catch (_: Exception) {
            }
            // 立刻断开：若服务器不支持 Range 会返回 200 并准备推送整个 800MB，
            // 不主动断开的话 close() 会去 drain 剩余响应体，把测速线程拖死
            try {
                conn.disconnect()
            } catch (_: Exception) {
            }
            if (got < 4) return Probe(prefix, name, false, reason = "只读到 $got 字节")
            val isZip = buf[0] == 0x50.toByte() && buf[1] == 0x4B.toByte() &&
                buf[2] == 0x03.toByte() && buf[3] == 0x04.toByte()
            if (!isZip) return Probe(prefix, name, false, reason = "不是压缩包")
            return Probe(prefix, name, true, System.currentTimeMillis() - t0)
        } catch (e: Exception) {
            val r = when {
                e is java.net.SocketTimeoutException -> "超时"
                e is java.net.UnknownHostException -> "域名解析失败"
                e is java.net.ConnectException -> "连接被拒"
                else -> e.message?.take(30) ?: "异常"
            }
            return Probe(prefix, name, false, reason = r)
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /** 空通道（不测试、直接按顺序试）时用 */
    fun allAsIs(): List<Pair<String, String>> = MIRRORS

    /**
     * 并发测速：对每个镜像做一次 Range 小请求，测出响应速度。
     *
     * @return 可用的镜像，按速度从快到慢排序
     */
    fun probeMirrors(url: String, threads: Int = 4, timeoutMs: Int = 3500): List<Pair<String, String>> {
        // 统一走 probeAll，保证判据只有一套：读真实字节验 ZIP 魔数，
        // 不看 content-type（GitHub 原版直连会把它错标成 text/html）
        return probeAll(url, threads, timeoutMs).filter { it.ok }.map { it.prefix to it.name }
    }

    /** 保留旧实现，仅供参考，不再被调用 */
    @Suppress("unused")
    private fun probeMirrorsLegacy(url: String, threads: Int = 4, timeoutMs: Int = 3500): List<Pair<String, String>> {
        val results = Collections.synchronizedList(ArrayList<Triple<Long, String, String>>())
        val pool = Executors.newFixedThreadPool(threads)
        for ((prefix, name) in MIRRORS) {
            pool.execute {
                val full = if (prefix.isEmpty()) url else prefix + url
                val t0 = System.currentTimeMillis()
                var conn: HttpURLConnection? = null
                try {
                    conn = URL(full).openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = true
                    conn.connectTimeout = timeoutMs
                    conn.readTimeout = timeoutMs
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("User-Agent", "StrongholdShell-Launcher")
                    conn.setRequestProperty("Range", "bytes=0-65535")
                    val code = conn.responseCode
                    // 必须是 200/206：301/302 说明这个镜像只是把请求转出去，
                    // 自己也拿不到文件；403/404 说明路径没转对
                    if (code == 200 || code == 206) {
                        // 排除 HTML 错误页：镜像失效时常常返回一个 200 的错误页，
                        // 内容只有几百字节 HTML，读得到数据但根本不是压缩包
                        val ct = conn.contentType ?: ""
                        val isHtml = ct.contains("text/html", ignoreCase = true)
                        if (isHtml) {
                            Log.d(TAG, "测速排除 $name：返回 HTML 而非文件")
                        } else {
                            // 读满 64KB 再算速度：读太少测不出真实带宽，
                            // 错误页恰恰因为小才快，会污染排序
                            val buf = ByteArray(65536)
                            var got = 0
                            conn.inputStream.use { ins ->
                                while (got < buf.size) {
                                    val n = ins.read(buf, got, buf.size - got)
                                    if (n <= 0) break
                                    got += n
                                }
                            }
                            // 必须确认是真 ZIP（PK\x03\x04）：这是最后一道关
                            val isZip = got >= 4 && buf[0] == 0x50.toByte() &&
                                buf[1] == 0x4B.toByte() &&
                                buf[2] == 0x03.toByte() && buf[3] == 0x04.toByte()
                            if (isZip) {
                                val cost = System.currentTimeMillis() - t0
                                results.add(Triple(cost, prefix, name))
                            } else {
                                Log.d(TAG, "测速排除 $name：不是 ZIP（前 4 字节不符）")
                            }
                        }
                    } else {
                        Log.d(TAG, "测速排除 $name：HTTP $code")
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "测速失败 $name: ${e.message}")
                } finally {
                    try {
                        conn?.disconnect()
                    } catch (_: Exception) {
                    }
                }
            }
        }
        pool.shutdown()
        try {
            pool.awaitTermination(15, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
        val sorted = results.sortedBy { it.first }
        return sorted.map { it.second to it.third }
    }

    /**
     * 下载到指定文件（支持续传：文件已存在则接着下）。
     *
     * 分多轮进行：每轮最多试 MIRRORS_PER_ROUND 个镜像，失败后进入下一轮，
     * 但**已下载的字节会保留**，所以总耗时远小于重下。
     *
     * @param dest 目标文件
     * @param expectedSize 期望大小（<=0 表示未知，只做能下完的判断）
     * @param preferredMirrors 优先尝试的镜像（来自 probeMirrors），可为空
     * @param onCancel 返回 true 时中止下载
     * @return 成功返回 true
     */
    fun download(
        url: String,
        dest: File,
        expectedSize: Long,
        listener: Listener? = null,
        preferredMirrors: List<Pair<String, String>>? = null,
        onCancel: () -> Boolean = { false }
    ): Boolean {
        dest.parentFile?.mkdirs()

        // 组装尝试顺序：测速结果在前，其余补齐
        val order = ArrayList<Pair<String, String>>()
        preferredMirrors?.let { order.addAll(it) }
        for (m in MIRRORS) {
            if (order.none { it.first == m.first }) order.add(m)
        }

        var cursor = 0
        for (round in 1..MAX_ROUNDS) {
            if (onCancel()) return false
            if (expectedSize > 0 && dest.exists() && dest.length() == expectedSize) return true

            var tried = 0
            while (tried < MIRRORS_PER_ROUND && cursor < order.size) {
                if (onCancel()) return false
                val m = order[cursor]
                cursor++
                tried++
                val fullUrl = if (m.first.isEmpty()) url else m.first + url
                listener?.onMirror(cursor, order.size, m.second)
                Log.i(TAG, "第 $round 轮下载尝试 [${m.second}]: ${maskUrl(fullUrl)}")
                val ok = tryDownloadOnce(fullUrl, dest, expectedSize, listener, onCancel)
                if (ok) {
                    Log.i(TAG, "下载成功（${m.second}，共 $round 轮）")
                    return true
                }
                if (onCancel()) return false
                // 用户指定了要换到某个通道：直接跳到它在 order 里的位置
                val target = takeSwitchTarget()
                if (target != null) {
                    val idx = order.indexOfFirst { it.first == target.first }
                    if (idx >= 0) {
                        cursor = idx
                        tried = 0      // 重置本轮配额，让指定通道有机会被尝试
                        Log.i(TAG, "按用户指定切换到：${target.second}")
                    }
                }
            }
            // 本轮镜像用完，回到队首继续下一轮（这时已下的字节还在）
            cursor = 0
            Log.w(TAG, "第 $round 轮全部失败，准备重试（已下 ${dest.length()} 字节）")
        }
        return false
    }

    private fun tryDownloadOnce(
        url: String,
        dest: File,
        expectedSize: Long,
        listener: Listener?,
        onCancel: () -> Boolean
    ): Boolean {
        var raf: RandomAccessFile? = null
        var conn: HttpURLConnection? = null
        try {
            // 新通道开始前，丢弃上一个通道遗留的换源标记，避免刚连上就被误杀
            consumeSwitchRequest()
            var startPos = if (dest.exists()) dest.length() else 0L
            // 已经下完了（大小对得上）直接成功
            if (expectedSize > 0 && startPos == expectedSize) return true
            // 文件比预期还大 → 数据坏了，删了重下
            if (expectedSize > 0 && startPos > expectedSize) {
                dest.delete()
                startPos = 0
            }
            // 已有的部分必须开头就是 ZIP：否则它是一段错误页 / 半截垃圾，
            // 在上面续传只会把错误内容永久粘住，越下越错
            if (startPos > 0 && !isZipFile(dest)) {
                Log.w(TAG, "已有缓存不是 ZIP（${startPos} 字节），丢弃重下")
                dest.delete()
                startPos = 0
            }

            val u = URL(url)
            conn = u.openConnection() as HttpURLConnection
            // 登记当前连接：手动换源时可以直接断开它
            activeConn = conn
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", "StrongholdShell-Launcher")
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (startPos > 0) {
                conn.setRequestProperty("Range", "bytes=$startPos-")
            }

            val code = conn.responseCode
            Log.i(TAG, "HTTP $code (startPos=$startPos)")

            if (code !in 200..299) {
                conn.disconnect()
                return false
            }

            // 服务器不支持 Range：返回 200 而不是 206 → 只能从头下
            val actualStart = if (code == 206) startPos else 0L
            if (code == 200 && startPos > 0) {
                Log.w(TAG, "服务器不支持断点续传，从头下载")
            }

            // 总大小：206 时是 Content-Range 剩余 + 已下载
            var total = -1L
            val cr = conn.getHeaderField("Content-Range")
            val cl = conn.contentLengthLong
            total = when {
                cr != null && cr.contains("/") -> {
                    cr.substringAfterLast("/").trim().toLongOrNull() ?: (cl + actualStart)
                }
                cl > 0 -> cl + actualStart
                expectedSize > 0 -> expectedSize
                else -> -1
            }

            raf = RandomAccessFile(dest, "rw")
            raf.seek(actualStart)

            val buf = ByteArray(64 * 1024)
            var downloaded = actualStart
            var lastNotify = 0L
            // 低速检测：连续一段时间速度低于阈值就放弃这个通道，交给上层换源。
            // 只看「最近窗口」的平均速度，避免刚开始的抖动误判。
            val speedWindowMs = 8000L        // 观察窗口 8 秒
            val slowThreshold = 180L * 1024  // 低于 180 KB/s 视为过慢
            var winStart = System.currentTimeMillis()
            var winBytes = 0L
            var slowStrikes = 0               // 连续几次窗口都慢才放弃（防抖）
            // 已经下了一半以上就不轻易放弃，否则前功尽弃
            conn.inputStream.use { ins ->
                while (true) {
                    if (onCancel()) return false
                    val n = ins.read(buf)
                    if (n <= 0) break
                    // 用户点了「换个源」：立刻放弃当前通道（已下字节会保留）
                    if (consumeSwitchRequest()) {
                        Log.i(TAG, "用户手动换源，放弃当前通道")
                        return false
                    }
                    raf.write(buf, 0, n)
                    downloaded += n
                    winBytes += n
                    val now = System.currentTimeMillis()
                    if (now - lastNotify > 300) {   // 限流到 ~3 次/秒，避免 UI 卡
                        listener?.onProgress(downloaded, total)
                        listener?.onSpeed(winBytes * 1000L / maxOf(1L, now - winStart))
                        lastNotify = now
                    }
                    // 每满一个窗口评估一次速度
                    if (now - winStart >= speedWindowMs) {
                        val elapsed = maxOf(1L, now - winStart)
                        val bps = winBytes * 1000L / elapsed
                        val remain = if (total > 0) total - downloaded else Long.MAX_VALUE
                        // 剩余不足 20MB 时不再换源，避免最后关头白费
                        val nearDone = remain in 0..(20L * 1024 * 1024)
                        if (bps < slowThreshold && !nearDone) {
                            slowStrikes++
                            Log.w(TAG, "通道过慢：${bps / 1024} KB/s（第 $slowStrikes 次）")
                            if (slowStrikes >= 2) {
                                Log.w(TAG, "连续 2 个窗口过慢，放弃该通道，准备换源")
                                return false   // 已下字节保留，上层会换下一个通道续传
                            }
                        } else {
                            slowStrikes = 0
                        }
                        winStart = now
                        winBytes = 0
                    }
                }
            }
            listener?.onProgress(downloaded, total)

            // 校验大小
            if (total > 0 && downloaded != total) {
                Log.w(TAG, "下载不完整：$downloaded / $total")
                return false
            }
            if (expectedSize > 0 && dest.length() != expectedSize) {
                Log.w(TAG, "文件大小不符：${dest.length()} / $expectedSize")
                return false
            }
            // 大小对上了也要确认是真 ZIP：镜像有时会返回「大小凑巧接近」的错误页，
            // 只有魔数校验能挡住
            if (!isZipFile(dest)) {
                Log.w(TAG, "文件不是 ZIP：${maskUrl(url)}")
                dest.delete()   // 坏文件删掉，避免续传时越传越错
                return false
            }
            return true
        } catch (e: Exception) {
            Log.w(TAG, "下载异常: ${e.message}")
            return false
        } finally {
            try {
                raf?.close()
            } catch (_: Exception) {
            }
            // 连接用完就取消登记，避免手动换源误断下一个通道的连接
            if (activeConn === conn) activeConn = null
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /** 检查文件头是不是 ZIP 魔数 PK\x03\x04 */
    fun isZipFile(f: File): Boolean {
        if (!f.isFile || f.length() < 4) return false
        return try {
            java.io.RandomAccessFile(f, "r").use { r ->
                val b = ByteArray(4)
                r.readFully(b)
                b[0] == 0x50.toByte() && b[1] == 0x4B.toByte() &&
                    b[2] == 0x03.toByte() && b[3] == 0x04.toByte()
            }
        } catch (e: Exception) {
            false
        }
    }

    /** 日志里隐藏完整 URL，只留文件名 */
    private fun maskUrl(url: String): String {
        val name = url.substringAfterLast("/")
        return ".../$name"
    }

    /**
     * 下载目录。
     *
     * 支持三种模式（存在 Settings.downloadMode 里）：
     *   0 = 内部私有（默认，最稳，但文件管理器看不见）
     *   1 = 公共 Download/StrongholdPacks（文件管理器可见，方便手动分享）
     *   2 = 自定义路径（用户在设置里填的绝对路径）
     *
     * 若目标目录建不出来（权限不足 / 路径非法），自动回落到内部私有目录，
     * 保证功能不会因为路径选错而彻底不可用。
     */
    fun downloadDir(context: Context): File {
        val p = try {
            Settings.load(context)
        } catch (_: Exception) {
            null
        }
        val mode = p?.downloadMode ?: 0
        val custom = p?.downloadPath ?: ""
        val candidate: File? = when (mode) {
            1 -> File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), "StrongholdPacks")
            2 -> if (custom.isNotBlank()) File(custom) else null
            else -> null
        }
        if (candidate != null) {
            try {
                if (!candidate.exists()) candidate.mkdirs()
                if (candidate.isDirectory && candidate.canWrite()) return candidate
                Log.w(TAG, "自定义下载目录不可写，回落内部目录: " + candidate.absolutePath)
            } catch (e: Exception) {
                Log.w(TAG, "自定义下载目录创建失败: " + e.message)
            }
        }
        val d = File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads")
        if (!d.exists()) d.mkdirs()
        return d
    }

    /** 当前实际生效的下载目录说明（给设置页显示用） */
    fun downloadDirLabel(context: Context): String {
        val d = downloadDir(context)
        val p = try {
            Settings.load(context)
        } catch (_: Exception) {
            null
        }
        val mode = p?.downloadMode ?: 0
        // 用实际生效的目录反推名字：模式选了公共/自定义但权限不足时会回落，
        // 这时要如实显示回落后的位置，不能只看设置值
        val actual = when {
            d.absolutePath.contains("/Download/StrongholdPacks") -> "公共 Download/StrongholdPacks"
            mode == 2 && d.absolutePath == (p?.downloadPath ?: "") -> "自定义路径"
            else -> "应用私有目录"
        }
        val fallback = if (mode == 1 && !actual.startsWith("公共")) "（权限不足，已回落）"
        else if (mode == 2 && actual != "自定义路径") "（路径不可用，已回落）"
        else ""
        return actual + fallback + "\n" + d.absolutePath
    }

    /**
     * 解压临时目录。
     *
     * 注意：不要放在下载目录里！下载目录可能被用户改成公共 /sdcard/Download，
     * 在 FUSE 挂载点上解压上万个小文件会慢 10 倍以上，而且跨挂载点 renameTo 会失败。
     * 所以这里固定用应用内部私有目录。
     */
    fun stagingDir(context: Context): File {
        val d = File(context.filesDir, "staging")
        if (!d.exists()) d.mkdirs()
        return d
    }

    /** 本地已有包的检查结果 */
    data class LocalCheck(
        /** 文件是否存在 */
        val exists: Boolean,
        /** 文件大小（字节） */
        val size: Long,
        /** 是不是合法 ZIP（开头 PK\x03\x04） */
        val isZip: Boolean,
        /** ZIP 结构是否完整（能读出目录表） */
        val zipOk: Boolean,
        /** ZIP 里的文件条目数 */
        val entries: Int,
        /** 发现的问题说明 */
        val problem: String,
        /** 文件绝对路径 */
        val path: String
    )

    /**
     * 检查已下载的包：文件在不在、大小对不对、是不是真 ZIP、结构有没有坏。
     * 这是“下载下来了但装不上”这种情形最需要的一步。
     */
    fun checkLocal(file: File, expectedSize: Long = 0L): LocalCheck {
        if (!file.exists()) {
            return LocalCheck(false, 0, false, false, 0, "文件不存在", file.absolutePath)
        }
        val size = file.length()
        if (size == 0L) {
            return LocalCheck(true, 0, false, false, 0, "文件是 0 字节（只创建了空文件）", file.absolutePath)
        }
        if (!isZipFile(file)) {
            val head = readHeadText(file)
            val hint = if (head.contains("<html", true) || head.contains("<!DOCTYPE", true))
                "下载到的是网页（镜像返回的错误页）"
            else "开头不是 ZIP 标志（PK）"
            return LocalCheck(true, size, false, false, 0, hint, file.absolutePath)
        }
        // 大小对不上
        var sizeProblem = ""
        if (expectedSize > 0 && size != expectedSize) {
            val diff = expectedSize - size
            sizeProblem = if (diff > 0)
                "文件不完整，还差 " + fmtMb(diff) + " MB"
            else
                "文件比官方大 " + fmtMb(-diff) + " MB"
        }
        // 读中央目录，验证压缩包结构
        var entries = 0
        var zipOk = true
        var zipProblem = ""
        try {
            java.util.zip.ZipFile(file).use { zf ->
                entries = zf.size()
                if (entries == 0) {
                    zipOk = false
                    zipProblem = "压缩包内没有任何文件"
                }
            }
        } catch (e: Exception) {
            zipOk = false
            zipProblem = "压缩包结构损坏（" + (e.message?.take(40) ?: "无法读取") + "）"
        }
        val problem = when {
            zipProblem.isNotEmpty() -> zipProblem
            sizeProblem.isNotEmpty() -> sizeProblem
            else -> ""
        }
        return LocalCheck(true, size, true, zipOk, entries, problem, file.absolutePath)
    }

    /** 读文件开头一小段当文本，用来判断是不是网页 */
    private fun readHeadText(f: File): String {
        return try {
            val n = minOf(f.length(), 512L).toInt()
            val b = ByteArray(n)
            java.io.RandomAccessFile(f, "r").use { r ->
                r.readFully(b)
            }
            String(b, Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    fun fmtMb(bytes: Long): String = "%.1f".format(bytes / 1048576.0)

    /** 下载目录里所有 zip，按修改时间倒序 */
    fun listLocalZips(context: Context): List<File> {
        val dir = downloadDir(context)
        val arr = dir.listFiles { f -> f.isFile && f.name.endsWith(".zip", true) }
        return (arr ?: emptyArray()).sortedByDescending { it.lastModified() }
    }

    /** 磁盘剩余空间（字节） */
    fun freeSpace(dir: File): Long {
        return try {
            dir.usableSpace
        } catch (e: Exception) {
            -1L
        }
    }

    /** 清掉下载缓存（装完后调用，省空间） */
    fun clearDownloads(context: Context) {
        try {
            downloadDir(context).listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }
}
