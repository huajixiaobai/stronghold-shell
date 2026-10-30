package com.stronghold.shell

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.concurrent.Executors

/**
 * 主界面 —— 启动器首页（PCL2 骨架 × 明日方舟美术）。
 *
 * 页面：主页 / 插件 / 设置（底部导航切换）
 * 设计依据：docs/UI-REDESIGN-PLAN.md
 */
class ImportActivity : AppCompatActivity() {

    companion object {
        private const val NAV_HOME = 0
        private const val NAV_PLUGIN = 1
        private const val NAV_SETTINGS = 2
    }

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private var navIndex = NAV_HOME

    private lateinit var contentArea: LinearLayout
    private lateinit var navButtons: List<TextView>

    private var updateInfo: UpdateChecker.ReleaseInfo? = null
    private var checkingUpdate = false
    private var downloading = false
    private var downloadLabel: TextView? = null
    private var downloadBar: View? = null
    /** 下载卡片里的「换个源」按钮 */
    private var downloadSwitchBtn: View? = null
    /** 当前已下载字节数（换源时写进日志，也用于估算剩余时间） */
    private var curDownloadedBytes: Long = 0L
    /** 本地游戏包列表弹框（点选某一行后需要关掉它） */
    private var currentLocalDialog: AlertDialog? = null
    /** 下载通道选择弹框（点选后需要关掉它） */
    private var currentChannelDialog: AlertDialog? = null
    /** 最近一次测速排好序的镜像（下载时优先用） */
    private var preferredMirrors: List<Pair<String, String>> = emptyList()
    /** 本次准备下载哪种包（增量 / 完整 / 精简） */
    private var preferredKind: UpdateChecker.PackKind = UpdateChecker.PackKind.FULL
    private var downloadCard: LinearLayout? = null

