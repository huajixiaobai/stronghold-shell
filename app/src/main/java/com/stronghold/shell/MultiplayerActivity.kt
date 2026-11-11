package com.stronghold.shell

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.UUID

/**
 * 多人游戏服务器列表。
 *
 * 定位：只提供一个「填服务器地址」的入口，启动器本身不持有任何服务器配置。
 * 谁和谁填了同一个地址，就连到同一个服务器，在这个服务器上建房/加入房间一起玩。
 */
class MultiplayerActivity : AppCompatActivity() {

    private lateinit var c: LinearLayout
    private val servers = mutableListOf<ServerEntry>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildRoot())
        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    // ==================== 骨架 ====================

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiKit.BG_PAGE)
        }

        root.addView(buildTopBar())
        root.addView(View(this).apply {
            setBackgroundColor(UiKit.ACCENT)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2))
        })

        val scroll = ScrollView(this).apply { isFillViewport = true }
        c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        scroll.addView(c)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(buildBottomBar())
        return root
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(UiKit.BG_PAGE)
            setPadding(dp(8), dp(14), dp(16), dp(12))
        }

        val back = TextView(this).apply {
            text = "‹"
            textSize = 30f
            setTextColor(UiKit.ACCENT)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), 0, dp(16), 0)
            setOnClickListener { finish() }
        }
        bar.addView(back)

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(UiKit.label(this, "多人游戏", 18f, UiKit.TEXT_MAIN, true))
        box.addView(UiKit.label(this, "MULTIPLAYER · 填同一个地址即可同服", 9f, UiKit.TEXT_DIM))
        bar.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        return bar
    }

    private fun buildBottomBar(): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiKit.BG_PAGE)
            setPadding(dp(16), dp(10), dp(16), dp(18))
        }
        wrap.addView(View(this).apply {
            setBackgroundColor(UiKit.BG_CARD_HL)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        })
        wrap.addView(UiKit.spacer(this, 12f))
        val add = UiKit.button(this, "＋  添加服务器", true) { showEditDialog(null) }
        wrap.addView(add, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        return wrap
    }

    // ==================== 列表 ====================

    private fun reload() {
        servers.clear()
        servers.addAll(ServerStore.load(this))
        render()
    }

    private fun render() {
        c.removeAllViews()

        if (servers.isEmpty()) {
            c.addView(UiKit.sectionHeader(this, "服务器列表", "01"))
            c.addView(UiKit.spacer(this, 10f))
            val empty = UiKit.card(this)
            empty.addView(UiKit.label(this, "还没有添加服务器", 15f, UiKit.TEXT_MAIN, true))
            empty.addView(UiKit.spacer(this, 8f))
            empty.addView(
                UiKit.label(
                    this,
                    "点下面的「添加服务器」，填一个地址（例如 192.168.1.10:3000）。\n" +
                        "朋友填同一个地址，就能进同一个服务器一起玩。",
                    12f, UiKit.TEXT_SUB
                )
            )
            c.addView(empty, cardLp())
            return
        }

        c.addView(UiKit.sectionHeader(this, "服务器列表", "01"))
        c.addView(UiKit.spacer(this, 10f))
        for (s in servers) c.addView(buildItem(s), cardLp())

        c.addView(UiKit.divider(this, "HOW IT WORKS"))
        val tip = UiKit.card(this)
        tip.addView(UiKit.label(this, "怎么和朋友一起玩", 13f, UiKit.TEXT_MAIN, true))
        tip.addView(UiKit.spacer(this, 8f))
        tip.addView(
            UiKit.label(
                this,
                "1. 双方都填同一个服务器地址\n" +
                    "2. 点进这个服务器，游戏内的多人玩法照常建房 / 加入\n" +
                    "3. 素材从各自手机读取，只有房间消息走服务器，很省流量",
                12f, UiKit.TEXT_SUB
            )
        )
        c.addView(tip, cardLp())
    }

    private fun buildItem(s: ServerEntry): View {
        val card = UiKit.card(this)
        card.isClickable = true

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(UiKit.label(this, s.displayName, 16f, UiKit.TEXT_MAIN, true))
        info.addView(UiKit.spacer(this, 5f))
        info.addView(UiKit.label(this, s.address, 12f, UiKit.TEXT_SUB))
        row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        row.addView(UiKit.chip(this, "进入", UiKit.ACCENT_DIM))
        card.addView(row)

        card.setOnClickListener { enter(s) }
        card.setOnLongClickListener {
            showItemMenu(s)
            true
        }
        return card
    }

    private fun showItemMenu(s: ServerEntry) {
        AlertDialog.Builder(this)
            .setTitle(s.displayName)
            .setItems(arrayOf("编辑", "删除")) { _, which ->
                when (which) {
                    0 -> showEditDialog(s)
                    1 -> confirmDelete(s)
                }
            }
            .show()
    }

    private fun confirmDelete(s: ServerEntry) {
        AlertDialog.Builder(this)
            .setTitle("删除服务器")
            .setMessage("确定删除「${s.displayName}」？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                ServerStore.delete(this, s.id)
                reload()
            }
            .show()
    }

    // ==================== 添加 / 编辑 ====================

    private fun showEditDialog(existing: ServerEntry?) {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(0))
        }

        val nameInput = EditText(this).apply {
            hint = "服务器名称（可选，方便自己认）"
            setText(existing?.name ?: "")
            inputType = InputType.TYPE_CLASS_TEXT
        }
        wrap.addView(nameInput)

        val addrInput = EditText(this).apply {
            hint = "地址，例如 192.168.1.10:3000"
            setText(existing?.address ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
        wrap.addView(addrInput, lp)

        wrap.addView(UiKit.spacer(this, 8f))
        wrap.addView(
            UiKit.label(
                this,
                "端口不写默认 3000；可以带 http:// 或 ws:// 前缀，会自动去掉。",
                11f, UiKit.TEXT_DIM
            )
        )

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "添加服务器" else "编辑服务器")
            .setView(wrap)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()

        // 自己接管「保存」，校验失败时不关闭对话框
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val res = ServerStore.normalizeAddress(addrInput.text.toString())
                if (res.address == null) {
                    addrInput.error = res.error
                    addrInput.requestFocus()
                    return@setOnClickListener
                }
                val rawName = nameInput.text.toString().trim()
                val entry = ServerEntry(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    name = rawName,
                    address = res.address,
                    lastPlayed = existing?.lastPlayed ?: 0L,
                    tls = res.tls
                )
                ServerStore.upsert(this, entry)
                dialog.dismiss()
                reload()
            }
        }
        dialog.show()
    }

    // ==================== 进入游戏 ====================

    private fun enter(s: ServerEntry) {
        if (PackManager.activeId(this) == null) {
            AlertDialog.Builder(this)
                .setTitle("还没有游戏包")
                .setMessage("需要先下载或导入一个游戏版本，才能启动。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        ServerStore.touch(this, s.id)
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_SERVER_ADDRESS, s.address)
                putExtra(MainActivity.EXTRA_SERVER_NAME, s.displayName)
                putExtra(MainActivity.EXTRA_SERVER_TLS, s.tls)
            }
        )
    }

    // ==================== 小工具 ====================

    private fun cardLp(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(10)
        return lp
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}