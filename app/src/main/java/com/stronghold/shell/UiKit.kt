package com.stronghold.shell

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * UiKit —— 明日方舟风格 UI 工具箱（零依赖纯手写）。
 *
 * 【视觉语言（源自方案文档 docs/UI-REDESIGN-PLAN.md）】
 *   - 底色：#1b1c1e（页面）/ #232427（卡片）
 *   - 强调色：荧光青绿 #00e5c0 ~ #2ee6a8
 *   - 辅色：警示橙黄 #ffb400
 *   - 三件套：斜切角 + 细线框 + 荧光绿
 *   - 区块标题：▌竖条 + 灰色编号 01/02/03
 *   - 分隔装饰：// SYSTEM LOG
 *
 * 【为什么不用 Material】Material 的圆角+阴影与舟的硬朗斜切气质冲突，
 *   而且依赖重（APK 大几十 MB）。纯手写 Drawable 完全可控。
 */
object UiKit {

    // ==================== 颜色 ====================
    const val BG_PAGE = 0xFF1B1C1E.toInt()        // 页面底色
    const val BG_CARD = 0xFF232427.toInt()        // 卡片底色
    const val BG_CARD_HL = 0xFF2A2C30.toInt()     // 卡片高亮态
    const val ACCENT = 0xFF00E5C0.toInt()         // 荧光青绿（主）
    const val ACCENT_DIM = 0xFF2EE6A8.toInt()     // 荧光绿（次）
    const val WARN = 0xFFFFB400.toInt()           // 警示橙黄
    const val DANGER = 0xFFFF5252.toInt()         // 危险红
    const val TEXT_MAIN = 0xFFE8EAED.toInt()      // 主文字
    const val TEXT_SUB = 0xFF9AA0A6.toInt()       // 次要文字
    const val TEXT_DIM = 0xFF6B7280.toInt()       // 弱化文字
    const val LINE = 0xFF3A3D42.toInt()           // 细线框

    // ==================== 尺寸换算 ====================
    fun dp(context: Context, v: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics
        ).toInt()

    // ==================== 斜切角背景 ====================

    /**
     * 斜切角背景（舟的标志性元素）：右上角与左下角被切掉。
     *
     * @param fill    填充色（alpha=0 表示不填充）
     * @param stroke  描边色
     * @param cut     切角大小（px）
     * @param strokeW 描边宽度（px）
     */
    fun slantedBg(fill: Int, stroke: Int, cut: Float, strokeW: Float = 2f): Drawable =
        object : Drawable() {
            private val path = Path()
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            override fun onBoundsChange(bounds: Rect) {
                super.onBoundsChange(bounds)
                path.reset()
                val l = bounds.left.toFloat()
                val t = bounds.top.toFloat()
                val r = bounds.right.toFloat()
                val b = bounds.bottom.toFloat()
                path.moveTo(l, t)
                path.lineTo(r - cut, t)
                path.lineTo(r, t + cut)
                path.lineTo(r, b)
                path.lineTo(l + cut, b)
                path.lineTo(l, b - cut)
                path.close()
            }

            override fun draw(canvas: Canvas) {
                if (bounds.isEmpty) return
                if (Color.alpha(fill) > 0) {
                    paint.style = Paint.Style.FILL
                    paint.color = fill
                    canvas.drawPath(path, paint)
                }
                if (Color.alpha(stroke) > 0) {
                    paint.style = Paint.Style.STROKE
                    paint.color = stroke
                    paint.strokeWidth = strokeW
                    canvas.drawPath(path, paint)
                }
            }

