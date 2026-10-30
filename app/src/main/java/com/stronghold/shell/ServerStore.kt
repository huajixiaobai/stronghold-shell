package com.stronghold.shell

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 多人联机服务器地址的本地存储。
 *
 * 设计要点：
 *   - 内存缓存为唯一真相源，避免 SharedPreferences 频繁反序列化
 *   - 所有写操作加锁，防止并发覆盖
 *   - 地址规范化在这里统一处理，UI 层只负责展示错误
 */
object ServerStore {

    private const val PREFS = "stronghold_servers"
    private const val KEY = "servers_json"

    /** 内存缓存，null 表示尚未加载 */
    private var cache: MutableList<ServerEntry>? = null
    private val lock = Any()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 读取全部服务器，按最近游玩倒序 */
    fun load(context: Context): List<ServerEntry> = synchronized(lock) {
        val list = cache ?: readFromDisk(context).also { cache = it }
        list.sortedByDescending { it.lastPlayed }
    }

    private fun readFromDisk(context: Context): MutableList<ServerEntry> {
        val raw = prefs(context).getString(KEY, null) ?: return mutableListOf()
        val out = mutableListOf<ServerEntry>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val address = o.optString("address", "").trim()
                if (address.isEmpty()) continue
                out.add(
                    ServerEntry(
                        id = o.optString("id", "").ifEmpty { UUID.randomUUID().toString() },
                        name = o.optString("name", ""),
                        address = address,
                        lastPlayed = o.optLong("lastPlayed", 0L),
                        // 老数据没有 tls 字段：端口是 443 的按加密连接处理
                        tls = o.optBoolean("tls", address.endsWith(":443"))
                    )
                )
            }
        } catch (e: Exception) {
            FileLogger.log("[ServerStore] 解析已保存服务器失败: ${e.message}")
            return mutableListOf()
        }
        return out
    }

    private fun writeToDisk(context: Context, list: List<ServerEntry>) {
        val arr = JSONArray()
        for (s in list) {
            arr.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("address", s.address)
                    put("lastPlayed", s.lastPlayed)
                    put("tls", s.tls)
                }
            )
        }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    /** 新增或更新一条 */
    fun upsert(context: Context, entry: ServerEntry) = synchronized(lock) {
        val list = cache ?: readFromDisk(context).also { cache = it }
        val idx = list.indexOfFirst { it.id == entry.id }
        if (idx >= 0) list[idx] = entry else list.add(entry)
        writeToDisk(context, list)
    }

    /** 删除一条 */
    fun delete(context: Context, id: String) = synchronized(lock) {
        val list = cache ?: readFromDisk(context).also { cache = it }
        list.removeAll { it.id == id }
        writeToDisk(context, list)
        Unit
    }

    /** 记录最近游玩时间 */
    fun touch(context: Context, id: String) = synchronized(lock) {
        val list = cache ?: readFromDisk(context).also { cache = it }
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            list[idx] = list[idx].copy(lastPlayed = System.currentTimeMillis())
            writeToDisk(context, list)
        }
    }

    /** 按 id 取一条 */
    fun find(context: Context, id: String): ServerEntry? = synchronized(lock) {
        val list = cache ?: readFromDisk(context).also { cache = it }
        list.firstOrNull { it.id == id }
    }

    // ==================== 地址规范化 ====================

    /** 规范化结果：成功时 address 非空，失败时 error 非空；tls 表示该服务器走 wss/https */
    data class Result(val address: String?, val error: String?, val tls: Boolean = false)

    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    /**
     * IPv6 字面量（不含方括号）。覆盖完整写法、各种 `::` 压缩写法。
     * 不支持内嵌 IPv4 尾巴（如 `::ffff:192.168.1.1`），实际用不到。
     */
    private val IPV6 = Regex(
        "^(" +
            "([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}|" +
            "([0-9a-fA-F]{1,4}:){1,7}:|" +
            "([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}|" +
            "([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}|" +
            "([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}|" +
            "([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}|" +
            "([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}|" +
            "[0-9a-fA-F]{1,4}:((:[0-9a-fA-F]{1,4}){1,6})|" +
            ":((:[0-9a-fA-F]{1,4}){1,7}|:)" +
            ")$"
    )

    private val DOMAIN = Regex(
        "^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?" +
            "(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$"
    )
    private val SCHEME = Regex("^(https?|wss?)://", RegexOption.IGNORE_CASE)

    /**
     * 拼出最终的地址字符串。
     *
     * 端口等于该协议的默认端口时不写出来（`ws` 默认 80、`wss` 默认 443），
     * 让 URL 自己补 —— 界面上也更干净：`www.example.com` 而不是 `www.example.com:80`。
     */
    private fun fmt(hostForUrl: String, portNum: Int, tls: Boolean): String {
        val defaultPort = if (tls) 443 else 80
        return if (portNum == defaultPort) hostForUrl else "$hostForUrl:$portNum"
    }

    /**
     * 把用户输入变成 host:port，并判断是否需要加密（wss/https）。
     *
     *   "192.168.1.10:3000"          → 192.168.1.10:3000        （局域网，ws）
     *   "192.168.1.5"                → 192.168.1.5:3000         （IP 缺省 3000）
     *   "example.com"                → example.com:80           （域名缺省 80，走 ws）
     *   "https://example.com"        → example.com:443          （tls=true，走 wss）
     *   "www.example.com:3000"       → www.example.com:3000
     *   "http://[240c::1]:3000/"     → [240c::1]:3000
     *
     * 为什么域名默认 80 而不是 3000：挂在 Cloudflare / Nginx 后面的域名只开放
     * 80/443，3000 这类端口根本不通（Cloudflare 只代理标准端口）。局域网直连
     * 的场景是 IP，仍然默认 3000，行为不变。
     */
    fun normalizeAddress(input: String): Result {
        var s = input.trim()
        if (s.isEmpty()) return Result(null, "地址不能为空")

        // 先看协议：决定加密方式与缺省端口，再把协议头剥掉
        var tls = false
        var schemePort: String? = null
        SCHEME.find(s)?.let { m ->
            val scheme = m.value.substringBefore("://").lowercase()
            tls = scheme == "https" || scheme == "wss"
            schemePort = if (tls) "443" else "80"
            s = s.removeRange(m.range)
        }

        // 去掉路径 / 查询 / 片段
        val cut = s.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (cut >= 0) s = s.substring(0, cut)
        s = s.trim()
        if (s.isEmpty()) return Result(null, "地址无效")

        // ---- IPv6 字面量：[240c:cb02::1]:3000 / [240c:cb02::1] ----
        // 必须放在通用 host:port 拆分之前：IPv6 冒号太多，
        // lastIndexOf(':') 会把地址的最后一段当成端口切错。
        if (s.startsWith("[")) {
            val close = s.indexOf(']')
            if (close < 0) return Result(null, "IPv6 地址缺少右括号 ]")
            val inner = s.substring(1, close)
            if (!IPV6.matches(inner)) return Result(null, "IPv6 地址格式不正确")
            val rest = s.substring(close + 1)
            val fallback = schemePort ?: "3000"
            val port = when {
                rest.isEmpty() -> fallback
                rest.startsWith(":") -> rest.substring(1).ifEmpty { fallback }
                else -> return Result(null, "IPv6 地址后面只能跟 :端口")
            }
            val portNum = port.toIntOrNull() ?: return Result(null, "端口号必须是数字")
            if (portNum !in 1..65535) return Result(null, "端口号超出范围（1-65535）")
            // URL 里 IPv6 必须保留方括号，否则会被解析错
            return Result(fmt("[$inner]", portNum, tls), null, tls)
        }
        // 没加方括号、但整串本身就是一个合法 IPv6（未指定端口）→ 用默认端口
        // 注意：这种写法无法再附加端口（"240c:cb02::1:3000" 里的 3000 是合法的 hex 段），
        // 要指定端口请写成 [240c:cb02::1]:3000
        if (s.count { it == ':' } >= 2 && IPV6.matches(s)) {
            val p = (schemePort ?: "3000").toIntOrNull() ?: return Result(null, "端口号必须是数字")
            return Result(fmt("[$s]", p, tls), null, tls)
        }

        // 用户可能粘了 "host:port" 也可能只给了 host
        var host = s
        var explicitPort: String? = null
        val colon = s.lastIndexOf(':')
        if (colon >= 0) {
            host = s.substring(0, colon).trim()
            explicitPort = s.substring(colon + 1).trim().ifEmpty { null }
        }

        if (host.isEmpty()) return Result(null, "主机名不能为空")

        val isLocalhost = host.equals("localhost", true)
        val isIpv4 = IPV4.matches(host)
        if (!isLocalhost && !isIpv4 && !DOMAIN.matches(host)) {
            return Result(null, "主机名格式不正确")
        }
        if (isIpv4 && host.split(".").any { (it.toIntOrNull() ?: 999) > 255 }) {
            return Result(null, "IP 地址超出范围")
        }

        // 缺省端口优先级：显式写的端口 > 协议缺省 > IP/localhost 用 3000 > 域名用 80
        val port = explicitPort
            ?: schemePort
            ?: if (isLocalhost || isIpv4) "3000" else "80"
        val portNum = port.toIntOrNull() ?: return Result(null, "端口号必须是数字")
        if (portNum !in 1..65535) return Result(null, "端口号超出范围（1-65535）")

        // 没写协议但端口是 443 → 按 https/wss 处理
        if (!tls && explicitPort != null && portNum == 443) tls = true

        return Result(fmt(host, portNum, tls), null, tls)
    }
}

/** 一条服务器记录 */
data class ServerEntry(
    val id: String,
    val name: String,
    val address: String,
    val lastPlayed: Long,
    /** true = 该服务器走加密连接（wss / https），例如挂在 Cloudflare 后面的域名 */
    val tls: Boolean = false
) {
    /** 界面上显示的标题：没起名字就显示地址 */
    val displayName: String get() = if (name.isBlank()) address else name
}