    private val pickPackZip = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r -> if (r.resultCode == Activity.RESULT_OK) r.data?.data?.let { doInstallPack(it) } }

    private val pickPluginZipResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r -> if (r.resultCode == Activity.RESULT_OK) r.data?.data?.let { doInstallPlugin(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileLogger.init(this)
        CrashGuard.install(this)
        buildShell()

        // 首次进入总是停在启动器界面（用户要看新 UI / 版本列表）。
        // 只有用户主动点「启动游戏」才跳转 —— 之前的自动跳转会让启动器一闪而过。
        if (savedInstanceState == null && Settings.load(this).autoCheckPack) {
            checkUpdate(silent = true)
        }
    }

    // ==================== 骨架 ====================

    private fun buildShell() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiKit.BG_PAGE)
        }
        root.addView(buildTopBar())

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true
            setBackgroundColor(UiKit.BG_PAGE)
        }
        contentArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = UiKit.dp(this@ImportActivity, 16f)
            setPadding(p, p, p, p)
        }
        scroll.addView(contentArea, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(scroll)
        root.addView(buildBottomNav())
        setContentView(root)
        switchNav(NAV_HOME)
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(UiKit.BG_CARD)
            val ph = UiKit.dp(this@ImportActivity, 16f)
            val pv = UiKit.dp(this@ImportActivity, 12f)
            setPadding(ph, pv, ph, pv)
        }
        bar.addView(View(this).apply {
            setBackgroundColor(UiKit.ACCENT)
            layoutParams = LinearLayout.LayoutParams(
                UiKit.dp(this@ImportActivity, 4f), UiKit.dp(this@ImportActivity, 26f)
            ).apply { rightMargin = UiKit.dp(this@ImportActivity, 10f) }
        })
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        box.addView(UiKit.label(this, "卫戍协议：盟约", 17f, UiKit.TEXT_MAIN, true))
        box.addView(UiKit.label(this, "STRONGHOLD PROTOCOL · 启动器", 9f, UiKit.TEXT_DIM))
        bar.addView(box)
        bar.addView(UiKit.chip(this, "v" + BuildConfig.VERSION_NAME, UiKit.ACCENT_DIM))
        return bar
    }

    private fun buildBottomNav(): View {
        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrapper.addView(View(this).apply {
            setBackgroundColor(UiKit.LINE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        })
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(UiKit.BG_CARD)
            val pv = UiKit.dp(this@ImportActivity, 6f)
            setPadding(0, pv, 0, pv)
        }
        navButtons = listOf("主页" to NAV_HOME, "插件" to NAV_PLUGIN, "设置" to NAV_SETTINGS).map { (n, i) ->
            val tv = TextView(this).apply {
                text = n
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                val pv = UiKit.dp(this@ImportActivity, 10f)
                setPadding(0, pv, 0, pv)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { switchNav(i) }
            }
            nav.addView(tv)
            tv
        }
        wrapper.addView(nav)
        return wrapper
    }

    private fun switchNav(idx: Int) {
        navIndex = idx
        navButtons.forEachIndexed { i, tv ->
            val on = i == idx
            tv.setTextColor(if (on) UiKit.ACCENT else UiKit.TEXT_DIM)
            tv.typeface = if (on) android.graphics.Typeface.DEFAULT_BOLD
            else android.graphics.Typeface.DEFAULT
        }
        contentArea.removeAllViews()
        when (idx) {
            NAV_HOME -> buildHome()
            NAV_PLUGIN -> buildPluginPage()
            NAV_SETTINGS -> buildSettingsPage()
        }
    }

    private fun cardLp(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = UiKit.dp(this@ImportActivity, 10f) }

    // ==================== 主页 ====================

    private fun buildHome() {
        val c = contentArea
        c.addView(UiKit.sectionHeader(this, "游戏版本", "01"))
        c.addView(UiKit.spacer(this, 10f))

        val packs = PackManager.listPacks(this)
        val activeId = PackManager.activeId(this)

        if (packs.isEmpty()) {
            val card = UiKit.card(this)
            card.addView(UiKit.label(this, "尚未安装游戏包", 15f, UiKit.TEXT_MAIN, true))
            card.addView(UiKit.spacer(this, 6f))
            card.addView(UiKit.label(
                this,
                "可从 GitHub 在线下载最新完整包，或导入本地 zip。\n完整包约 800MB，自带全部素材。",
                12f, UiKit.TEXT_SUB
            ))
            c.addView(card, cardLp())
        } else {
            for (p in packs) {
                c.addView(buildPackCard(p, p.id == activeId), cardLp())
            }
        }

        c.addView(UiKit.sectionHeader(this, "操作", "02"))
        c.addView(UiKit.spacer(this, 10f))

        if (activeId != null) {
            val lb = UiKit.button(this, "▶  启动游戏", true) { startGame() }
            lb.layoutParams = cardLp()
            c.addView(lb)
        }

        val db = UiKit.button(this, "⬇  下载 / 更新", false) { onDownloadLatest() }
        db.layoutParams = cardLp()
        c.addView(db)

        val ib = UiKit.button(this, "📁  从本地导入 zip", false) { pickLocalZip() }
        ib.layoutParams = cardLp()
        c.addView(ib)

        // 多人游戏入口：只在 lab 版显示（主版本不暴露未完成功能）
        if (BuildConfig.ENABLE_MULTIPLAYER) {
            c.addView(UiKit.spacer(this, 6f))
            val mb = UiKit.button(this, "🌐  多人游戏（实验）", false) { openMultiplayer() }
            mb.layoutParams = cardLp()
            c.addView(mb)
        }

        val dlCard = UiKit.card(this)
        dlCard.visibility = View.GONE
        val lbl = UiKit.label(this, "", 12f, UiKit.TEXT_SUB)
        val bar = UiKit.progressBar(this, 6f)
        dlCard.addView(lbl)
        dlCard.addView(UiKit.spacer(this, 8f))
        dlCard.addView(bar)
        // 下载中随时可手动换源：卡住或太慢时不用等，也不用取消重来
        val swBtn = UiKit.button(this, "🔄  太慢了？换个源", false) {
            showSwitchMirrorDialog()
        }
        dlCard.addView(UiKit.spacer(this, 8f))
        dlCard.addView(swBtn)
        c.addView(dlCard, cardLp())
        downloadLabel = lbl
        downloadCard = dlCard
        downloadBar = bar
        downloadSwitchBtn = swBtn

        val info = updateInfo
        val current = packs.firstOrNull { it.id == activeId }
        if (info != null && current != null && UpdateChecker.isNewer(info.version, current.version)) {
            c.addView(UiKit.divider(this, "UPDATE AVAILABLE"))
            val up = UiKit.card(this, highlight = true)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(UiKit.dot(this, UiKit.WARN))
            row.addView(UiKit.label(this, "发现新版本 v${info.version}", 14f, UiKit.WARN, true))
            up.addView(row)
            up.addView(UiKit.spacer(this, 6f))
            up.addView(UiKit.label(
                this, "当前 v${current.version}，最新 v${info.version}。点上方「下载 / 更新」，可选增量更新或完整包。",
                12f, UiKit.TEXT_SUB
            ))
            c.addView(up, cardLp())
        }

        c.addView(UiKit.divider(this, "SYSTEM LOG"))
        c.addView(UiKit.label(
            this,
            "共 ${packs.size} 个版本 · 可用空间 ${filesDir.usableSpace / 1048576} MB",
            11f, UiKit.TEXT_DIM
        ))
        c.addView(UiKit.spacer(this, 20f))
    }

    private fun buildPackCard(p: PackManager.PackEntry, active: Boolean): View {
        val card = UiKit.card(this, active)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(UiKit.dot(this, if (active) UiKit.ACCENT else UiKit.TEXT_DIM))
        row.addView(UiKit.label(this, "v${p.version}", 16f, UiKit.TEXT_MAIN, true))
        row.addView(UiKit.hSpacer(this, 8f))
        row.addView(UiKit.chip(this, if (p.kind == "FORGE") "FORGE" else "原版",
            if (p.kind == "FORGE") UiKit.WARN else UiKit.ACCENT_DIM))
        if (active) {
            row.addView(UiKit.hSpacer(this, 6f))
            row.addView(UiKit.chip(this, "使用中", UiKit.ACCENT))
        }
        card.addView(row)
        card.addView(UiKit.spacer(this, 6f))
        card.addView(UiKit.label(this, "${p.sizeMb} MB · ${p.id}", 11f, UiKit.TEXT_DIM))
        card.addView(UiKit.spacer(this, 12f))

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (!active) {
            val use = UiKit.button(this, "切换到此版本", true) {
                if (PackManager.setActive(this, p.id)) {
                    toast("已切换到 v${p.version}，重启游戏生效")
                    switchNav(NAV_HOME)
                } else toast("切换失败（包不完整）")
            }
            use.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            btnRow.addView(use)
            btnRow.addView(UiKit.hSpacer(this, 8f))
        }
        val del = UiKit.button(this, "删除", false) { confirmDeletePack(p) }
        del.layoutParams = if (active)
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.45f)
        btnRow.addView(del)
        card.addView(btnRow)
        return card
    }

    private fun confirmDeletePack(p: PackManager.PackEntry) {
        AlertDialog.Builder(this)
            .setTitle("删除 v${p.version}？")
            .setMessage("将删除该版本全部文件（约 ${p.sizeMb} MB）。不可撤销。")
            .setPositiveButton("删除") { _, _ ->
                io.execute {
                    val ok = PackManager.removePack(this@ImportActivity, p.id)
                    ui.post {
                        toast(if (ok) "已删除 v${p.version}" else "删除失败")
                        switchNav(NAV_HOME)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    /**
     * 主线程安全的 toast。
     *
     * 【为什么必须兜这一层】io 是 Executors.newSingleThreadExecutor()，它创建的工作线程
     * **没有 Looper**。在 Android 10 及更早版本上，从这种线程直接 Toast.show() 会抛
     *   RuntimeException: Can't toast on a thread that has not called Looper.prepare()
     * 而这个异常通常发生在 io.execute { } 的 lambda 里、不在任何 try 之内 ——
     * 工作线程被终止，默认异常处理器接管，进程直接死掉 = 闪退。
     *
     * 所以统一在这里判一次线程：主线程直接弹，其它线程 post 回主线程再弹。
     * 调用方就不用再各自记得包 ui.post 了。
     */
    private fun toast(msg: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } else {
            ui.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
        }
    }

    // ==================== 插件页 ====================

    private fun buildPluginPage() {
        val c = contentArea
        val gameRoot = PackManager.activeDir(this)

        c.addView(UiKit.sectionHeader(this, "插件管理", "01"))
        c.addView(UiKit.spacer(this, 10f))

        if (gameRoot == null) {
            val card = UiKit.card(this)
            card.addView(UiKit.label(this, "尚未安装游戏版本", 15f, UiKit.TEXT_MAIN, true))
            card.addView(UiKit.spacer(this, 6f))
            card.addView(UiKit.label(
                this, "请先在「主页」安装游戏包，安装后才能管理插件。", 12f, UiKit.TEXT_SUB
            ))
            c.addView(card, cardLp())
            return
        }

        val kind = PackManager.detectKindAt(gameRoot)
        if (kind == "FORGE") {
            c.addView(UiKit.divider(this, "FORGE MODE"))
            val card = UiKit.card(this, highlight = true)
            card.addView(UiKit.label(this, "当前为 Forge 版本", 14f, UiKit.WARN, true))
            card.addView(UiKit.spacer(this, 6f))
            card.addView(UiKit.label(
                this,
                "Forge 使用 workshop/ 目录装 mod，与 0.3.0+ 官方插件体系不兼容。\n" +
                "如需使用官方插件，请切换到原版游戏包。",
                12f, UiKit.TEXT_SUB
            ))
            c.addView(card, cardLp())
            return
        }

        // ---- 已装插件列表 ----
        val plugins = PluginManager.list(gameRoot)
        val curVer = PackManager.readVersionFrom(gameRoot) ?: "?"
        c.addView(UiKit.label(
            this,
            "当前版本 v$curVer · 已装 ${plugins.size} 个",
            11f, UiKit.TEXT_DIM
        ))
        c.addView(UiKit.spacer(this, 10f))

        if (plugins.isEmpty()) {
            val card = UiKit.card(this)
            card.addView(UiKit.label(this, "还没有安装任何插件", 14f, UiKit.TEXT_MAIN, true))
            card.addView(UiKit.spacer(this, 6f))
            card.addView(UiKit.label(
                this,
                "插件是 .zip 文件，内含 plugin.json 清单。\n可从社区工坊下载后在这里导入。",
                12f, UiKit.TEXT_SUB
            ))
            c.addView(card, cardLp())
        } else {
            for (p in plugins) {
                c.addView(buildPluginCard(gameRoot, p), cardLp())
            }
        }

        c.addView(UiKit.sectionHeader(this, "操作", "02"))
        c.addView(UiKit.spacer(this, 10f))

        val add = UiKit.button(this, "＋  从 zip 安装插件", true) { pickPluginZip() }
        add.layoutParams = cardLp()
        c.addView(add)

        val openDir = UiKit.button(this, "📂  查看插件目录说明", false) {
            AlertDialog.Builder(this)
                .setTitle("插件目录")
                .setMessage(
                    "${PluginManager.pluginsDir(gameRoot)?.absolutePath}\n\n" +
                    "每个插件是一个子文件夹，内含 plugin.json。\n" +
                    "以 _ 开头的文件夹不会被加载（官方模板即 _template）。"
                )
                .setPositiveButton("知道了", null)
                .show()
        }
        openDir.layoutParams = cardLp()
        c.addView(openDir)

        // 脚本开关状态提示
        val prefs = Settings.load(this)
        val scriptsOn = prefs.pluginScripts == "sandbox"
        c.addView(UiKit.divider(this, "SCRIPT POLICY"))
        val pol = UiKit.card(this, scriptsOn)
        val prow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        prow.addView(UiKit.dot(this, if (scriptsOn) UiKit.WARN else UiKit.ACCENT))
        prow.addView(UiKit.label(
            this,
            if (scriptsOn) "脚本插件已启用（沙箱）" else "脚本插件已禁用",
            13f, if (scriptsOn) UiKit.WARN else UiKit.TEXT_MAIN, true
        ))
        pol.addView(prow)
        pol.addView(UiKit.spacer(this, 6f))
        pol.addView(UiKit.label(
            this,
            "script / trusted 类插件会执行代码。上游默认关闭，" +
            "可在「设置 → 插件脚本」中开启沙箱模式。",
            11f, UiKit.TEXT_SUB
        ))
        c.addView(pol, cardLp())
        c.addView(UiKit.spacer(this, 20f))
    }

    private fun buildPluginCard(gameRoot: File, p: PluginManager.PluginInfo): View {
        val card = UiKit.card(this, highlight = !p.valid)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(UiKit.dot(this, if (p.valid) UiKit.ACCENT else UiKit.DANGER))
        row.addView(UiKit.label(this, p.name.ifEmpty { p.id }, 15f, UiKit.TEXT_MAIN, true))
        row.addView(UiKit.hSpacer(this, 8f))
        row.addView(UiKit.chip(this, "v${p.version}", UiKit.ACCENT_DIM))
        card.addView(row)

        card.addView(UiKit.spacer(this, 6f))
        card.addView(UiKit.label(this, p.classLabel, 11f, UiKit.TEXT_SUB))
        card.addView(UiKit.label(this, "id: ${p.id}  ·  api${p.api}", 10f, UiKit.TEXT_DIM))
        if (p.app.isNotEmpty()) {
            card.addView(UiKit.label(this, "兼容: ${p.app}", 10f, UiKit.TEXT_DIM))
        }
        if (!p.valid && p.error != null) {
            card.addView(UiKit.spacer(this, 6f))
            card.addView(UiKit.label(this, "⚠ ${p.error}", 11f, UiKit.DANGER))
        }

        card.addView(UiKit.spacer(this, 10f))
        val dispName = p.name.ifEmpty { p.id }
        val del = UiKit.button(this, "卸载", false) {
            AlertDialog.Builder(this)
                .setTitle("卸载 $dispName？")
                .setMessage("将删除插件目录及其配置，不可撤销。")
                .setPositiveButton("卸载") { _, _ ->
                    io.execute {
                        val ok = PluginManager.uninstall(gameRoot, p.id)
                        ui.post {
                            toast(if (ok) "已卸载 ${p.id}" else "卸载失败")
                            if (ok) switchNav(NAV_PLUGIN)
                        }
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
        del.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        card.addView(del)
        return card
    }

    // ==================== 设置页 ====================

    private fun buildSettingsPage() {
        val c = contentArea
        val cur = Settings.load(this)

        c.addView(UiKit.sectionHeader(this, "服务", "01"))
        c.addView(UiKit.spacer(this, 10f))

        c.addView(settingRow(
            "端口", cur.port.toString(),
            "游戏服务器监听端口，默认 3000"
        ) { editNumber("端口", cur.port, 1024, 65535) { v ->
            val p = Settings.load(this); p.port = v; Settings.save(this, p)
            toast("端口改为 " + v + "（重启游戏生效）"); switchNav(NAV_SETTINGS)
        } }, cardLp())

        c.addView(settingRow(
            "监听地址", cur.host,
            ":: 为全部网卡（支持局域网联机）"
        ) { chooseString("监听地址", cur.host, listOf("::", "0.0.0.0", "127.0.0.1")) { v ->
            val p = Settings.load(this); p.host = v; Settings.save(this, p)
            toast("监听地址改为 " + v); switchNav(NAV_SETTINGS)
        } }, cardLp())

        c.addView(UiKit.sectionHeader(this, "玩法", "02"))
        c.addView(UiKit.spacer(this, 10f))

        c.addView(settingRow(
            "战斗结算", if (cur.combatMode == "server") "服务端判定" else "客户端判定",
            "服务端判定更严格，用于联机防作弊"
        ) { chooseString("战斗结算", cur.combatMode, listOf("client", "server"),
            listOf("客户端判定", "服务端判定")) { v ->
            val p = Settings.load(this); p.combatMode = v; Settings.save(this, p)
            switchNav(NAV_SETTINGS)
        } }, cardLp())

        c.addView(settingRow(
            "存档校验", if (cur.verifyMode == "off") "关闭"
                       else if (cur.verifyMode == "all") "全量" else "抽样",
            "SP_VERIFY，校验存档合法性"
        ) { chooseString("存档校验", cur.verifyMode, listOf("off", "sample", "all"),
            listOf("关闭", "抽样", "全量")) { v ->
            val p = Settings.load(this); p.verifyMode = v; Settings.save(this, p)
            switchNav(NAV_SETTINGS)
        } }, cardLp())

        c.addView(settingRow(
            "插件脚本", if (cur.pluginScripts == "sandbox") "沙箱启用" else "禁用",
            "script / trusted 类插件是否运行代码"
        ) { chooseString("插件脚本", cur.pluginScripts, listOf("off", "sandbox"),
            listOf("禁用", "沙箱启用")) { v ->
            val p = Settings.load(this); p.pluginScripts = v; Settings.save(this, p)
            toast(if (v == "sandbox") "已启用沙箱脚本" else "已禁用脚本插件")
            switchNav(NAV_SETTINGS)
        } }, cardLp())

        c.addView(UiKit.sectionHeader(this, "其他", "03"))
        c.addView(UiKit.spacer(this, 10f))

        c.addView(settingRow(
            "启动时检查游戏包更新", if (cur.autoCheckPack) "开" else "关",
            "打开启动器时查询上游 GitHub 最新游戏包版本"
        ) { chooseString("游戏包更新", if (cur.autoCheckPack) "on" else "off",
            listOf("on", "off"), listOf("开", "关")) { v ->
            val p = Settings.load(this); p.autoCheckPack = (v == "on"); Settings.save(this, p)
            switchNav(NAV_SETTINGS)
        } }, cardLp())

        c.addView(settingRow(
            "启动时检查启动器更新", if (cur.autoCheckApp) "开" else "关",
            "打开启动器时查询本项目最新版本（含一键下载安装）"
        ) { chooseString("启动器更新", if (cur.autoCheckApp) "on" else "off",
            listOf("on", "off"), listOf("开", "关")) { v ->
            val p = Settings.load(this); p.autoCheckApp = (v == "on"); Settings.save(this, p)
            switchNav(NAV_SETTINGS)
        } }, cardLp())

        c.addView(UiKit.divider(this, "下载"))
        c.addView(settingRow(
            "下载位置",
            when (cur.downloadMode) {
                0 -> "应用私有"
                2 -> "自定义"
                else -> "公共 Download"
            },
            PackDownloader.downloadDirLabel(this)
        ) { chooseDownloadPath() }, cardLp())

        c.addView(settingRow(
            "安装后保留压缩包", if (cur.keepZipAfterInstall) "开" else "关",
            "保留后可分享给朋友、重装免重下；关掉可省 821MB"
        ) { chooseString("保留压缩包", if (cur.keepZipAfterInstall) "on" else "off",
            listOf("on", "off"), listOf("开", "关")) { v ->
            val p = Settings.load(this); p.keepZipAfterInstall = (v == "on"); Settings.save(this, p)
            switchNav(NAV_SETTINGS)
        } }, cardLp())

        c.addView(UiKit.divider(this, "TOOLS"))
        val tools = UiKit.card(this)
        val dr = UiKit.button(this, "🔧  运行环境诊断", true) { runDoctor() }
        tools.addView(dr)
        tools.addView(UiKit.spacer(this, 8f))
        // 随时体检下载通道，不必等到真正下载时才知道源好坏
        val mc = UiKit.button(this, "🌐  下载通道体检", false) { checkMirrorsNow() }
        tools.addView(mc)
        tools.addView(UiKit.spacer(this, 8f))
        // 查看/处理已经下载好的包（下好了但装不上时用这个）
        val lp = UiKit.button(this, "📦  本地游戏包（检测 / 安装 / 删除）", false) { showLocalPacks() }
        tools.addView(lp)
        tools.addView(UiKit.spacer(this, 8f))
        val lg = UiKit.button(this, "📄  查看运行日志", false) { showLog() }
        tools.addView(lg)
        c.addView(tools, cardLp())

        c.addView(UiKit.divider(this, "ABOUT"))
        val about = UiKit.card(this)
        about.addView(UiKit.label(this, "Stronghold Shell  v" + BuildConfig.VERSION_NAME,
            14f, UiKit.TEXT_MAIN, true))
        about.addView(UiKit.spacer(this, 6f))
        about.addView(UiKit.label(
            this,
            "游戏本体来自上游 sganggs/Stronghold-Protocol (GPL-3.0)。\n" +
            "本壳仅提供安卓运行环境，不包含游戏素材。",
            11f, UiKit.TEXT_SUB
        ))
        c.addView(about, cardLp())
        c.addView(UiKit.spacer(this, 20f))
    }

    /** 一行设置项：标题 + 值 + 说明，点击触发动作 */
    private fun settingRow(
        title: String,
        value: String,
        desc: String,
        onClick: () -> Unit
    ): View {
        val card = UiKit.card(this)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(UiKit.label(this, title, 14f, UiKit.TEXT_MAIN, true))
        col.addView(UiKit.spacer(this, 3f))
        col.addView(UiKit.label(this, desc, 10f, UiKit.TEXT_DIM))
        row.addView(col)
        row.addView(UiKit.chip(this, value, UiKit.ACCENT))
        card.addView(row)
        card.isClickable = true
        card.isFocusable = true
        card.setOnClickListener { onClick() }
        return card

    }

    private fun editNumber(title: String, cur: Int, min: Int, max: Int, onOk: (Int) -> Unit) {
        val et = EditText(this).apply {
            setText(cur.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(et)
            .setPositiveButton("确定") { _, _ ->
                val v = et.text.toString().toIntOrNull()
                if (v == null || v < min || v > max) toast("请输入 $min ~ $max 之间的数字")
                else onOk(v)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 从固定选项中选择（values 为存储值，labels 为展示值，可省略） */
    private fun chooseString(
        title: String,
        cur: String,
        values: List<String>,
        labels: List<String>? = null,
        onOk: (String) -> Unit
    ) {
        val shown = labels ?: values
        val idx = values.indexOf(cur).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(shown.toTypedArray(), idx) { d, which ->
                d.dismiss()
                onOk(values[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 动作：安装 / 启动 ====================

    private fun pickLocalZip() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream"))
        }
        pickPackZip.launch(i)
    }
    /**
     * 导入本地 zip —— 自动区分「完整包」与「增量包」。
     *
     * 【为什么要分流】原逻辑把任何含 package.json + server/ 的 zip 都当完整包注册成
     * 新版本，而 -update.zip 恰好两者都有 → 注册出一个没有 node_modules 的残缺版本
     * → 点启动必崩。现在先探测包类型再决定走哪条路。
     */
    private fun doInstallPack(uri: Uri) {
        toast("正在检查包…")
        io.execute {
            runCatching {
                // 先把文件名和体积查出来：文件名能判增量包，体积能判完整包，
                // 两条都是零成本，能省掉「顺序扫 4 万个 zip 条目」那一大段等待。
                val (pkgName, pkgSize) = PackUpdater.queryMeta(this@ImportActivity, uri)
                val kind = PackUpdater.detect(this@ImportActivity, uri, pkgName, pkgSize)

                // ---------- 增量包 → 覆盖到已装版本上 ----------
                if (kind == PackUpdater.ZipKind.UPDATE) {
                    val active = PackManager.activeDir(this@ImportActivity)
                    if (active == null) {
                        ui.post { toast("这是增量包，本地还没有完整安装；请先导入完整包") }
                        return@execute
                    }
                    if (!File(active, "node_modules").isDirectory) {
                        ui.post { toast("当前版本不完整（缺 node_modules），请先导入完整包") }
                        return@execute
                    }
                    val from = PackManager.readVersionFrom(active) ?: "未知"

                    val latch = java.util.concurrent.CountDownLatch(1)
                    var go = false
                    ui.post {
                        AlertDialog.Builder(this@ImportActivity)
                            .setTitle("检测到增量包")
                            .setMessage(
                                "本地当前：v$from\n\n" +
                                    "增量包只含改动的文件，会覆盖到 v$from 上。\n" +
                                    "覆盖前自动备份，按 UPDATE.json 校验 from 与 sha256，失败自动回滚。\n\n" +
                                    "⚠️ 只适用于紧邻的上一版；不匹配会被直接拒绝（不动任何文件）。\n" +
                                    "跨版本请用「下载最新」，它会自动逐级升。"
                            )
                            .setPositiveButton("开始覆盖") { _, _ -> go = true; latch.countDown() }
                            .setNegativeButton("取消") { _, _ -> latch.countDown() }
                            .setOnCancelListener { latch.countDown() }
                            .show()
                    }
                    latch.await()
                    if (!go) return@execute

                    ui.post { toast("正在覆盖，请勿退出…") }
                    val r = PackUpdater.applyOverlay(this@ImportActivity, uri) { stage ->
                        ui.post { toast(stage) }
                    }
                    ui.post {
                        if (r.ok) {
                            toast("增量升级完成：v${r.toVersion}")
                            updateInfo = null
                            switchNav(NAV_HOME)
                        } else {
                            AlertDialog.Builder(this@ImportActivity)
                                .setTitle("增量升级失败")
                                .setMessage(
                                    r.message + "\n\n已回滚到 v${r.fromVersion ?: "原版本"}。\n" +
                                        "跨版本请改用完整包或「下载最新」。"
                                )
                                .setPositiveButton("知道了", null)
                                .show()
                        }
                    }
                    return@execute
                }

                // ---------- 精简包 / 源码包 → 明确拒绝 ----------
                if (kind == PackUpdater.ZipKind.UNKNOWN) {
                    ui.post {
                        AlertDialog.Builder(this@ImportActivity)
                            .setTitle("这不是可用的游戏包")
                            .setMessage(
                                "包里既没有 node_modules（完整包），\n" +
                                    "也没有 UPDATE.json（增量包）。\n\n" +
                                    "多半是 lite 精简包或源码包，请换完整包。"
                            )
                            .setPositiveButton("知道了", null)
                            .show()
                    }
                    return@execute
                }

                // ---------- 完整包 → 原逻辑 ----------
                toast("正在导入，请勿退出…")
                var tmp: File? = null
                try {
                    val root = PackManager.packsRoot(this@ImportActivity)
                    val stage = File(root, ".incoming")
                    stage.deleteRecursively()
                    stage.mkdirs()
                    tmp = stage

                    ZipInstaller.extract(this@ImportActivity, uri, stage)
                    // 判断是否有单层顶层目录（GitHub zip 通常含 Stronghold-Protocol/）
                    val kids = stage.listFiles()?.filter { it.name != "__MACOSX" } ?: emptyList()
                    val gameDir: File = if (kids.size == 1 && kids[0].isDirectory) kids[0] else stage

                    val version = PackManager.readVersionFrom(gameDir)
                    if (version == null) {
                        ui.post { toast("不是有效的游戏包（缺少 package.json）") }
                        return@execute
                    }
                    if (!File(gameDir, "node_modules").isDirectory) {
                        ui.post { toast("缺 node_modules，请用完整包而非 lite/源码包") }
                        return@execute
                    }
                    if (!File(gameDir, "server").exists() && !File(gameDir, "index.js").exists()) {
                        ui.post { toast("包结构不完整（缺少 server/）") }
                        return@execute
                    }

                    val id = PackManager.register(
                        this@ImportActivity, gameDir, version, source = "local", setActive = true
                    )
                    FileLogger.log("导入游戏包成功：$id (v$version)")
                    ui.post {
                        toast("导入成功：v$version")
                        updateInfo = null
                        switchNav(NAV_HOME)
                    }
                } catch (e: Exception) {
                    FileLogger.log("导入游戏包失败：${e.message}")
                    val reason = e.message ?: "未知错误"
                    ui.post { toast("导入失败：$reason") }
                } finally {
                    tmp?.deleteRecursively()
                }
            }.onFailure { e ->
                // 兜住探测 / 分支判断阶段的任何未预料异常，绝不让 io 线程裸崩
                FileLogger.log("导入流程异常：" + (e.message ?: e.javaClass.simpleName))
                ui.post { toast("导入异常：" + (e.message ?: "未知错误")) }
            }
        }
    }

    /** 从 zip 安装插件（复用 PluginManager 的解压与校验） */
    private fun doInstallPlugin(uri: Uri) {
        val gameRoot = PackManager.activeDir(this)
        if (gameRoot == null) {
            toast("请先安装游戏包")
            return
        }
        toast("正在安装插件…")
        io.execute {
            val dir = try {
                PluginManager.installFromUri(gameRoot, uri, this@ImportActivity)
            } catch (e: Exception) {
                FileLogger.log("安装插件异常：${e.message}")
                null
            }
            ui.post {
                if (dir != null) {
                    toast("插件已安装：${dir.name}")
                    switchNav(NAV_PLUGIN)
                } else {
                    AlertDialog.Builder(this@ImportActivity)
                        .setTitle("插件安装失败")
                        .setMessage(
                            "可能原因：\n" +
                            "· zip 里没有 plugin.json\n" +
                            "· 文件夹名与插件 id 不一致\n" +
                            "· id 不符合规范（2~32 位小写字母/数字/-）\n" +
                            "· 当前游戏版本太旧（插件需 0.3.0+）"
                        )
                        .setPositiveButton("知道了", null)
                        .show()
                }
            }
        }
    }

    private fun pickPluginZip() {
        if (PackManager.activeDir(this) == null) {
            toast("请先安装游戏包")
            return
        }
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream"))
        }
        pickPluginZipResult.launch(i)
    }

    private fun startGame() {
        if (PackManager.activeId(this) == null) {
            AlertDialog.Builder(this)
                .setTitle("还没有游戏包")
                .setMessage("需要先下载或导入一个游戏版本，才能启动。")
                .setPositiveButton("去下载") { _, _ -> onDownloadLatest() }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        val it = Intent(this, MainActivity::class.java)
        startActivity(it)
    }

    /** 打开多人游戏服务器列表 */
    private fun openMultiplayer() {
        startActivity(Intent(this, MultiplayerActivity::class.java))
    }

    // ==================== 动作：下载最新包 ====================

    private fun onDownloadLatest() {
        if (downloading) {
            toast("正在下载中…")
            return
        }
        // 空机：没有本地版本，只能下完整包
        if (PackManager.activeId(this) == null) {
            startFullDownload()
            return
        }
        // 有本地版本：把「增量更新」和「下载完整包」两个选择都摆出来
        showUpdateChoice()
    }

    /**
     * 更新方式选择：一次网络请求同时拿到「增量计划」和「完整包大小」，两条路都给用户。
     *
     * 【两种方式的本质区别】
     *   增量更新 —— 逐级下载 -update.zip，覆盖进现有版本目录。只下改动文件
     *              （876 KB ~ 几十 MB），mod / 素材 / 存档都保留；
     *              受 UPDATE.json 的 from 约束，必须一级一级升。
     *   完整包   —— 下 800MB 级整包，解压成一个**新的**版本目录，旧版本原样保留，
     *              可在「游戏版本」列表里随时切换；不受 from 约束，跳几个版本都行。
     */
    private fun showUpdateChoice() {
        toast("正在查询版本列表…")
        io.execute {
            val releases = SteppedUpdater.fetchAll()
            if (releases == null) {
                ui.post { toast("查询失败，请检查网络后重试") }
                return@execute
            }
            val active = PackManager.activeDir(this@ImportActivity)
            val cur = active?.let { PackManager.readVersionFrom(it) } ?: ""
            val plan = if (cur.isEmpty()) SteppedUpdater.Plan(emptyList(), null)
            else SteppedUpdater.plan(cur, releases)
            val steps = plan.steps

            // 最新一版：按版本号取最大，不依赖列表顺序
            val latest = releases.maxWithOrNull { a, b ->
                when {
                    UpdateChecker.isNewer(a.version, b.version) -> 1
                    UpdateChecker.isNewer(b.version, a.version) -> -1
                    else -> 0
                }
            }
            val fullAsset = latest?.let { UpdateChecker.pickAsset(it, UpdateChecker.PackKind.FULL) }

            ui.post {
                val rel = latest ?: run {
                    toast("没有可用的版本信息")
                    return@post
                }
                val full = fullAsset
                val canIncremental = steps.isNotEmpty()
                val totalMb = steps.sumOf { if (it.asset.sizeBytes > 0) it.asset.sizeMb else 0.0 }

                val msg = buildString {
                    append("当前版本：v").append(if (cur.isEmpty()) "未知" else cur).append("\n\n")

                    if (canIncremental) {
                        append("① 增量更新（推荐）\n")
                        append("   ").append(steps.joinToString(" → ") { "v${it.to}" })
                        append("，共 ").append(steps.size).append(" 步，下载约 ")
                        append("%.1f MB\n".format(totalMb))
                        append("   覆盖进现有版本，mod / 素材 / 存档保留；\n")
                        append("   每步校验 sha256，失败自动回滚\n")
                        if (plan.blockedAt != null) {
                            append("   ⚠️ v").append(plan.blockedAt)
                                .append(" 没有发布增量包，只能先升到 v").append(steps.last().to).append("\n")
                        }
                        append("\n")
                    } else if (plan.blockedAt != null) {
                        append("① 增量更新：不可用（v").append(plan.blockedAt).append(" 没有发布增量包）\n\n")
                    } else {
                        append("① 增量更新：当前已是最新版本，无需进行\n\n")
                    }

                    if (full != null) {
                        append("② 下载完整包\n")
                        append("   v").append(rel.version)
                        if (full.sizeBytes > 0) append("，约 %.1f MB".format(full.sizeMb))
                        append("\n")
                        append("   解压成新的版本目录，旧版本保留、可随时切换；跳几个版本都能用\n")
                    } else {
                        append("② 下载完整包：该版本没有完整包")
                    }
                }

                val b = AlertDialog.Builder(this@ImportActivity)
                    .setTitle("更新到 v${rel.version}")
                    .setMessage(msg)
                    .setNegativeButton("取消", null)

                if (canIncremental) {
                    b.setPositiveButton("增量更新（%.1f MB）".format(totalMb)) { _, _ -> runStepped() }
                } else if (full != null) {
                    b.setPositiveButton("下载完整包") { _, _ -> confirmDownload(rel, full) }
                }
                if (canIncremental && full != null) {
                    b.setNeutralButton(
                        if (full.sizeBytes > 0) "完整包（%.1f MB）".format(full.sizeMb)
                        else "下载完整包"
                    ) { _, _ -> confirmDownload(rel, full) }
                }
                b.show()
            }
        }
    }

    /** 只走完整包：空机首装，或用户明确选整包 */
    private fun startFullDownload() {
        val info = updateInfo
        if (info != null) {
            proceedDownload(info)
            return
        }
        toast("正在查询最新版本…")
        io.execute {
            val i = UpdateChecker.fetchLatest()
            ui.post {
                if (i == null) {
                    toast("查询失败，请检查网络后重试")
                } else {
                    updateInfo = i
                    proceedDownload(i)
                }
            }
        }
    }

    /** 真正跑升级链（下载 -> 覆盖 -> 下一级） */
    private fun runStepped() {
        downloading = true
        downloadCard?.visibility = View.VISIBLE
        downloadLabel?.text = "准备升级…"

        io.execute {
            val r = SteppedUpdater.run(this@ImportActivity) { s ->
                ui.post { downloadLabel?.text = s }
            }
            downloading = false
            ui.post {
                downloadCard?.visibility = View.GONE
                updateInfo = null
                if (r.ok) {
                    toast(r.message)
                    switchNav(NAV_HOME)
                } else {
                    AlertDialog.Builder(this@ImportActivity)
                        .setTitle("升级未完成")
                        .setMessage(r.message)
                        .setPositiveButton("知道了") { _, _ -> switchNav(NAV_HOME) }
                        .show()
                }
            }
        }
    }

    /**
     * 本地没有版本时下完整包。
     *
     * 【改动说明】原来这里判断「本地已有同版本才走增量」（it.version == info.version），
     * 导致本地 0.3.2 + 远程 0.3.3 时 same == null，永远去下 821MB 完整包。
     * 现在有本地版本的情况统一交给 showUpdateChoice()（增量 / 完整包二选一），
     * 这里只负责空机首装和「用户明确选了完整包」的下载。
     */
    private fun proceedDownload(info: UpdateChecker.ReleaseInfo) {
        val asset = UpdateChecker.pickAsset(info, UpdateChecker.PackKind.FULL)
        if (asset == null) {
            toast("该版本没有可下载的完整包")
            return
        }
        preferredKind = UpdateChecker.PackKind.FULL
        confirmDownload(info, asset)
    }

    private fun confirmDownload(info: UpdateChecker.ReleaseInfo, asset: UpdateChecker.Asset) {
        val packs = PackManager.listPacks(this)
        val same = packs.firstOrNull { it.version == info.version }
        val sizeMbText = "%.1f".format(asset.sizeMb)
        val cur = Settings.load(this)
        val keepText = if (cur.keepZipAfterInstall) "安装后保留压缩包" else "安装后删除压缩包"
        val msg = buildString {
            append("版本：v${info.version}\n")
            append("文件：${asset.name}\n")
            append("大小：$sizeMbText MB\n")
            append("当前位置：").append(shortDirName(cur.downloadMode)).append("\n")
            append("安装后：").append(keepText).append("\n\n")
            if (same != null) append("注意：该版本已安装，将覆盖。\n")
            append("完整包较大，建议在 Wi-Fi 下进行。\n")
            append("点「开始下载」后会先选下载位置。")
        }
        AlertDialog.Builder(this)
            .setTitle("下载 v${info.version}")
            .setMessage(msg)
            .setNeutralButton("改用增量包") { _, _ ->
                if (same == null) {
                    toast("本地还没有这个版本，无法增量升级")
                } else {
                    preferredKind = UpdateChecker.PackKind.UPDATE
                    confirmIncremental(info, same)
                }
            }
            .setPositiveButton("开始下载") { _, _ ->
                pickDirThenDownload(info, asset)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 申请写入公共 Download 目录所需权限。
     * Android 11+ 需要「所有文件访问」权限，要去系统设置页手动开，
     * 所以这里引导用户跳转，而不是弹普通权限框。
     *
     * @param onOk 已有权限时直接执行
     * @param onDenied 用户明确不授权时执行（一般用来回落到私有目录并继续）
     */
    private fun ensureStoragePermission(onOk: () -> Unit, onDenied: (() -> Unit)? = null) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            if (android.os.Environment.isExternalStorageManager()) {
                onOk()
                return
            }
            AlertDialog.Builder(this)
                .setTitle("需要文件访问权限")
                .setMessage(
                    "要把游戏包保存到公共 Download 目录（这样你能在文件管理器里找到、也能直接分享），" +
                        "需要授予「所有文件访问权限」。\n\n" +
                        "点「去授权」后在系统页面找到本应用，打开开关，然后回来重新点下载即可。"
                )
                .setPositiveButton("去授权") { _, _ ->
                    try {
                        val i = android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            android.net.Uri.parse("package:" + packageName)
                        )
                        startActivity(i)
                    } catch (e: Exception) {
                        try {
                            startActivity(android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        } catch (e2: Exception) {
                            toast("无法打开授权页面，请手动到系统设置里授权")
                        }
                    }
                }
                .setNegativeButton("改用私有目录") { _, _ ->
                    val p = Settings.load(this)
                    p.downloadMode = 0
                    Settings.save(this, p)
                    toast("已改用应用私有目录")
                    if (onDenied != null) onDenied() else onOk()
                }
                .show()
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            val need = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            if (checkSelfPermission(need) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                onOk()
            } else {
                pendingPermissionNext = onDenied
                requestPermissions(arrayOf(need), 9101)
            }
        } else {
            onOk()
        }
    }

    /** 权限回调返回后的动作（null 表示无法继续） */
    private var pendingPermissionNext: (() -> Unit)? = null

    /** 下载位置的中文短名 */
    private fun shortDirName(mode: Int): String = when (mode) {
        0 -> "应用私有目录"
        2 -> "自定义路径"
        else -> "公共 Download/StrongholdPacks"
    }

    /**
     * 下载前先选位置：列出三种预设（可当场改），选完直接开始下载。
     * 与设置页共用同一份 downloadMode / downloadPath。
     */
    private fun pickDirThenDownload(info: UpdateChecker.ReleaseInfo, asset: UpdateChecker.Asset) {
        val cur = Settings.load(this)
        val curLabel = PackDownloader.downloadDirLabel(this).replace("\n", "\n    ")
        val items = arrayOf(
            "应用私有目录",
            "公共 Download/StrongholdPacks（文件管理器可见，推荐）",
            "自定义路径…"
        )
        // 标题里带上当前路径：AlertDialog 不能同时 setMessage + setSingleChoiceItems，
        // 否则单选列表会被吞掉，用户点不到任何东西
        val titleText = "选择下载位置\n（当前：" + shortDirName(cur.downloadMode) + "）"
        val checked = when (cur.downloadMode) {
            0 -> 0
            2 -> 2
            else -> 1
        }
        AlertDialog.Builder(this)
            .setTitle(titleText)
            .setSingleChoiceItems(items, checked) { d, which ->
                when (which) {
                    0 -> {
                        val p = Settings.load(this)
                        p.downloadMode = 0
                        Settings.save(this, p)
                        d.dismiss()
                        toast("已选：应用私有目录")
                        doDownload(info, asset)
                    }
                    1 -> {
                        val p = Settings.load(this)
                        p.downloadMode = 1
                        Settings.save(this, p)
                        d.dismiss()
                        ensureStoragePermission(onOk = {
                            toast("已选：公共 Download/StrongholdPacks")
                            doDownload(info, asset)
                        })
                    }
                    2 -> {
                        d.dismiss()
                        // 先确保有文件访问权限，再让用户填路径：
                        // 没权限时 canWrite() 必然 false，会导致自定义路径永远设不成
                        ensureStoragePermission(onOk = {
                            askCustomPathForDownload(cur.downloadPath) { ok ->
                                if (ok) doDownload(info, asset)
                            }
                        })
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 下载流程里用的自定义路径输入框，确认成功后回调 */
    private fun askCustomPathForDownload(current: String, onDone: (Boolean) -> Unit) {
        val et = android.widget.EditText(this)
        et.setText(current)
        et.setHint("/sdcard/Download/StrongholdPacks")
        val pad = (16 * resources.displayMetrics.density).toInt()
        val wrap = android.widget.FrameLayout(this)
        wrap.setPadding(pad, pad / 2, pad, 0)
        wrap.addView(et)
        AlertDialog.Builder(this)
            .setTitle("自定义下载路径")
            .setMessage("填一个手机上的绝对路径，例如：\n/sdcard/Download/StrongholdPacks")
            .setView(wrap)
            .setPositiveButton("确定并下载") { _, _ ->
                val path = et.text.toString().trim()
                if (path.isEmpty() || !path.startsWith("/")) {
                    toast("路径要写完整，必须以 / 开头")
                    onDone(false)
                    return@setPositiveButton
                }
                val f = File(path)
                val ok = try {
                    if (!f.exists()) f.mkdirs()
                    f.isDirectory && f.canWrite()
                } catch (e: Exception) {
                    false
                }
                if (!ok) {
                    toast("这个路径建不出来或不可写，请换一个")
                    onDone(false)
                    return@setPositiveButton
                }
                val p = Settings.load(this)
                p.downloadMode = 2
                p.downloadPath = path
                Settings.save(this, p)
                toast("已设为：" + path)
                onDone(true)
            }
            .setNegativeButton("取消") { _, _ -> onDone(false) }
            .show()
    }

    // ==================== 增量更新（十几 MB 级升级） ====================

    /**
     * 增量升级确认框。
     *
     * 【为什么要增量】上游每个版本提供 -update.zip（纯代码补丁）：
     *   v0.3.1 完整包 806MB / 增量包 13MB，相差 61 倍。
     * 【风险】增量包只覆盖「有改动的文件」。若某版把新素材压进完整包，
     *   增量升级后会缺文件 —— 所以解压完必须校验版本号，不符就提示改用完整包。
     */
    private fun confirmIncremental(info: UpdateChecker.ReleaseInfo, same: PackManager.PackEntry) {
        val asset = UpdateChecker.pickAsset(info, UpdateChecker.PackKind.UPDATE)
        if (asset == null) {
            toast("该版本没有增量包，改用完整包")
            val full = UpdateChecker.pickAsset(info, UpdateChecker.PackKind.FULL)
            preferredKind = UpdateChecker.PackKind.FULL
            if (full != null) confirmDownload(info, full) else toast("也没有完整包")
            return
        }
        val sizeText = if (asset.sizeBytes > 0) {
            "%.1f MB".format(asset.sizeMb)
        } else {
            "约 15 MB（大小未知）"
        }
        val msg = buildString {
            append("本地已有 v${same.version}，可以直接增量升级：")
            append("\n\n")
            append("方式：增量覆盖（只下载改动的代码文件）")
            append("\n")
            append("大小：$sizeText   （完整包约 800MB）")
            append("\n")
            append("目标：${same.dir.name}（原地覆盖）")
            append("\n\n")
            append("升级前会自动备份旧包；若升级后版本号不对，会提示你改用完整包。")
        }
        AlertDialog.Builder(this)
            .setTitle("增量升级到 v${info.version}")
            .setMessage(msg)
            .setPositiveButton("开始增量升级") { _, _ -> doIncremental(info, asset, same) }
            .setNegativeButton("取消", null)
            .setNeutralButton("改用完整包") { _, _ ->
                val full = UpdateChecker.pickAsset(info, UpdateChecker.PackKind.FULL)
                preferredKind = UpdateChecker.PackKind.FULL
                if (full != null) confirmDownload(info, full) else toast("该版本没有完整包")
            }
            .show()
    }

    private fun doIncremental(
        info: UpdateChecker.ReleaseInfo,
        asset: UpdateChecker.Asset,
        same: PackManager.PackEntry
    ) {
        downloading = true
        downloadCard?.visibility = View.VISIBLE
        downloadLabel?.text = "准备增量升级…"

        io.execute {
            val dir = PackDownloader.downloadDir(this@ImportActivity)
            val dest = File(dir, asset.name)
            var lastUi = 0L

            // 先体检通道，让用户看到哪个能用、自选优先通道，再开始下载
            if (preferredMirrors.isEmpty()) {
                val chosen = java.util.concurrent.CountDownLatch(1)
                var picked: List<Pair<String, String>> = emptyList()
                var cancelled = false
                ui.post {
                    prepareMirrors(asset.downloadUrl, "选择下载通道") { order ->
                        picked = order
                        chosen.countDown()
                    }
                }
                val deadline = System.currentTimeMillis() + 120000L
                while (!chosen.await(300, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    if (isFinishing || isDestroyed) {
                        cancelled = true
                        break
                    }
                    // 兜底：两分钟内没做出选择就自己往下走，永不永久阻塞
                    if (System.currentTimeMillis() > deadline) {
                        cancelled = true
                        break
                    }
                }
                if (cancelled || picked.isEmpty()) {
                    downloading = false
                    ui.post { downloadCard?.visibility = View.GONE }
                    return@execute
                }
                preferredMirrors = picked
            }

            val ok = PackDownloader.download(
                url = asset.downloadUrl,
                dest = dest,
                expectedSize = asset.sizeBytes,
                listener = object : PackDownloader.Listener {
                    override fun onProgress(downloaded: Long, total: Long) {
                        val now = System.currentTimeMillis()
                        if (now - lastUi < 300) return
                        lastUi = now
                        val tot = if (total > 0) total else asset.sizeBytes
                        val pctInt = if (tot > 0) (downloaded * 100.0 / tot).toInt() else 0
                        val dlMb = "%.1f".format(downloaded / 1048576.0)
                        val totMb = "%.1f".format(tot / 1048576.0)
                        ui.post {
                            downloadLabel?.text = "增量包 $dlMb / $totMb MB  ($pctInt%)"
                            (downloadBar as? View)?.let { v ->
                                try {
                                    val m = v.javaClass.getMethod("setProgress", Float::class.java)
                                    m.invoke(v, if (tot > 0) (downloaded.toFloat() / tot) else 0f)
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }
                },
                preferredMirrors = preferredMirrors
            )

            if (!ok) {
                dest.delete()
                downloading = false
                ui.post {
                    downloadCard?.visibility = View.GONE
                    toast("增量包下载失败，请检查网络后重试")
                }
                return@execute
            }

            // ---- 应用补丁：剥包裹 / 备份 / 覆盖 / 删清单 / 校验 / 回滚 全交给 PackUpdater ----
            ui.post { downloadLabel?.text = "正在应用增量补丁…" }
            val r = PackUpdater.applyOverlayFile(
                this@ImportActivity, dest, targetId = same.id
            ) { stage -> ui.post { downloadLabel?.text = stage } }
            dest.delete()
            downloading = false

            if (r.ok) {
                val newVer = r.toVersion ?: "unknown"
                FileLogger.log("增量升级完成：${r.fromVersion} -> $newVer")
                ui.post {
                    downloadCard?.visibility = View.GONE
                    toast("增量升级完成：v$newVer")
                    updateInfo = null
                    switchNav(NAV_HOME)
                }
            } else {
                val reason = r.message
                FileLogger.log("增量升级失败：" + reason)
                ui.post {
                    downloadCard?.visibility = View.GONE
                    AlertDialog.Builder(this@ImportActivity)
                        .setTitle("增量升级失败")
                        .setMessage(reason + "\n\n已回滚到原版本，可以改用完整包重试。")
                        .setPositiveButton("用完整包") { _, _ ->
                            val full = UpdateChecker.pickAsset(info, UpdateChecker.PackKind.FULL)
                            preferredKind = UpdateChecker.PackKind.FULL
                            if (full != null) confirmDownload(info, full) else toast("该版本没有完整包")
                        }
                        .setNegativeButton("关闭", null)
                        .show()
                }
            }
        }
    }

    /**
     * 下载中的「换个源」弹窗：列出所有通道的最近实测状态，
     * 让用户自己指定换到哪一个，而不是只能盲目往后轮。
     */
    private fun showSwitchMirrorDialog() {
        val probes = PackDownloader.lastProbeResult
        if (probes.isEmpty()) {
            // 还没测过：直接往后轮
            PackDownloader.requestSwitchMirror()
            toast("正在换到下一个通道…（已下载部分会保留）")
            return
        }
        val list = android.widget.LinearLayout(this)
        list.orientation = android.widget.LinearLayout.VERTICAL
        val pad = (16 * resources.displayMetrics.density).toInt()
        list.setPadding(pad, pad / 2, pad, pad / 2)

        val head = android.widget.TextView(this)
        head.text = "当前已下载 " + PackDownloader.fmtMb(curDownloadedBytes) + " MB\n点一项换过去（会续传，不用重下）："
        head.textSize = 13f
        list.addView(head)

        var dlg: AlertDialog? = null
        for (p in probes) {
            val row = android.widget.TextView(this)
            row.text = if (p.ok) {
                "✅ " + p.name + "    " + p.costMs + "ms"
            } else {
                "❌ " + p.name + "    " + p.reason
            }
            row.textSize = 14f
            row.setPadding(0, pad / 2, 0, pad / 2)
            row.setOnClickListener {
                if (!p.ok) {
                    toast("这个通道上次测是不通的（" + p.reason + "），仍会尝试")
                }
                PackDownloader.requestSwitchMirrorTo(p.prefix, p.name)
                FileLogger.log("用户指定换源到：" + p.name + "（已下 " + PackDownloader.fmtMb(curDownloadedBytes) + " MB）")
                toast("正在切换到 " + p.name + "…")
                dlg?.dismiss()
            }
            list.addView(row)
        }

        val sv = android.widget.ScrollView(this)
        sv.addView(list)
        dlg = AlertDialog.Builder(this)
            .setTitle("选择下载通道")
            .setView(sv)
            .setNeutralButton("顺序换下一个") { _, _ ->
                PackDownloader.requestSwitchMirror()
                toast("正在换到下一个通道…")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 设置页里的“本地游戏包”：列出下载目录里已有的包，逐个体检，
     * 并给出处理选项（直接安装 / 重新下载 / 删除 / 打开目录）。
     * 专门解决“包其实下好了、但装不上又找不到在哪”这种情况。
     */
    private fun showLocalPacks() {
        io.execute {
            val zips = PackDownloader.listLocalZips(this@ImportActivity)
            // 已知官方大小，用来判断完整性
            var expected = 0L
            try {
                val info = UpdateChecker.fetchLatest()
                val a = info?.let { UpdateChecker.pickAsset(it, UpdateChecker.PackKind.FULL) }
                expected = a?.sizeBytes ?: 0L
            } catch (_: Exception) {
            }
            val checks = zips.map { it to PackDownloader.checkLocal(it, expected) }
            val dir = PackDownloader.downloadDir(this@ImportActivity)
            ui.post {
                if (checks.isEmpty()) {
                    AlertDialog.Builder(this@ImportActivity)
                        .setTitle("本地游戏包")
                        .setMessage(
                            "下载目录里没有任何压缩包。\n\n" +
                                "目录位置：\n" + dir.absolutePath + "\n\n" +
                                "说明：Android 11 以后系统限制，文件管理器一般进不去这个目录，\n" +
                                "可以用下面的「打开下载目录」按钮，或把手机连电脑查看。"
                        )
                        .setPositiveButton("打开下载目录") { _, _ -> openDownloadDir() }
                        .setNegativeButton("关闭", null)
                        .show()
                    return@post
                }
                // 注意：AlertDialog 的 setMessage 和 setItems 互斥，同时用会导致列表不显示。
                // 所以这里用自定义的列表视图，把文件名、状态、路径都放进每一行。
                val list = android.widget.LinearLayout(this@ImportActivity)
                list.orientation = android.widget.LinearLayout.VERTICAL
                val pad = (16 * resources.displayMetrics.density).toInt()
                list.setPadding(pad, pad / 2, pad, pad / 2)
                for ((f, c) in checks) {
                    val row = android.widget.TextView(this@ImportActivity)
                    val mark = if (c.problem.isEmpty()) "✅" else "❌"
                    val txt = StringBuilder()
                    txt.append(mark).append(" ").append(f.name).append("\n")
                    txt.append("    大小 ").append(PackDownloader.fmtMb(c.size)).append(" MB")
                    if (c.isZip) txt.append("，内 ").append(c.entries).append(" 个文件")
                    if (c.problem.isNotEmpty()) txt.append("\n    问题：").append(c.problem)
                    row.text = txt.toString()
                    row.textSize = 13f
                    row.setPadding(0, pad / 2, 0, pad / 2)
                    row.setOnClickListener {
                        handleLocalPack(f, c)
                        currentLocalDialog?.dismiss()
                    }
                    list.addView(row)
                }
                val sv = android.widget.ScrollView(this@ImportActivity)
                sv.addView(list)
                currentLocalDialog = AlertDialog.Builder(this@ImportActivity)
                    .setTitle("本地游戏包（点选处理）")
                    .setView(sv)
                    .setPositiveButton("打开下载目录") { _, _ -> openDownloadDir() }
                    .setNegativeButton("关闭", null)
                    .show()
            }
        }
    }

    /** 针对某个本地包给出处理选项 */
    private fun handleLocalPack(f: File, c: PackDownloader.LocalCheck) {
        val canInstall = c.isZip && c.zipOk && c.problem.isEmpty()
        val title = if (canInstall) "这个包是好的" else "这个包有问题"
        val info = StringBuilder()
        info.append("文件：").append(f.name).append("\n")
        info.append("大小：").append(PackDownloader.fmtMb(c.size)).append(" MB\n")
        info.append("路径：").append(f.absolutePath).append("\n\n")
        if (canInstall) {
            info.append("检测通过：ZIP 结构完整，共 ").append(c.entries).append(" 个文件。\n")
            info.append("可以直接安装。")
        } else {
            info.append("问题：").append(c.problem).append("\n\n")
            if (!c.isZip) {
                info.append("说明：这不是一个真正的游戏压缩包，多半是下载时拿到了镜像的网页错误页。")
            } else {
                info.append("说明：压缩包结构不完整，通常是下载中断导致的，需要重新下载。")
            }
        }
        val b = AlertDialog.Builder(this@ImportActivity)
        b.setTitle(title)
        b.setMessage(info.toString())
        if (canInstall) {
            b.setPositiveButton("直接安装") { _, _ -> installLocalPack(f) }
            b.setNeutralButton("删除") { _, _ -> deleteLocalPack(f) }
        } else {
            b.setPositiveButton("删除并重新下载") { _, _ ->
                f.delete()
                toast("已删除，请重新下载")
                checkUpdate(silent = false)
            }
            b.setNeutralButton("只删除") { _, _ -> deleteLocalPack(f) }
        }
        b.setNegativeButton("关闭", null)
        b.show()
    }

    private fun deleteLocalPack(f: File) {
        val ok = try {
            f.delete()
        } catch (e: Exception) {
            false
        }
        toast(if (ok) "已删除" else "删除失败")
        FileLogger.log("删除本地包：" + f.name + (if (ok) " 成功" else " 失败"))
    }

    /** 用本地已有的包直接解压安装 */
    private fun installLocalPack(f: File) {
        if (downloading) {
            toast("正在下载中，请稍候")
            return
        }
        downloading = true
        downloadCard?.visibility = View.VISIBLE
        downloadLabel?.text = "正在安装本地包…"
        FileLogger.log("从本地包安装：" + f.absolutePath)
        io.execute {
            try {
                val root = PackManager.packsRoot(this@ImportActivity)
                val stage = File(root, ".incoming")
                stage.deleteRecursively()
                stage.mkdirs()
                var last = 0L
                val n = ZipInstaller.extractFile(f, stage) { done, _ ->
                    val now = System.currentTimeMillis()
                    if (now - last > 400) {
                        last = now
                        ui.post { downloadLabel?.text = "解压中… 已处理 $done 个文件" }
                    }
                }
                if (n <= 0) throw IllegalStateException("解压出 0 个文件")
                val entries = stage.listFiles()
                val base = if (entries != null && entries.size == 1 && entries[0].isDirectory)
                    entries[0] else stage
                val ver = guessVersion(f.name)
                val dest = File(root, ver)
                dest.deleteRecursively()
                if (!base.renameTo(dest)) {
                    base.copyRecursively(dest, overwrite = true)
                    stage.deleteRecursively()
                }
                PackManager.setActive(this@ImportActivity, ver)
                FileLogger.log("本地包安装完成：v$ver（$n 个文件）")
                ui.post {
                    downloadCard?.visibility = View.GONE
                    downloading = false
                    toast("安装完成：v$ver")
                    AlertDialog.Builder(this@ImportActivity)
                        .setTitle("安装完成")
                        .setMessage("版本 v$ver 已安装并设为当前版本。")
                        .setPositiveButton("开始游戏") { _, _ -> startGame() }
                        .setNegativeButton("关闭", null)
                        .show()
                }
            } catch (e: Exception) {
                FileLogger.log("本地包安装失败：" + (e.message ?: "未知"))
                ui.post {
                    downloadCard?.visibility = View.GONE
                    downloading = false
                    AlertDialog.Builder(this@ImportActivity)
                        .setTitle("安装失败")
                        .setMessage((e.message ?: "未知错误") + "\n\n可以删除这个包后重新下载。")
                        .setPositiveButton("删除这个包") { _, _ -> deleteLocalPack(f) }
                        .setNegativeButton("关闭", null)
                        .show()
                }
            }
        }
    }

    /** 从文件名里猜版本号，猜不到就用时间戳 */
    private fun guessVersion(name: String): String {
        val m = Regex("""v?(\d+\.\d+\.\d+)""").find(name)
        return m?.groupValues?.get(1) ?: ("local-" + System.currentTimeMillis())
    }

    /** 打开下载目录（调系统文件管理器） */
    private fun openDownloadDir() {
        val dir = PackDownloader.downloadDir(this@ImportActivity)
        // 包名不能写死：主版本是 com.stronghold.shell，实验版是 com.stronghold.shell.lab
        val pkg = packageName
        val docId = "primary%3AAndroid%2Fdata%2F" + pkg.replace("/", "%2F") + "%2Ffiles%2Fdownloads"
        try {
            val i = android.content.Intent(android.content.Intent.ACTION_VIEW)
            i.setDataAndType(
                android.net.Uri.parse("content://com.android.externalstorage.documents/document/" + docId),
                "resource/folder"
            )
            i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
            toast("目录：" + dir.absolutePath)
        } catch (e: Exception) {
            AlertDialog.Builder(this@ImportActivity)
                .setTitle("下载目录")
                .setMessage(
                    "目录路径：\n" + dir.absolutePath + "\n\n" +
                        "Android 11 以后，这个目录属于应用私有空间，普通文件管理器看不到。\n" +
                        "查看方法：\n" +
                        "① 手机连电脑，在电脑上打开该路径\n" +
                        "② 或用支持 root / Shizuku 的文件管理器\n" +
                        "③ 直接在本页点「直接安装」，不需要进目录"
                )
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    /**
     * 设置页里的“下载通道体检”：拿最新版本完整包的地址，实测所有通道。
     */
    private fun checkMirrorsNow() {
        toast("正在体检下载通道…")
        io.execute {
            // 优先用已知的更新信息；没有就用上游 latest 的完整包地址
            var testUrl: String? = null
            try {
                val info = UpdateChecker.fetchLatest()
                val a = info?.let { UpdateChecker.pickAsset(it, UpdateChecker.PackKind.FULL) }
                testUrl = a?.downloadUrl
            } catch (_: Exception) {
            }
            if (testUrl.isNullOrBlank()) {
                testUrl = "https://github.com/sganggs/Stronghold-Protocol/releases/latest/download/Stronghold-Protocol-v0.3.3.zip"
            }
            val probes = try {
                PackDownloader.probeAll(testUrl)
            } catch (e: Exception) {
                emptyList()
            }
            val sb = StringBuilder()
            val okCount = probes.count { it.ok }
            sb.append("共 ").append(probes.size).append(" 个通道，可用 ").append(okCount).append(" 个\n\n")
            for (p in probes) {
                if (p.ok) {
                    sb.append("✅ ").append(p.name).append("   ").append(p.costMs).append("ms\n")
                } else {
                    sb.append("❌ ").append(p.name).append("   ").append(p.reason).append("\n")
                }
            }
            if (okCount == 0) {
                sb.append("\n全部通道都不可用。请检查网络，或稍后重试。\n")
            }
            sb.append("\n检测地址：\n").append(testUrl)
            FileLogger.log("手动通道体检：" + sb.toString().replace("\n", " / "))
            ui.post {
                AlertDialog.Builder(this@ImportActivity)
                    .setTitle("下载通道体检结果")
                    .setMessage(sb.toString())
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }
    }

    /**
     * 在后台体检全部下载通道，然后弹框把结果全列出来让用户选。
     * 用户选完之后，onReady 拿到的就是最终优先顺序。
     */
    private fun prepareMirrors(
        downloadUrl: String,
        title: String,
        onReady: (List<Pair<String, String>>) -> Unit
    ) {
        ui.post {
            downloadCard?.visibility = View.VISIBLE
            downloadLabel?.text = "正在检测下载通道…"
        }
        io.execute {
            val probes = try {
                PackDownloader.probeAll(downloadUrl)
            } catch (e: Exception) {
                emptyList()
            }
            FileLogger.log(
                "通道体检：" + probes.joinToString("，") {
                    it.name + if (it.ok) "✅${it.costMs}ms" else "❌${it.reason}"
                }
            )
            ui.post {
                downloadCard?.visibility = View.GONE
                val sb = StringBuilder()
                if (probes.none { it.ok }) {
                    sb.append("本次没有任何通道可用。\n\n")
                } else {
                    sb.append("点一项作为优先通道：\n\n")
                }
                // 同样不能用 setMessage + setItems：两者互斥，列表会被吞掉，
                // 那样用户点不到任何东西，后台下载线程会在 latch 上永久卡住。
                val list = android.widget.LinearLayout(this@ImportActivity)
                list.orientation = android.widget.LinearLayout.VERTICAL
                val pad = (16 * resources.displayMetrics.density).toInt()
                list.setPadding(pad, pad / 2, pad, pad / 2)
                val head = android.widget.TextView(this@ImportActivity)
                head.text = sb.toString()
                head.textSize = 13f
                list.addView(head)
                for (p in probes) {
                    val row = android.widget.TextView(this@ImportActivity)
                    row.text = if (p.ok) {
                        "✅ " + p.name + "    " + p.costMs + "ms"
                    } else {
                        "❌ " + p.name + "    " + p.reason
                    }
                    row.textSize = 14f
                    row.setPadding(0, pad / 2, 0, pad / 2)
                    row.setOnClickListener {
                        if (!p.ok) {
                            toast("该通道当前不可用（" + p.reason + "），会按顺序尝试其它通道")
                        }
                        val order = ArrayList<Pair<String, String>>()
                        order.add(p.prefix to p.name)
                        for (q in probes) {
                            if (q.ok && q.prefix != p.prefix) order.add(q.prefix to q.name)
                        }
                        for (q in probes) {
                            if (!q.ok) order.add(q.prefix to q.name)
                        }
                        preferredMirrors = order
                        currentChannelDialog?.dismiss()
                        onReady(order)
                    }
                    list.addView(row)
                }
                val sv = android.widget.ScrollView(this@ImportActivity)
                sv.addView(list)
                val bd = AlertDialog.Builder(this@ImportActivity)
                    .setTitle(title)
                    .setView(sv)
                    .setNeutralButton("不限通道，直接下载") { _, _ ->
                        preferredMirrors = emptyList()
                        onReady(PackDownloader.allAsIs())
                    }
                    .setNegativeButton("取消") { _, _ ->
                        toast("已取消下载")
                    }
                    // 关键兜底：用户按返回键关掉弹框时，也必须放行后台线程，
                    // 否则下载协程会永远等在 latch 上
                    .setOnCancelListener {
                        onReady(emptyList())
                    }
                    .setCancelable(true)
                currentChannelDialog = bd.show()
            }
        }
    }

    /**
     * 选择下载位置：三种预设 + 自定义路径
     */
    private fun chooseDownloadPath() {
        val cur = Settings.load(this)
        val opts = arrayOf(
            "应用私有目录（最稳，但文件管理器看不见）",
            "公共 Download/StrongholdPacks（文件管理器可见，方便分享）",
            "自定义路径…"
        )
        AlertDialog.Builder(this)
            .setTitle("下载位置")
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> {
                        val p = Settings.load(this)
                        p.downloadMode = 0
                        Settings.save(this, p)
                        toast("已改为应用私有目录")
                        switchNav(NAV_SETTINGS)
                    }
                    1 -> {
                        val p = Settings.load(this)
                        p.downloadMode = 1
                        Settings.save(this, p)
                        toast("已改为公共 Download 目录")
                        switchNav(NAV_SETTINGS)
                    }
                    2 -> askCustomPath(cur.downloadPath)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 让用户手填一个绝对路径 */
    private fun askCustomPath(current: String) {
        val et = android.widget.EditText(this)
        et.setText(current)
        et.setHint("/sdcard/Download/StrongholdPacks")
        val pad = (16 * resources.displayMetrics.density).toInt()
        val wrap = android.widget.FrameLayout(this)
        wrap.setPadding(pad, pad / 2, pad, 0)
        wrap.addView(et)
        AlertDialog.Builder(this)
            .setTitle("自定义下载路径")
            .setMessage("填一个手机上的绝对路径，例如：\n/sdcard/Download/StrongholdPacks")
            .setView(wrap)
            .setPositiveButton("确定") { _, _ ->
                val path = et.text.toString().trim()
                if (path.isEmpty() || !path.startsWith("/")) {
                    toast("路径要写完整，必须以 / 开头")
                    return@setPositiveButton
                }
                val f = File(path)
                val ok = try {
                    if (!f.exists()) f.mkdirs()
                    f.isDirectory && f.canWrite()
                } catch (e: Exception) {
                    false
                }
                if (!ok) {
                    toast("这个路径建不出来或不可写，请换一个")
                    return@setPositiveButton
                }
                val p = Settings.load(this)
                p.downloadMode = 2
                p.downloadPath = path
                Settings.save(this, p)
                toast("已设为：" + path)
                switchNav(NAV_SETTINGS)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doDownload(info: UpdateChecker.ReleaseInfo, asset: UpdateChecker.Asset) {
        downloading = true
        downloadCard?.visibility = View.VISIBLE
        downloadLabel?.text = "准备中…"

        io.execute {
            val dir = PackDownloader.downloadDir(this@ImportActivity)
            val dest = File(dir, asset.name)
            var lastUi = 0L

            // 抢跑式探测：第一个成功的通道立刻开始下载，不再等最慢的死链，
            // 也不再弹窗让用户先做选择（想手选可以走设置里的「下载通道体检」）
            if (preferredMirrors.isEmpty()) {
                ui.post { downloadLabel?.text = "正在连接服务器…" }
                var winner: Pair<String, String>? = null
                val winnerLatch = java.util.concurrent.CountDownLatch(1)
                val probes = try {
                    PackDownloader.probeRacing(asset.downloadUrl, 6000) { w ->
                        winner = w
                        winnerLatch.countDown()
                    }
                } catch (e: Exception) {
                    emptyList()
                }
                // 等第一个成功者（probeRacing 内部已有总超时，这里只做兜底）
                try {
                    winnerLatch.await(7, java.util.concurrent.TimeUnit.SECONDS)
                } catch (_: Exception) {
                }
                FileLogger.log(
                    "通道抢跑：" + probes.joinToString("，") {
                        it.name + if (it.ok) "✅${it.costMs}ms" else "❌${it.reason}"
                    }
                )
                // 存下来：下载中打开「换个源」弹窗时，直接把这份状态列给用户看
                PackDownloader.rememberProbe(probes)
                val w = winner
                if (w != null) {
                    // 赢家排第一，其余可用通道跟在后面作为换源备选
                    val order = ArrayList<Pair<String, String>>()
                    order.add(w)
                    for (p in probes) {
                        if (p.ok && p.prefix != w.first) order.add(p.prefix to p.name)
                    }
                    for (p in probes) {
                        if (!p.ok) order.add(p.prefix to p.name)
                    }
                    preferredMirrors = order
                    ui.post { downloadLabel?.text = "已连接 " + w.second + "，开始下载…" }
                } else {
                    // 一个都没成功：仍按原顺序逐个尝试，让失败信息更有意义
                    preferredMirrors = PackDownloader.allAsIs()
                    ui.post { downloadLabel?.text = "无可用通道，逐个尝试…" }
                }
            }

            val ok = PackDownloader.download(
                url = asset.downloadUrl,
                dest = dest,
                expectedSize = asset.sizeBytes,
                listener = object : PackDownloader.Listener {
                    // 当前速度与当前通道，供 onProgress 拼进文案
                    @Volatile
                    var speedBps: Long = 0L

                    override fun onProgress(downloaded: Long, total: Long) {
                        curDownloadedBytes = downloaded
                        val now = System.currentTimeMillis()
                        if (now - lastUi < 300) return
                        lastUi = now
                        val tot = if (total > 0) total else asset.sizeBytes
                        val pct = if (tot > 0) downloaded * 100.0 / tot else 0.0
                        val dlMb = "%.1f".format(downloaded / 1048576.0)
                        val totMb = "%.1f".format(tot / 1048576.0)
                        val pctInt = pct.toInt()
                        // 速度和剩余时间：让用户直接看出是不是卡住了
                        val sp = speedBps
                        val speedText = if (sp > 0) {
                            "  " + "%.1f".format(sp / 1048576.0) + " MB/s"
                        } else ""
                        val etaText = if (sp > 65536 && tot > downloaded) {
                            val sec = (tot - downloaded) / sp
                            val mm = sec / 60
                            val ss = sec % 60
                            "  剩余约 " + mm + "分" + ss + "秒"
                        } else ""
                        ui.post {
                            downloadLabel?.text =
                                "下载中 $dlMb / $totMb MB  ($pctInt%)" + speedText + etaText
                            (downloadBar as? View)?.let { v ->
                                try {
                                    val m = v.javaClass.getMethod("setProgress", Float::class.java)
                                    m.invoke(v, (pct / 100.0).toFloat())
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }

                    override fun onSpeed(bytesPerSec: Long) {
                        speedBps = bytesPerSec
                    }

                    override fun onMirror(idx: Int, count: Int, name: String) {
                        ui.post {
                            downloadLabel?.text = "切换到 $name（$idx/$count）…"
                            toast("已切换到：" + name)
                        }
                        FileLogger.log("下载通道切换：$name（$idx/$count）")
                    }
                },
                preferredMirrors = preferredMirrors
            )

            if (!ok) {
                downloading = false
                ui.post {
                    downloadCard?.visibility = View.GONE
                    toast("下载失败，请检查网络后重试（下次会续传）")
                }
                return@execute
            }

            ui.post {
                downloadLabel?.text = "下载完成，正在解压安装…"
                toast("下载完成，开始安装")
            }

            // ---- 解压安装 ----
            try {
                val root = PackManager.packsRoot(this@ImportActivity)
                val stage = File(root, ".incoming")
                stage.deleteRecursively()
                stage.mkdirs()
                ZipInstaller.extractFile(dest, stage)
                val kids = stage.listFiles()?.filter { it.name != "__MACOSX" } ?: emptyList()
                val gameDir = if (kids.size == 1 && kids[0].isDirectory) kids[0] else stage
                val version = PackManager.readVersionFrom(gameDir) ?: info.version
                PackManager.register(this@ImportActivity, gameDir, version, source = "github", setActive = true)
                stage.deleteRecursively()
                // 按设置决定是否保留压缩包：保留方便分享给朋友、重装免重下
                val keep = try {
                    Settings.load(this@ImportActivity).keepZipAfterInstall
                } catch (_: Exception) {
                    true
                }
                if (keep) {
                    FileLogger.log("压缩包已保留：" + dest.absolutePath)
                } else {
                    dest.delete()
                }
                FileLogger.log("在线下载并安装完成：v$version")
                downloading = false
                ui.post {
                    downloadCard?.visibility = View.GONE
                    toast("v$version 安装完成")
                    updateInfo = null
                    switchNav(NAV_HOME)
                }
            } catch (e: Exception) {
                FileLogger.log("在线包解压失败：${e.message}")
                downloading = false
                val reason = e.message ?: "解压出错"
                ui.post {
                    downloadCard?.visibility = View.GONE
                    toast("安装失败：$reason")
                }
            }
        }
    }

    // ==================== 动作：检查更新 ====================

    private fun checkUpdate(silent: Boolean, thenDownload: Boolean = false) {
        if (checkingUpdate) return
        checkingUpdate = true
        io.execute {
            val info = UpdateChecker.fetchLatest()
            checkingUpdate = false
            ui.post {
                if (info == null) {
                    if (!silent) toast("检查更新失败（网络不通）")
                    return@post
                }
                updateInfo = info

                val prefs = Settings.load(this@ImportActivity)
                prefs.lastKnownLatest = info.version
                Settings.save(this@ImportActivity, prefs)

                val active = PackManager.activeId(this@ImportActivity)
                val curV = active?.let {
                    PackManager.listPacks(this@ImportActivity).firstOrNull { p -> p.id == it }?.version
                }

                if (thenDownload) {
                    // 有本地版本 -> 增量 / 完整包二选一；空机 -> 完整包
                    if (PackManager.activeId(this@ImportActivity) != null) {
                        showUpdateChoice()
                    } else {
                        proceedDownload(info)
                    }
                    return@post
                }

                if (!silent) {
                    if (curV != null && !UpdateChecker.isNewer(info.version, curV)) {
                        toast("已是最新版本 v$curV")
                    } else {
                        toast("发现新版本 v${info.version}")
                    }
                }
                if (navIndex == NAV_HOME) switchNav(NAV_HOME)
            }
        }
    }

    // ==================== 动作：诊断 / 日志 ====================

    private fun runDoctor() {
        toast("正在诊断…")
        io.execute {
            val report = try {
                Doctor.run(this@ImportActivity)
            } catch (e: Exception) {
                "诊断出错：${e.message}"
            }
            ui.post { showTextDialog("环境诊断报告", report) }
        }
    }

    private fun showLog() {
        io.execute {
            val txt = try {
                val f = FileLogger.logFile()
                if (f != null && f.exists()) FileLogger.tail(300) else "（暂无日志）"
            } catch (e: Exception) {
                "读取日志失败：${e.message}"
            }
            ui.post {
                AlertDialog.Builder(this@ImportActivity)
                    .setTitle("最近日志")
                    .setMessage(txt)
                    .setPositiveButton("关闭", null)
                    .setNeutralButton("清空") { _, _ ->
                        io.execute { FileLogger.clear() }
                        toast("日志已清空")
                    }
                    .show()
            }
        }
    }

    private fun showTextDialog(title: String, text: String) {
        val tv = TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(UiKit.TEXT_MAIN)
            setPadding(UiKit.dp(this@ImportActivity, 16f), UiKit.dp(this@ImportActivity, 8f),
                UiKit.dp(this@ImportActivity, 16f), UiKit.dp(this@ImportActivity, 8f))
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val sv = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(sv)
            .setPositiveButton("关闭", null)
            .show()
    }

    // 权限结果回来：授予了就继续下载，没给就切回应用私有目录并继续
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 9101) {
            if (isFinishing || isDestroyed) return
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
            val next = pendingPermissionNext
            pendingPermissionNext = null
            if (granted) {
                toast("已授权，请重新点下载")
            } else {
                val p = Settings.load(this)
                p.downloadMode = 0
                Settings.save(this, p)
                toast("未授权，已改用应用私有目录")
                if (next != null) next()
            }
        }
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }
}