            override fun setAlpha(alpha: Int) {
                paint.alpha = alpha
            }

            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
                paint.colorFilter = colorFilter
            }

            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
        }

    /** 带按压反馈的斜切角按钮背景 */
    fun slantedButtonBg(context: Context, accent: Boolean = true): Drawable {
        val cut = dp(context, 10f).toFloat()
        val sw = dp(context, 1.5f).toFloat()
        val normal = slantedBg(
            if (accent) 0x1A00E5C0 else BG_CARD,
            if (accent) ACCENT else LINE,
            cut, sw
        )
        val pressed = slantedBg(
            if (accent) 0x3300E5C0 else BG_CARD_HL,
            if (accent) ACCENT else ACCENT_DIM,
            cut, sw
        )
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), normal)
        }
    }

    /** 卡片背景（细线框 + 微斜切） */
    fun cardBg(context: Context, highlight: Boolean = false): Drawable {
        val cut = dp(context, 12f).toFloat()
        val sw = dp(context, 1f).toFloat()
        return slantedBg(
            if (highlight) BG_CARD_HL else BG_CARD,
            if (highlight) ACCENT else LINE,
            cut, sw
        )
    }

    // ==================== 控件工厂 ====================

    /** 斜切角按钮 */
    fun button(
        context: Context,
        text: String,
        accent: Boolean = true,
        onClick: (() -> Unit)? = null
    ): TextView {
        return TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextColor(if (accent) ACCENT else TEXT_MAIN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            val ph = dp(context, 14f)
            val pv = dp(context, 11f)
            setPadding(ph, pv, ph, pv)
            background = slantedButtonBg(context, accent)
            isClickable = true
            isFocusable = true
            onClick?.let { cb -> setOnClickListener { cb() } }
        }
    }

    /** 卡片容器 */
    fun card(context: Context, highlight: Boolean = false): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg(context, highlight)
            val p = dp(context, 14f)
            setPadding(p, p, p, p)
        }
    }

    /**
     * 区块标题：▌ + 标题 + 灰色编号
     * 例：▌ 游戏版本  01
     */
    fun sectionHeader(context: Context, title: String, index: String? = null): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            addView(View(context).apply {
                setBackgroundColor(ACCENT)
                layoutParams = LinearLayout.LayoutParams(dp(context, 3f), dp(context, 16f))
            })

            addView(TextView(context).apply {
                text = "  $title"
                setTextColor(TEXT_MAIN)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })

            index?.let {
                addView(TextView(context).apply {
                    text = it
                    setTextColor(TEXT_DIM)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(dp(context, 8f), 0, 0, 0)
                })
            }
        }
    }

    /** 普通文字标签 */
    fun label(
        context: Context,
        text: String,
        sizeSp: Float = 13f,
        color: Int = TEXT_MAIN,
        bold: Boolean = false
    ): TextView = TextView(context).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    /** 装饰分隔线：// SYSTEM LOG */
    fun divider(context: Context, text: String = ""): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val v = dp(context, 12f)
            setPadding(0, v, 0, v)

            addView(View(context).apply {
                setBackgroundColor(LINE)
                layoutParams = LinearLayout.LayoutParams(0, dp(context, 1f), 1f)
            })
            if (text.isNotEmpty()) {
                addView(TextView(context).apply {
                    this.text = "  $text  "
                    setTextColor(TEXT_DIM)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                })
                addView(View(context).apply {
                    setBackgroundColor(LINE)
                    layoutParams = LinearLayout.LayoutParams(0, dp(context, 1f), 1f)
                })
            }
        }
    }

    /** 状态小圆点 */
    fun dot(context: Context, color: Int, sizeDp: Float = 7f): View {
        return View(context).apply {
            val s = dp(context, sizeDp)
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                rightMargin = dp(context, 6f)
                gravity = Gravity.CENTER_VERTICAL
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
        }
    }

    /** 竖向间距 */
    fun spacer(context: Context, dp: Float): View =
        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(1, dp(context, dp))
        }

    /** 横向间距 */
    fun hSpacer(context: Context, dp: Float): View =
        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, dp), 1)
        }

    /**
     * 细横条进度条（荧光绿）。
     *
     * 抽成具名类而不是匿名 View：调用方要能直接 `bar.progress = 0.42f`。
     * 匿名类的话外部拿不到这个属性，之前下载代码里只能用反射去调 setProgress，
     * 又慢又容易在混淆后失效。
     */
    class ThinProgressBar(context: Context, heightDp: Float) : View(context) {
        var progress: Float = 0f
            set(value) {
                field = value.coerceIn(0f, 1f)
                invalidate()
            }

        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(context, heightDp)
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val h = this.height.toFloat()
            p.color = 0xFF2F3236.toInt()
            canvas.drawRect(0f, 0f, width.toFloat(), h, p)
            p.color = ACCENT
            canvas.drawRect(0f, 0f, width * progress, h, p)
            if (progress > 0.02f) {
                p.color = 0xFFFFFFFF.toInt()
                val x = width * progress
                canvas.drawRect(x - UiKit.dp(context, 2f), 0f, x, h, p)
            }
        }
    }

    /** 进度条（细横条，荧光绿） */
    fun progressBar(context: Context, heightDp: Float = 6f): ThinProgressBar =
        ThinProgressBar(context, heightDp)

    /** 标签 chip（如 "0.3.1" "UPSTREAM"） */
    fun chip(context: Context, text: String, color: Int = ACCENT): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            val ph = dp(context, 7f)
            val pv = dp(context, 3f)
            setPadding(ph, pv, ph, pv)
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 3f).toFloat()
                setColor((color and 0x00FFFFFF) or 0x22000000)
                setStroke(dp(context, 1f), (color and 0x00FFFFFF) or 0x55000000)
            }
        }
    }
}